package me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline;

import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;

public class BlockOcclusionCache {
    private static final int CACHE_SIZE = 512;

    private final Object2IntLinkedOpenHashMap<ShapeComparison> comparisonLookupTable;
    private final ShapeComparison cachedComparisonObject = new ShapeComparison();
    private final BlockPos.Mutable cachedPositionObject = new BlockPos.Mutable();

    public BlockOcclusionCache() {
        this.comparisonLookupTable = new Object2IntLinkedOpenHashMap<>(CACHE_SIZE);
        this.comparisonLookupTable.defaultReturnValue(ENTRY_ABSENT);
    }

    public boolean shouldDrawSide(BlockState selfState, BlockView view, BlockPos selfPos, Direction facing) {
        BlockPos.Mutable otherPos = this.cachedPositionObject;
        otherPos.set(selfPos.getX() + facing.getOffsetX(), selfPos.getY() + facing.getOffsetY(), selfPos.getZ() + facing.getOffsetZ());

        BlockState otherState = view.getBlockState(otherPos);

        if (selfState.isSideInvisible(otherState, facing)) {
            return false;
        }

        if (!otherState.isOpaque()) {
            return true;
        }

        VoxelShape selfShape = selfState.getCullingFace(view, selfPos, facing);
        if (selfShape.isEmpty()) {
            return true;
        }

        VoxelShape otherShape = otherState.getCullingFace(view, otherPos, facing.getOpposite());
        if (otherShape.isEmpty()) {
            return true;
        }

        if (selfShape == VoxelShapes.fullCube() && otherShape == VoxelShapes.fullCube()) {
            return false;
        }

        return this.lookup(selfShape, otherShape);
    }

    private boolean lookup(VoxelShape self, VoxelShape other) {
        ShapeComparison comparison = this.cachedComparisonObject;
        comparison.self = self;
        comparison.other = other;

        return switch (this.comparisonLookupTable.getAndMoveToFirst(comparison)) {
            case ENTRY_FALSE -> false;
            case ENTRY_TRUE -> true;
            default -> this.calculate(comparison);
        };
    }

    private boolean calculate(ShapeComparison comparison) {
        boolean result = VoxelShapes.matchesAnywhere(comparison.self, comparison.other, BooleanBiFunction.ONLY_FIRST);

        if (this.comparisonLookupTable.size() >= CACHE_SIZE) {
            this.comparisonLookupTable.removeLastInt();
        }

        this.comparisonLookupTable.putAndMoveToFirst(comparison.copy(), (result ? ENTRY_TRUE : ENTRY_FALSE));

        return result;
    }

    private static final int ENTRY_ABSENT = -1;
    private static final int ENTRY_FALSE = 0;
    private static final int ENTRY_TRUE = 1;

    private static final class ShapeComparison {
        private VoxelShape self, other;

        private ShapeComparison() {}

        private ShapeComparison(VoxelShape self, VoxelShape other) {
            this.self = self;
            this.other = other;
        }

        public ShapeComparison copy() {
            return new ShapeComparison(this.self, this.other);
        }
    }
}
