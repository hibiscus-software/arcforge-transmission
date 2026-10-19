/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.block;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.zagdrath.arcforgetransmission.grid.Coupling;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.data.AttachGroups;
import net.zagdrath.arcforgetransmission.grid.world.GridConfig;
import net.zagdrath.arcforgetransmission.grid.world.GridData;
import net.zagdrath.arcforgetransmission.grid.world.GridManager;
import net.zagdrath.arcforgetransmission.grid.world.Insulator;
import net.zagdrath.arcforgetransmission.tower.TowerPlacement;
import net.zagdrath.arcforgetransmission.tower.model.TowerMesh;

// The Pole-Mount Transformer (LV primary) and the MV Distribution Transformer (MV primary): three-phase in on its primaries
// (A, B, C on the lid), split phase out on its secondary (L1, N and L2 on the front: two single-phase circuits, L1 + N and
// L2 + N, sharing the neutral). An electrical link between them (a Coupling): power passes either way as the grid needs,
// up to its rating, and it holds none.
public class PoleTransformerBlockEntity extends GridEndpointBlockEntity implements GridManager.PortSource {
    // Insulator order (the attach file's): primaries A B C, then secondaries L1 N L2.
    private static final int L1 = 3, N = 4, L2 = 5;

    private double lastFlow;

    public PoleTransformerBlockEntity(BlockPos pos, BlockState state) {
        super(GridContent.POLE_TRANSFORMER_ENTITY.get(), pos, state);
    }

    public Direction facing() {
        BlockState state = getBlockState();
        return state.hasProperty(PoleTransformerBlock.FACING) ? state.getValue(PoleTransformerBlock.FACING) : Direction.NORTH;
    }

    // Its primary's tier (the can's block's).
    public VoltageTier primaryTier() {
        return getBlockState().getBlock() instanceof PoleTransformerBlock block ? block.primary() : VoltageTier.LV;
    }

    // Power through it last tick (FE/t).
    public double lastFlow() {
        return lastFlow;
    }

    // Its insulators, worked out once per facing.
    private @Nullable List<Insulator> insulatorCache;
    private @Nullable Direction cacheFacing;

    @Override
    public List<Insulator> insulators() {
        if (insulatorCache == null || cacheFacing != facing()) {
            cacheFacing = facing();
            insulatorCache = List.copyOf(buildInsulators());
        }
        return insulatorCache;
    }

    private List<Insulator> buildInsulators() {
        int turns = TowerPlacement.turns(facing());
        VoltageTier primary = primaryTier();
        List<Insulator> out = new ArrayList<>();
        List<AttachGroups.Group> groups = AttachGroups.get(primary == VoltageTier.MV ? "mv_distribution_transformer" : "pole_transformer").groups();
        for (int g = 0; g < groups.size(); g++) {
            for (AttachGroups.Terminal terminal : groups.get(g).terminals()) {
                double[] p = terminal.pos();
                double[] t = TowerMesh.turnedPoint((float) p[0], (float) p[1], (float) p[2], turns);
                out.add(new Insulator(g == 0 ? primary : VoltageTier.LV, terminal.phase(),
                        new Vec3(worldPosition.getX() + t[0], worldPosition.getY() + t[1], worldPosition.getZ() + t[2]), g));
            }
        }
        return out;
    }

    @Override
    public void ports(ServerLevel level, GridData.Dimension dimension, List<GridManager.PortHandler> out) {}

    @Override
    public void couplings(ServerLevel level, GridData.Dimension dimension, List<Coupling> out) {
        Long[] ids = new Long[6];
        for (int i = 0; i < 6; i++) {
            ids[i] = dimension.nodeAt(new GridData.Anchor(worldPosition, i));
            if (ids[i] == null) {
                return;
            }
        }
        out.add(new Coupling(worldPosition.asLong(), primaryTier(), List.of(ids[0], ids[1], ids[2]), VoltageTier.LV,
                List.of(List.of(ids[L1], ids[N]), List.of(ids[L2], ids[N])), GridConfig.POLE_TRANSFORMER_RATING.getAsInt(), 0, true));
    }

    @Override
    public void afterTick(ServerLevel level, GridData.Dimension dimension, GridManager.TickView view) {
        lastFlow = Math.abs(view.result().couplingFlow().getOrDefault(worldPosition.asLong(), 0.0));
    }

    // The can, its brackets reaching into the pole's block, and its wires.
    public AABB getRenderBox() {
        return withWires(new AABB(worldPosition).inflate(1.0, 0.25, 1.0));
    }
}
