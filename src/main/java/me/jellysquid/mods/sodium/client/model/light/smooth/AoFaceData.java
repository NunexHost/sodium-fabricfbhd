package me.jellysquid.mods.sodium.client.model.light.smooth;

import me.jellysquid.mods.sodium.client.model.light.data.LightDataAccess;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import static me.jellysquid.mods.sodium.client.model.light.data.ArrayLightDataCache.*;

class AoFaceData {
    private final int[] lm = new int[4];
    private final float[] ao = new float[4];
    private final float[] bl = new float[4];
    private final float[] sl = new float[4];
    private int flags;

    public void initLightData(LightDataAccess cache, BlockPos pos, Direction direction, boolean offset) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();

        int adjX = x;
        int adjY = y;
        int adjZ = z;

        if (offset) {
            adjX += direction.getOffsetX();
            adjY += direction.getOffsetY();
            adjZ += direction.getOffsetZ();
        }

        int adjWord = cache.get(adjX, adjY, adjZ);

        int calm;
        boolean caem;

        if (offset && unpackFO(adjWord)) {
            int originWord = cache.get(x, y, z);
            calm = getLightmap(originWord);
            caem = unpackEM(originWord);
        } else {
            calm = getLightmap(adjWord);
            caem = unpackEM(adjWord);
        }

        float caao = unpackAO(adjWord);

        Direction[] faces = AoNeighborInfo.get(direction).faces;

        int e0 = cache.get(adjX, adjY, adjZ, faces[0]);
        int e0lm = getLightmap(e0);
        float e0ao = unpackAO(e0);
        boolean e0op = unpackOP(e0);
        boolean e0em = unpackEM(e0);

        int e1 = cache.get(adjX, adjY, adjZ, faces[1]);
        int e1lm = getLightmap(e1);
        float e1ao = unpackAO(e1);
        boolean e1op = unpackOP(e1);
        boolean e1em = unpackEM(e1);

        int e2 = cache.get(adjX, adjY, adjZ, faces[2]);
        int e2lm = getLightmap(e2);
        float e2ao = unpackAO(e2);
        boolean e2op = unpackOP(e2);
        boolean e2em = unpackEM(e2);

        int e3 = cache.get(adjX, adjY, adjZ, faces[3]);
        int e3lm = getLightmap(e3);
        float e3ao = unpackAO(e3);
        boolean e3op = unpackOP(e3);
        boolean e3em = unpackEM(e3);

        int c0lm, c1lm, c2lm, c3lm;
        float c0ao, c1ao, c2ao, c3ao;
        boolean c0em, c1em, c2em, c3em;

        if (e2op && e0op) {
            c0lm = e0lm;
            c0ao = e0ao;
            c0em = e0em;
        } else {
            int d0 = cache.get(adjX, adjY, adjZ, faces[0], faces[2]);
            c0lm = getLightmap(d0);
            c0ao = unpackAO(d0);
            c0em = unpackEM(d0);
        }

        if (e3op && e0op) {
            c1lm = e0lm;
            c1ao = e0ao;
            c1em = e0em;
        } else {
            int d1 = cache.get(adjX, adjY, adjZ, faces[0], faces[3]);
            c1lm = getLightmap(d1);
            c1ao = unpackAO(d1);
            c1em = unpackEM(d1);
        }

        if (e2op && e1op) {
            c2lm = e1lm;
            c2ao = e1ao;
            c2em = e1em;
        } else {
            int d2 = cache.get(adjX, adjY, adjZ, faces[1], faces[2]);
            c2lm = getLightmap(d2);
            c2ao = unpackAO(d2);
            c2em = unpackEM(d2);
        }

        if (e3op && e1op) {
            c3lm = e1lm;
            c3ao = e1ao;
            c3em = e1em;
        } else {
            int d3 = cache.get(adjX, adjY, adjZ, faces[1], faces[3]);
            c3lm = getLightmap(d3);
            c3ao = unpackAO(d3);
            c3em = unpackEM(d3);
        }

        ao[0] = (e3ao + e0ao + c1ao + caao) * 0.25f;
        ao[1] = (e2ao + e0ao + c0ao + caao) * 0.25f;
        ao[2] = (e2ao + e1ao + c2ao + caao) * 0.25f;
        ao[3] = (e3ao + e1ao + c3ao + caao) * 0.25f;

        lm[0] = calculateCornerBrightness(e3lm, e0lm, c1lm, calm, e3em, e0em, c1em, caem);
        lm[1] = calculateCornerBrightness(e2lm, e0lm, c0lm, calm, e2em, e0em, c0em, caem);
        lm[2] = calculateCornerBrightness(e2lm, e1lm, c2lm, calm, e2em, e1em, c2em, caem);
        lm[3] = calculateCornerBrightness(e3lm, e1lm, c3lm, calm, e3em, e1em, c3em, caem);

        flags |= AoCompletionFlags.HAS_LIGHT_DATA;
    }

    public void unpackLightData() {
        bl[0] = unpackBlockLight(lm[0]);
        bl[1] = unpackBlockLight(lm[1]);
        bl[2] = unpackBlockLight(lm[2]);
        bl[3] = unpackBlockLight(lm[3]);

        sl[0] = unpackSkyLight(lm[0]);
        sl[1] = unpackSkyLight(lm[1]);
        sl[2] = unpackSkyLight(lm[2]);
        sl[3] = unpackSkyLight(lm[3]);

        flags |= AoCompletionFlags.HAS_UNPACKED_LIGHT_DATA;
    }

    public float getBlendedSkyLight(float[] w) {
        return weightedSum(sl, w);
    }

    public float getBlendedBlockLight(float[] w) {
        return weightedSum(bl, w);
    }

    public float getBlendedShade(float[] w) {
        return weightedSum(ao, w);
    }

    private static float weightedSum(float[] v, float[] w) {
        return v[0] * w[0] + v[1] * w[1] + v[2] * w[2] + v[3] * w[3];
    }

    private static float unpackSkyLight(int i) {
        return (i >> 16) & 0xFF;
    }

    private static float unpackBlockLight(int i) {
        return i & 0xFF;
    }

    private static int calculateCornerBrightness(int a, int b, int c, int d, boolean aem, boolean bem, boolean cem, boolean dem) {
        if (a == 0 || b == 0 || c == 0 || d == 0) {
            int min = minNonZero(minNonZero(a, b), minNonZero(c, d));
            a = Math.max(a, min);
            b = Math.max(b, min);
            c = Math.max(c, min);
            d = Math.max(d, min);
        }

        if (aem) a = LightmapTextureManager.MAX_LIGHT_COORDINATE;
        if (bem) b = LightmapTextureManager.MAX_LIGHT_COORDINATE;
        if (cem) c = LightmapTextureManager.MAX_LIGHT_COORDINATE;
        if (dem) d = LightmapTextureManager.MAX_LIGHT_COORDINATE;

        return ((a + b + c + d) >> 2) & 0xFF00FF;
    }

    private static int minNonZero(int a, int b) {
        if (a == 0) return b;
        if (b == 0) return a;
        return Math.min(a, b);
    }

    public boolean hasLightData() {
        return (flags & AoCompletionFlags.HAS_LIGHT_DATA) != 0;
    }

    public boolean hasUnpackedLightData() {
        return (flags & AoCompletionFlags.HAS_UNPACKED_LIGHT_DATA) != 0;
    }

    public void reset() {
        flags = 0;
    }
}
