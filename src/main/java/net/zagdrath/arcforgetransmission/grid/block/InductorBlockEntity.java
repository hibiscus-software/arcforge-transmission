/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.block;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.Codec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.grid.FaultEvent;
import net.zagdrath.arcforgetransmission.grid.FlowSolver;
import net.zagdrath.arcforgetransmission.grid.GridFaults;
import net.zagdrath.arcforgetransmission.grid.Phase;
import net.zagdrath.arcforgetransmission.grid.Port;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.data.AttachGroups;
import net.zagdrath.arcforgetransmission.grid.world.GridConfig;
import net.zagdrath.arcforgetransmission.grid.world.GridData;
import net.zagdrath.arcforgetransmission.grid.world.GridManager;
import net.zagdrath.arcforgetransmission.grid.world.Insulator;

// An Inductor: one insulator, one conductor's end, mounted on a face of the block it serves. Every inductor on one block
// makes one port (a whole circuit: A, B and C, or a line and N); the block's FE goes in or out through it, at up to the
// tier's voltage times the current limit. Mode and limit are the port's: setting them on one inductor sets every
// inductor on that block.
//
// Foreign blocks (any mod's but this one) may feed or be fed only at LV. An MV, HV or EV inductor switched on against
// one is an overvoltage: the block is broken, destroyed or blown up (GridConfig.OVERVOLTAGE).
public class InductorBlockEntity extends GridEndpointBlockEntity implements GridManager.PortSource {
    private InductorMode mode = InductorMode.OFF;
    // The current limit in eighths of the tier's rating (1 to 8).
    private int limitEighths = 8;
    // How the last tick went, for the status line.
    private FlowSolver.PortStatus status = FlowSolver.PortStatus.UNWIRED;
    private double lastFlow;

    public InductorBlockEntity(BlockPos pos, BlockState state) {
        super(GridContent.INDUCTOR_ENTITY.get(), pos, state);
    }

    public VoltageTier tier() {
        return getBlockState().getBlock() instanceof InductorBlock inductor ? inductor.tier() : VoltageTier.LV;
    }

    public Direction facing() {
        BlockState state = getBlockState();
        return state.hasProperty(InductorBlock.FACING) ? state.getValue(InductorBlock.FACING) : Direction.UP;
    }

    // The block it's mounted on.
    public BlockPos mountPos() {
        return worldPosition.relative(facing().getOpposite());
    }

    // How far the insulator stands off the mounting face (px), its top the wire's attach point.
    public static double height(VoltageTier tier) {
        return AttachGroups.get("inductor_" + tier.id()).groups().get(0).terminals().get(0).pos()[1];
    }

    @Override
    public List<Insulator> insulators() {
        VoltageTier tier = tier();
        Direction facing = facing();
        double off = (height(tier) - 8.0) / 16.0;
        Vec3 at = Vec3.atCenterOf(worldPosition).add(facing.getStepX() * off, facing.getStepY() * off, facing.getStepZ() * off);
        return List.of(new Insulator(tier, Phase.A, at, 0));
    }

    public InductorMode mode() {
        return mode;
    }

    public int limitEighths() {
        return limitEighths;
    }

    // The current limit, A per conductor.
    public double limitAmps() {
        return GridConfig.SETTINGS.ratedAmps(tier()) * limitEighths / 8.0;
    }

    public FlowSolver.PortStatus status() {
        return status;
    }

    public double lastFlow() {
        return lastFlow;
    }

    void set(InductorMode mode, int limitEighths) {
        this.mode = mode;
        this.limitEighths = Math.max(1, Math.min(8, limitEighths));
        setChanged();
    }

    // Every loaded inductor on the same block as this one (this one included), in a fixed order.
    public List<InductorBlockEntity> siblings() {
        List<InductorBlockEntity> out = new ArrayList<>();
        Level level = getLevel();
        BlockPos mount = mountPos();
        if (level == null) {
            return List.of(this);
        }
        for (Direction direction : Direction.values()) {
            BlockPos at = mount.relative(direction);
            if (level.isLoaded(at) && level.getBlockEntity(at) instanceof InductorBlockEntity other && !other.isRemoved()
                    && other.mountPos().equals(mount)) {
                out.add(other);
            }
        }
        if (!out.contains(this)) {
            out.add(this);
        }
        out.sort((a, b) -> a.worldPosition.compareTo(b.worldPosition));
        return out;
    }

    // Sets the mode and limit of the whole port.
    public void setForPort(InductorMode mode, int limitEighths) {
        for (InductorBlockEntity inductor : siblings()) {
            inductor.set(mode, limitEighths);
        }
    }

    // --- The port ---------------------------------------------------------------------------------------------------

    @Override
    public void ports(ServerLevel level, GridData.Dimension dimension, List<GridManager.PortHandler> out) {
        List<InductorBlockEntity> siblings = siblings();
        // One port per block: made by its first inductor.
        if (siblings.get(0) != this) {
            return;
        }
        List<Long> nodes = new ArrayList<>();
        for (InductorBlockEntity inductor : siblings) {
            Long id = dimension.nodeAt(new GridData.Anchor(inductor.worldPosition, 0));
            if (id != null) {
                nodes.add(id);
            }
        }
        if (nodes.isEmpty()) {
            return;
        }
        BlockPos mount = mountPos();
        Direction side = facing();
        EnergyHandler handler = level.isLoaded(mount) ? level.getCapability(Capabilities.Energy.BLOCK, mount, side) : null;
        VoltageTier tier = tier();
        // Overvoltage once it's live: switched on, against a foreign block, with a wire on any of its conductors.
        boolean wired = nodes.stream().anyMatch(id -> dimension.graph().networkOf(id) != null);
        if (mode != InductorMode.OFF && wired && handler != null && tier != VoltageTier.LV && !isNative(level, mount)) {
            overvoltage(level, mount, nodes.get(0), tier);
            return;
        }
        out.add(new InductorPort(siblings, tier, nodes, handler, mode, limitAmps()));
    }

    // This addon's own blocks take any tier.
    private static boolean isNative(Level level, BlockPos pos) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).getNamespace().equals(ArcforgeTransmission.MODID);
    }

    private static void overvoltage(ServerLevel level, BlockPos pos, long node, VoltageTier tier) {
        GridConfig.Overvoltage action = GridConfig.OVERVOLTAGE.get();
        if (action == GridConfig.Overvoltage.NONE) {
            return;
        }
        GridFaults.raise(FaultEvent.node(node, FaultEvent.Cause.OVERVOLTAGE, tier.volts()));
        switch (action) {
            case BREAK -> level.destroyBlock(pos, true);
            case DESTROY -> level.destroyBlock(pos, false);
            case EXPLODE -> {
                level.destroyBlock(pos, false);
                level.explode(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 1.5F, Level.ExplosionInteraction.NONE);
            }
            case NONE -> {}
        }
    }

    // A block's inductors as a port: its FE handler in or out, at up to the tier's voltage times the limit.
    private static final class InductorPort implements GridManager.PortHandler {
        private final List<InductorBlockEntity> inductors;
        private final VoltageTier tier;
        private final List<Long> nodes;
        private final @Nullable EnergyHandler handler;
        private final InductorMode mode;
        private final double limitAmps;

        InductorPort(List<InductorBlockEntity> inductors, VoltageTier tier, List<Long> nodes, @Nullable EnergyHandler handler, InductorMode mode,
                double limitAmps) {
            this.inductors = inductors;
            this.tier = tier;
            this.nodes = nodes;
            this.handler = handler;
            this.mode = mode;
            this.limitAmps = limitAmps;
        }

        private int maxPower() {
            return (int) Math.min(Integer.MAX_VALUE, Math.floor(limitAmps * tier.volts()));
        }

        @Override
        public Port port(long id) {
            double offer = 0, demand = 0;
            if (handler != null) {
                try (Transaction tx = Transaction.openRoot()) {
                    if (mode == InductorMode.INPUT) {
                        offer = handler.extract(maxPower(), tx);
                    } else if (mode == InductorMode.OUTPUT) {
                        demand = handler.insert(maxPower(), tx);
                    }
                    // Not committed: only asking.
                }
            }
            return new Port(id, tier, nodes, offer, demand, limitAmps);
        }

        @Override
        public int draw(int fe) {
            if (handler == null || mode != InductorMode.INPUT) {
                return 0;
            }
            try (Transaction tx = Transaction.openRoot()) {
                int got = handler.extract(fe, tx);
                tx.commit();
                return got;
            }
        }

        @Override
        public int deliver(int fe) {
            if (handler == null || mode != InductorMode.OUTPUT) {
                return 0;
            }
            try (Transaction tx = Transaction.openRoot()) {
                int got = handler.insert(fe, tx);
                tx.commit();
                return got;
            }
        }

        @Override
        public void report(FlowSolver.PortStatus status, double drawn, double delivered) {
            for (InductorBlockEntity inductor : inductors) {
                inductor.status = status;
                inductor.lastFlow = drawn > 0 ? drawn : delivered;
            }
        }
    }

    // --- Saving and drawing ------------------------------------------------------------------------------------------

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store("mode", InductorMode.CODEC, mode);
        output.store("limit_eighths", Codec.INT, limitEighths);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        mode = input.read("mode", InductorMode.CODEC).orElse(InductorMode.OFF);
        limitEighths = Math.max(1, Math.min(8, input.read("limit_eighths", Codec.INT).orElse(8)));
    }

    public AABB getRenderBox() {
        return withWires(new AABB(worldPosition));
    }
}
