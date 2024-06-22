package me.jellysquid.mods.sodium.client.model.light.smooth;

import me.jellysquid.mods.sodium.client.model.light.LightPipeline;
import me.jellysquid.mods.sodium.client.model.light.data.LightDataAccess;
import me.jellysquid.mods.sodium.client.model.light.data.QuadLightData;
import me.jellysquid.mods.sodium.client.model.quad.ModelQuadView;
import me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadFlags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;

/**
 * A light pipeline which produces smooth interpolated lighting and ambient occlusion for model quads. This
 * implementation makes a number of improvements over vanilla's own "smooth lighting" option. In no particular order:
 *
 * - Corner blocks are now selected from the correct set of neighbors above block faces (fixes MC-148689 and MC-12558)
 * - Shading issues caused by anisotropy are fixed by re-orientating quads to a consistent ordering (fixes MC-138211)
 * - Inset block faces are correctly shaded by their neighbors, fixing a number of problems with non-full blocks such as
 *   grass paths (fixes MC-11783 and MC-108621)
 * - Blocks next to emissive blocks are too bright (MC-260989)
 * - Synchronization issues between the main render thread's light engine and chunk build worker threads are corrected
 *   by copying light data alongside block states, fixing a number of inconsistencies in baked chunks (no open issue)
 *
 * This implementation also includes a significant number of optimizations:
 *
 * - Computed light data for a given block face is cached and re-used again when multiple quads exist for a given
 *   facing, making complex block models less expensive to render
 * - The light data cache encodes as much information as possible into integer words to improve cache locality and
 *   to eliminate the multiple array lookups that would otherwise be needed, significantly speeding up this section
 * - Block faces aligned to the block grid use a fast-path for mapping corner light values to vertices without expensive
 *   interpolation or blending, speeding up most block renders
 * - Some critical code paths have been re-written to hit the JVM's happy path, allowing it to perform auto-vectorization
 *   of the blend functions
 * - Information about a given model quad is cached to enable the light pipeline to make certain assumptions and skip
 *   unnecessary computation
 */
public class SmoothLightPipeline implements LightPipeline {
    private final LightDataAccess lightCache;
    private final AoFaceData[] cachedFaceData = new AoFaceData[12]; // 6 * 2 faces

    private long cachedPos = Long.MIN_VALUE;
    private final float[] weights = new float[4];

    public SmoothLightPipeline(LightDataAccess cache) {
        this.lightCache = cache;

        for (int i = 0; i < this.cachedFaceData.length; i++) {
            this.cachedFaceData[i] = new AoFaceData();
        }
    }

    @Override
    public void calculate(ModelQuadView quad, BlockPos pos, QuadLightData out, Direction cullFace, Direction lightFace, boolean shade) {
        updateCachedData(pos.asLong());

        int flags = quad.getFlags();
        final AoNeighborInfo neighborInfo = AoNeighborInfo.get(lightFace);

        if ((flags & ModelQuadFlags.IS_ALIGNED) != 0 || ((flags & ModelQuadFlags.IS_PARALLEL) != 0 && LightDataAccess.unpackFC(this.lightCache.get(pos)))) {
            if ((flags & ModelQuadFlags.IS_PARTIAL) == 0) {
                applyAlignedFullFace(neighborInfo, pos, lightFace, out);
            } else {
                applyAlignedPartialFace(neighborInfo, quad, pos, lightFace, out);
            }
        } else if ((flags & ModelQuadFlags.IS_PARALLEL) != 0) {
            applyParallelFace(neighborInfo, quad, pos, lightFace, out);
        } else {
            applyNonParallelFace(neighborInfo, quad, pos, lightFace, out);
        }

        applySidedBrightness(out, lightFace, shade);
    }

    private void applyAlignedFullFace(AoNeighborInfo neighborInfo, BlockPos pos, Direction dir, QuadLightData out) {
        AoFaceData faceData = getCachedFaceData(pos, dir, true);
        neighborInfo.mapCorners(faceData.lm, faceData.ao, out.lm, out.br);
    }

    private void applyAlignedPartialFace(AoNeighborInfo neighborInfo, ModelQuadView quad, BlockPos pos, Direction dir, QuadLightData out) {
        float[] weights = this.weights;
        for (int i = 0; i < 4; i++) {
            float cx = clamp(quad.getX(i));
            float cy = clamp(quad.getY(i));
            float cz = clamp(quad.getZ(i));

            neighborInfo.calculateCornerWeights(cx, cy, cz, weights);
            applyAlignedPartialFaceVertex(pos, dir, weights, i, out, true);
        }
    }

    private void applyParallelFace(AoNeighborInfo neighborInfo, ModelQuadView quad, BlockPos pos, Direction dir, QuadLightData out) {
        float[] weights = this.weights;
        for (int i = 0; i < 4; i++) {
            float cx = clamp(quad.getX(i));
            float cy = clamp(quad.getY(i));
            float cz = clamp(quad.getZ(i));

            neighborInfo.calculateCornerWeights(cx, cy, cz, weights);

            float depth = neighborInfo.getDepth(cx, cy, cz);

            if (depth >= 1.0F) {
                applyAlignedPartialFaceVertex(pos, dir, weights, i, out, false);
            } else {
                applyInsetPartialFaceVertex(pos, dir, depth, 1.0f - depth, weights, i, out);
            }
        }
    }

    private void applyNonParallelFace(AoNeighborInfo neighborInfo, ModelQuadView quad, BlockPos pos, Direction dir, QuadLightData out) {
        float[] weights = this.weights;
        for (int i = 0; i < 4; i++) {
            float cx = clamp(quad.getX(i));
            float cy = clamp(quad.getY(i));
            float cz = clamp(quad.getZ(i));

            neighborInfo.calculateCornerWeights(cx, cy, cz, weights);

            float depth = neighborInfo.getDepth(cx, cy, cz);

            if (depth <= 0.0F) {
                applyAlignedPartialFaceVertex(pos, dir, weights, i, out, true);
            } else if (depth >= 1.0F) {
                applyAlignedPartialFaceVertex(pos, dir, weights, i, out, false);
            } else {
                applyInsetPartialFaceVertex(pos, dir, depth, 1.0f - depth, weights, i, out);
            }
        }
    }

    private void applyAlignedPartialFaceVertex(BlockPos pos, Direction dir, float[] w, int i, QuadLightData out, boolean offset) {
        AoFaceData faceData = getCachedFaceData(pos, dir, offset);
        if (!faceData.hasUnpackedLightData()) {
            faceData.unpackLightData();
        }

        float sl = faceData.getBlendedSkyLight(w);
        float bl = faceData.getBlendedBlockLight(w);
        float ao = faceData.getBlendedShade(w);

        out.br[i] = ao;
        out.lm[i] = getLightMapCoord(sl, bl);
    }

    private void applyInsetPartialFaceVertex(BlockPos pos, Direction dir, float n1d, float n2d, float[] w, int i, QuadLightData out) {
        AoFaceData n1 = getCachedFaceData(pos, dir, false);
        if (!n1.hasUnpackedLightData()) {
            n1.unpackLightData();
        }

        AoFaceData n2 = getCachedFaceData(pos, dir, true);
        if (!n2.hasUnpackedLightData()) {
            n2.unpackLightData();
        }

        float ao = (n1.getBlendedShade(w) * n1d) + (n2.getBlendedShade(w) * n2d);
        float sl = (n1.getBlendedSkyLight(w) * n1d) + (n2.getBlendedSkyLight(w) * n2d);
        float bl = (n1.getBlendedBlockLight(w) * n1d) + (n2.getBlendedBlockLight(w) * n2d);

        out.br[i] = ao;
        out.lm[i] = getLightMapCoord(sl, bl);
    }

    private void applySidedBrightness(QuadLightData out, Direction face, boolean shade) {
        float brightness = lightCache.getWorld().getBrightness(face, shade);
        float[] br = out.br;

        for (int i = 0; i < br.length; i++) {
            br[i] *= brightness;
        }
    }

    private AoFaceData getCachedFaceData(BlockPos pos, Direction face, boolean offset) {
        AoFaceData data = cachedFaceData[offset ? face.ordinal() : face.ordinal() + 6];
        if (!data.hasLightData()) {
            data.initLightData(lightCache, pos, face, offset);
        }

        return data;
    }

    private void updateCachedData(long key) {
        if (cachedPos != key) {
            for (AoFaceData data : cachedFaceData) {
                data.reset();
            }
            cachedPos = key;
        }
    }

    private static float clamp(float v) {
        return MathHelper.clamp(v, 0.0f, 1.0f);
    }

    private static int getLightMapCoord(float sl, float bl) {
        return (((int) sl & 0xFF) << 16) | ((int) bl & 0xFF);
    }
}
