/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.factory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.grid.Phase;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.block.GridInteractions;
import net.zagdrath.arcforgetransmission.grid.block.MountedPort;
import net.zagdrath.arcforgetransmission.grid.block.MountedPortBlockEntity;
import net.zagdrath.arcforgetransmission.grid.block.WholeCircuit;
import net.zagdrath.arcforgetransmission.grid.world.GridData;
import net.zagdrath.arcforgetransmission.grid.world.GridManager;
import net.zagdrath.arcforgetransmission.grid.world.Insulator;
import net.zagdrath.arcforgetransmission.tower.model.FactoryMesh;

// A Cable Termination: one whole circuit's three conductors (A, B and C; at LV, L1, N and L2 if set so) where a tray
// cable ends. On a block that takes FE it is that block's port, just as the block's Pin Insulators would be (MountedPort:
// the same mode, current limit and status, the same overvoltage rule: above LV, a foreign block is destroyed); on a block
// of this addon's that takes terminations (a lineup section, a busway tap-off: TerminationHost), that block joins its
// conductors through instead. Its labels are fixed: the Wrench on Rotate swaps B and C, and on Port an LV termination's
// switch between three-phase and split-phase while no cable is on it.
public class CableTerminationBlockEntity extends MountedPortBlockEntity implements WholeCircuit, Hosted {
    private static final Phase[] THREE = { Phase.A, Phase.B, Phase.C }, SPLIT = { Phase.L1, Phase.N, Phase.L2 };

    public CableTerminationBlockEntity(BlockPos pos, BlockState state) {
        super(FactoryContent.TERMINATION_ENTITY.get(), pos, state);
    }

    @Override
    public VoltageTier tier() {
        return getBlockState().getBlock() instanceof CableTerminationBlock termination ? termination.tier() : VoltageTier.LV;
    }

    @Override
    public Direction facing() {
        BlockState state = getBlockState();
        return state.hasProperty(CableTerminationBlock.FACING) ? state.getValue(CableTerminationBlock.FACING) : Direction.UP;
    }

    public FactoryMesh.Mount mountKind() {
        return CableTerminationBlock.mount(facing());
    }

    // Its three conductors, A B C west to east (turned with it): each at its terminal, the top of its outlet (the bottom,
    // hanging).
    @Override
    public List<Insulator> insulators() {
        VoltageTier tier = tier();
        Direction facing = facing();
        FactoryMesh.Mount mount = CableTerminationBlock.mount(facing);
        List<float[]> boxes = FactoryMesh.termination(tier.ordinal(), mount).insulators();
        int turns = CableTerminationBlock.turns(facing);
        List<Insulator> out = new ArrayList<>(3);
        for (int i = 0; i < 3 && i < boxes.size(); i++) {
            float[] b = boxes.get(i);
            float y = mount == FactoryMesh.Mount.BOTTOM ? b[1] : b[4];
            float[] p = FactoryShapes.turnedPoint((b[0] + b[3]) / 2, y, (b[2] + b[5]) / 2, turns);
            Vec3 at = new Vec3(worldPosition.getX() + p[0] / 16.0, worldPosition.getY() + p[1] / 16.0, worldPosition.getZ() + p[2] / 16.0);
            out.add(new Insulator(tier, THREE[i], at, 0, "termination_" + THREE[i].id()));
        }
        return out;
    }

    // Where a cable leaves it: its gland, at the middle of its foot (its top, hanging).
    public Vec3 cableEnd() {
        Vec3 centre = Vec3.atBottomCenterOf(worldPosition);
        return mountKind() == FactoryMesh.Mount.BOTTOM ? centre.add(0, 1, 0) : centre;
    }

    // Its conductors' nodes, in A B C order.
    @Override
    public List<Long> nodes(GridData.Dimension dimension) {
        List<Long> nodes = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            Long id = dimension.nodeAt(new GridData.Anchor(worldPosition, i));
            if (id != null && id >= 0) {
                nodes.add(id);
            }
        }
        return nodes;
    }

    // Whether what it's mounted on joins its conductors through (a lineup section, a busway tap-off).
    public boolean onHost() {
        return level != null && level.getBlockEntity(mountPos()) instanceof TerminationHost;
    }

    @Override
    public void ports(ServerLevel level, GridData.Dimension dimension, List<GridManager.PortHandler> out) {
        if (onHost()) {
            return;
        }
        MountedPort.make(level, dimension, List.of(this), tier(), nodes(dimension), mountPos(), facing(),
                mount, mode, limitAmps(), out);
    }

    // On a host, its HUD says so rather than giving a port's mode.
    @Override
    public Component hudMode() {
        if (onHost()) {
            return Component.translatable("hud." + ArcforgeTransmission.MODID + ".cable_termination.host");
        }
        return super.hudMode();
    }

    // An LV termination between three-phase (A B C) and split-phase (L1 N L2), while no cable is on it.
    @Override
    public Component switchCircuit(ServerLevel level) {
        String m = ArcforgeTransmission.MODID;
        if (tier() != VoltageTier.LV) {
            return Component.translatable("message." + m + ".cable_termination.three_phase_only");
        }
        for (int i = 0; i < 3; i++) {
            if (GridManager.isWired(level, new GridData.Anchor(worldPosition, i))) {
                return Component.translatable("message." + m + ".phase.unwire_first");
            }
        }
        var first = GridManager.node(level, new GridData.Anchor(worldPosition, 0));
        Phase[] to = first != null && first.phase().circuit() == SPLIT[0].circuit() ? THREE : SPLIT;
        for (int i = 0; i < 3; i++) {
            GridManager.setPhase(level, new GridData.Anchor(worldPosition, i), to[i]);
        }
        GridManager.display(level, this, true);
        return Component.translatable("message." + m + ".phase.labels", GridInteractions.labels(level, this, 0));
    }

    // What an empty hand on it says: its labels, mode and limit, and how its port is doing.
    public Component statusLine(ServerLevel level) {
        String m = ArcforgeTransmission.MODID;
        return Component.translatable("message." + m + ".pin_insulator.status", GridInteractions.labels(level, this, 0),
                Component.translatable("pin_insulator_mode." + m + "." + mode.id()), GridInteractions.format(limitAmps()),
                Component.translatable("port_status." + m + "." + status.name().toLowerCase(Locale.ROOT)), GridInteractions.format(Math.round(lastFlow)));
    }

    public AABB getRenderBox() {
        int h = FactoryMesh.terminationHeight(tier().ordinal());
        AABB own = new AABB(worldPosition);
        return withWires(facing() == Direction.DOWN ? own.expandTowards(0, -(h - 1), 0) : own.expandTowards(0, h - 1, 0));
    }
}
