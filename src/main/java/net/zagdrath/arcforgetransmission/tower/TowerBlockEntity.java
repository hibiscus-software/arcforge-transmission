/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.tower;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.block.GridEndpointBlockEntity;
import net.zagdrath.arcforgetransmission.grid.data.AttachGroups;
import net.zagdrath.arcforgetransmission.grid.world.Insulator;
import net.zagdrath.arcforgetransmission.tower.model.TowerMesh;

// A transmission tower's main block entity: what TowerRenderer draws the tower from, what clears its parts away when
// it goes, and the tower's insulators for the grid (each one its own wire point, from the tower's attach groups). It
// holds no data of its own: the tier is its block, the facing its block state, the labels GridData's.
public class TowerBlockEntity extends GridEndpointBlockEntity {
    private boolean removing;
    // The box the tower is drawn in, worked out once (its tier and facing never change).
    private @Nullable AABB renderBox;

    public TowerBlockEntity(BlockPos pos, BlockState state) {
        super(TowerContent.TOWER_ENTITY.get(), pos, state);
    }

    public TowerTier tier() {
        return getBlockState().getBlock() instanceof TowerBlock tower ? tower.tier() : TowerTier.LV;
    }

    public Direction facing() {
        BlockState state = getBlockState();
        return state.hasProperty(TowerBlock.FACING) ? state.getValue(TowerBlock.FACING) : Direction.NORTH;
    }

    // Every insulator, group by group, turned with the tower.
    @Override
    public List<Insulator> insulators() {
        TowerTier tier = tier();
        Direction facing = facing();
        int turns = TowerPlacement.turns(facing);
        BlockPos centre = TowerPlacement.centre(tier, facing, worldPosition);
        VoltageTier voltage = VoltageTier.valueOf(tier.name());
        List<Insulator> out = new ArrayList<>();
        List<AttachGroups.Group> groups = AttachGroups.get(tier.id()).groups();
        for (int g = 0; g < groups.size(); g++) {
            for (AttachGroups.Terminal terminal : groups.get(g).terminals()) {
                double[] p = terminal.pos();
                double[] t = TowerMesh.turnedPoint((float) p[0], (float) p[1], (float) p[2], turns);
                out.add(new Insulator(voltage, terminal.phase(), new Vec3(centre.getX() + t[0], centre.getY() + t[1], centre.getZ() + t[2]), g));
            }
        }
        return out;
    }

    public boolean isRemoving() {
        return removing;
    }

    // The tower's box, and its wires'.
    public AABB getRenderBox() {
        if (renderBox == null) {
            renderBox = TowerPlacement.renderBox(tier(), facing(), worldPosition);
        }
        return withWires(renderBox);
    }

    // The whole tower goes with its main block; its parts drop nothing. Its wires go too, dropping their coils
    // (GridEndpointBlockEntity).
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        removing = true;
        super.preRemoveSideEffects(pos, state);
        if (level == null || level.isClientSide() || !(state.getBlock() instanceof TowerBlock tower)) {
            return;
        }
        Direction facing = state.getValue(TowerBlock.FACING);
        for (BlockPos part : TowerPlacement.positions(tower.tier(), facing, TowerPlacement.centre(tower.tier(), facing, pos))) {
            if (!part.equals(pos) && level.getBlockState(part).getBlock() instanceof TowerPartBlock
                    && pos.equals(TowerPartBlock.mainOf(level, part))) {
                level.setBlock(part, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }
}
