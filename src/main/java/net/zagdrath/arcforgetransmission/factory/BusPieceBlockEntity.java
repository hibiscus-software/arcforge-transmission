/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.factory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.zagdrath.arcforgetransmission.grid.Coupling;
import net.zagdrath.arcforgetransmission.grid.FlowSolver;
import net.zagdrath.arcforgetransmission.grid.GridEdge;
import net.zagdrath.arcforgetransmission.grid.Phase;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.block.GridEndpointBlockEntity;
import net.zagdrath.arcforgetransmission.grid.world.GridData;
import net.zagdrath.arcforgetransmission.grid.world.GridManager;
import net.zagdrath.arcforgetransmission.grid.world.Insulator;

// A piece of busway in the world (a run's block, a feed-in, a tap-off, a reducer): its conductors in groups of three (A B
// C), and its joints. A joint between two connected pieces is one 3-conductor connection (BUS edges a block long, A to A,
// B to B, C to C) at the run's size; both pieces want it, so each makes it if it's missing and drops any of its joints its
// shape no longer has. Joints are made as pieces are placed, load and change: busway alone makes a grid.
public abstract class BusPieceBlockEntity extends GridEndpointBlockEntity implements GridManager.PortSource {
    private static final Phase[] ABC = { Phase.A, Phase.B, Phase.C };

    protected BusPieceBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public abstract VoltageTier tier();

    // Its groups of three.
    protected int groups() {
        return 1;
    }

    // The group facing a side (where a piece there joins), or -1.
    public abstract int groupToward(Direction side);

    // The size of its busway on a side (its joint's), or 0 for none.
    public abstract int sizeToward(Direction side);

    @Override
    public List<Insulator> insulators() {
        List<Insulator> out = new ArrayList<>();
        for (int g = 0; g < groups(); g++) {
            for (int k = 0; k < 3; k++) {
                Vec3 at = Vec3.atCenterOf(worldPosition).add((k - 1) * 0.2, (g - 0.5 * (groups() - 1)) * 0.25, 0);
                out.add(new Insulator(tier(), ABC[k], at, g, "bus" + g + "_" + ABC[k].id()));
            }
        }
        return out;
    }

    public List<Long> nodes(GridData.Dimension dimension, int group) {
        List<Long> out = new ArrayList<>(3);
        for (int k = 0; k < 3; k++) {
            Long id = dimension.nodeAt(new GridData.Anchor(worldPosition, group * 3 + k));
            if (id != null && id >= 0) {
                out.add(id);
            }
        }
        return out;
    }

    // The nodes facing a side, for a joint (none when it has no busway that way, or is damaged).
    public List<Long> sideNodes(GridData.Dimension dimension, Direction side) {
        int group = groupToward(side);
        return group < 0 || damaged() ? List.of() : nodes(dimension, group);
    }

    public boolean damaged() {
        return false;
    }

    // The busway piece at a position, or null.
    public static BusPieceBlockEntity at(Level level, BlockPos pos) {
        return level.isLoaded(pos) && level.getBlockEntity(pos) instanceof BusPieceBlockEntity piece && !piece.isRemoved() ? piece : null;
    }

    // Makes its joints match its shape: a joint to the piece on each side both have busway toward.
    public void rejoin(ServerLevel level) {
        GridData.Dimension dimension = GridData.of(level);
        Set<Long> mine = new HashSet<>();
        for (int g = 0; g < groups(); g++) {
            mine.addAll(nodes(dimension, g));
        }
        Set<String> want = new HashSet<>();
        Map<String, Integer> sizes = new java.util.HashMap<>();
        for (Direction side : Direction.values()) {
            int size = sizeToward(side);
            BusPieceBlockEntity other = size > 0 ? at(level, worldPosition.relative(side)) : null;
            if (other == null || other.tier() != tier() || other.sizeToward(side.getOpposite()) <= 0) {
                continue;
            }
            List<Long> a = sideNodes(dimension, side), b = other.sideNodes(dimension, side.getOpposite());
            for (int k = 0; k < Math.min(a.size(), b.size()); k++) {
                String key = a.get(k) + ":" + b.get(k);
                want.add(key);
                sizes.put(key, size);
            }
        }
        List<Long> remove = new ArrayList<>();
        Set<String> have = new HashSet<>();
        for (long node : mine) {
            for (GridEdge edge : dimension.graph().edgesOf(node)) {
                if (edge.kind() != GridEdge.Kind.BUS || mine.contains(edge.other(node))) {
                    continue;
                }
                String key = node + ":" + edge.other(node);
                if (want.contains(key)) {
                    have.add(key);
                } else {
                    remove.add(edge.id());
                }
            }
        }
        if (!remove.isEmpty()) {
            GridManager.disconnect(level, remove);
        }
        for (String key : want) {
            if (!have.contains(key)) {
                String[] ab = key.split(":");
                GridManager.connectCircuit(level, List.of(Long.parseLong(ab[0])), List.of(Long.parseLong(ab[1])), GridEdge.Kind.BUS, 1.0, sizes.get(key), 1.0,
                        null);
            }
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server) {
            rejoin(server);
        }
    }

    // A neighbour changed: its joints (and, for one that hosts, what's on it) follow.
    public void neighbourChanged(ServerLevel level) {
        rejoin(level);
    }

    // --- The grid's tick: busway is passive (a tap-off or reducer protects) ---

    @Override
    public void ports(ServerLevel level, GridData.Dimension dimension, List<GridManager.PortHandler> out) {}

    @Override
    public void couplings(ServerLevel level, GridData.Dimension dimension, List<Coupling> out) {}

    @Override
    public void protective(ServerLevel level, GridData.Dimension dimension, Map<Long, FlowSolver.Protective> out) {}

    public AABB getRenderBox() {
        return withWires(new AABB(worldPosition));
    }
}
