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
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
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
import net.zagdrath.arcforgetransmission.grid.GridNode;
import net.zagdrath.arcforgetransmission.grid.Phase;
import net.zagdrath.arcforgetransmission.grid.Port;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.data.AttachGroups;
import net.zagdrath.arcforgetransmission.grid.world.GridConfig;
import net.zagdrath.arcforgetransmission.grid.world.GridData;
import net.zagdrath.arcforgetransmission.grid.world.GridManager;
import net.zagdrath.arcforgetransmission.grid.world.Insulator;

// A Pin Insulator: one insulator, one conductor's end, mounted on a face of the block it serves. Every pin insulator on one block
// makes one port (a whole circuit: A, B and C, or a line and N); the block's FE goes in or out through it, at up to the
// tier's voltage times the current limit. Mode and limit are the port's: setting them on one pin insulator sets every
// pin insulator on that block.
//
// Foreign blocks (any mod's but this one) may feed or be fed only at LV. An MV, HV or EV pin insulator switched on against
// one is an overvoltage: the block is broken, destroyed or blown up (GridConfig.OVERVOLTAGE).
public class PinInsulatorBlockEntity extends GridEndpointBlockEntity implements GridManager.PortSource {
    private PinInsulatorMode mode = PinInsulatorMode.OFF;
    // The current limit in eighths of the tier's rating (1 to 8).
    private int limitEighths = 8;
    // How the last tick went, for the status line.
    private FlowSolver.PortStatus status = FlowSolver.PortStatus.UNWIRED;
    private double lastFlow;
    // Just placed (nothing loaded from a save): takes its port's settings from the pin insulators already there.
    private boolean fresh = true;
    // Its phase label as the client knows it (the grid's record is GridData's), for the HUD (PinInsulatorHud).
    private Phase phase = Phase.A;
    // What the client was last sent (server side), and when, so the HUD's live numbers go out at most twice a second.
    private @Nullable Phase sentPhase;
    private FlowSolver.@Nullable PortStatus sentStatus;
    private long sentFlow = -1, sentAt;

    public PinInsulatorBlockEntity(BlockPos pos, BlockState state) {
        super(GridContent.PIN_INSULATOR_ENTITY.get(), pos, state);
    }

    public VoltageTier tier() {
        return getBlockState().getBlock() instanceof PinInsulatorBlock pinInsulator ? pinInsulator.tier() : VoltageTier.LV;
    }

    public Direction facing() {
        BlockState state = getBlockState();
        return state.hasProperty(PinInsulatorBlock.FACING) ? state.getValue(PinInsulatorBlock.FACING) : Direction.UP;
    }

    // The block it's mounted on.
    public BlockPos mountPos() {
        return worldPosition.relative(facing().getOpposite());
    }

    // How far the insulator stands off the mounting face (px), its top the wire's attach point.
    public static double height(VoltageTier tier) {
        return AttachGroups.get("pin_insulator_" + tier.id()).groups().get(0).terminals().get(0).pos()[1];
    }

    @Override
    public List<Insulator> insulators() {
        VoltageTier tier = tier();
        Direction facing = facing();
        double off = (height(tier) - 8.0) / 16.0;
        Vec3 at = Vec3.atCenterOf(worldPosition).add(facing.getStepX() * off, facing.getStepY() * off, facing.getStepZ() * off);
        return List.of(new Insulator(tier, Phase.A, at, 0));
    }

    public PinInsulatorMode mode() {
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

    // Its phase label (on the client, as last sent).
    public Phase phase() {
        return phase;
    }

    void set(PinInsulatorMode mode, int limitEighths) {
        this.mode = mode;
        this.limitEighths = Math.max(1, Math.min(8, limitEighths));
        setChanged();
        sync();
    }

    // Server: reads its label from the grid and tells the client what the HUD shows when any of it has changed (the flow
    // at most twice a second, unless forced).
    public void refreshDisplay(boolean force) {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        GridNode node = GridManager.node(server, new GridData.Anchor(worldPosition, 0));
        if (node != null) {
            phase = node.phase();
        }
        long flow = Math.round(lastFlow);
        boolean changed = phase != sentPhase || status != sentStatus;
        boolean flowChanged = flow != sentFlow && server.getGameTime() - sentAt >= 10;
        if (force || changed || flowChanged) {
            sync();
        }
    }

    private void sync() {
        if (level != null && !level.isClientSide()) {
            sentPhase = phase;
            sentStatus = status;
            sentFlow = Math.round(lastFlow);
            sentAt = level.getGameTime();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    // Every loaded pin insulator on the same block as this one (this one included), in a fixed order.
    public List<PinInsulatorBlockEntity> siblings() {
        List<PinInsulatorBlockEntity> out = new ArrayList<>();
        Level level = getLevel();
        BlockPos mount = mountPos();
        if (level == null) {
            return List.of(this);
        }
        for (Direction direction : Direction.values()) {
            BlockPos at = mount.relative(direction);
            if (level.isLoaded(at) && level.getBlockEntity(at) instanceof PinInsulatorBlockEntity other && !other.isRemoved()
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
    public void setForPort(PinInsulatorMode mode, int limitEighths) {
        for (PinInsulatorBlockEntity pinInsulator : siblings()) {
            pinInsulator.set(mode, limitEighths);
        }
    }

    @Override
    public void onLoad() {
        if (fresh && level != null && !level.isClientSide()) {
            for (PinInsulatorBlockEntity other : siblings()) {
                if (other != this) {
                    set(other.mode, other.limitEighths);
                    break;
                }
            }
        }
        fresh = false;
        super.onLoad();
    }

    // --- The port ---------------------------------------------------------------------------------------------------

    @Override
    public void ports(ServerLevel level, GridData.Dimension dimension, List<GridManager.PortHandler> out) {
        List<PinInsulatorBlockEntity> siblings = siblings();
        // One port per block: made by its first pin insulator.
        if (siblings.get(0) != this) {
            return;
        }
        List<Long> nodes = new ArrayList<>();
        for (PinInsulatorBlockEntity pinInsulator : siblings) {
            Long id = dimension.nodeAt(new GridData.Anchor(pinInsulator.worldPosition, 0));
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
        if (mode != PinInsulatorMode.OFF && wired && handler != null && tier != VoltageTier.LV && !isNative(level, mount)
                && GridConfig.OVERVOLTAGE.get() != GridConfig.Overvoltage.NONE) {
            overvoltage(level, mount, nodes.get(0), tier);
            return;
        }
        out.add(new PinInsulatorPort(siblings, tier, nodes, handler, mode, limitAmps()));
    }

    // This addon's own blocks take any tier.
    private static boolean isNative(Level level, BlockPos pos) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).getNamespace().equals(ArcforgeTransmission.MODID);
    }

    private static void overvoltage(ServerLevel level, BlockPos pos, long node, VoltageTier tier) {
        GridConfig.Overvoltage action = GridConfig.OVERVOLTAGE.get();
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

    // A block's pin insulators as a port: its FE handler in or out, at up to the tier's voltage times the limit.
    private static final class PinInsulatorPort implements GridManager.PortHandler {
        private final List<PinInsulatorBlockEntity> pinInsulators;
        private final VoltageTier tier;
        private final List<Long> nodes;
        private final @Nullable EnergyHandler handler;
        private final PinInsulatorMode mode;
        private final double limitAmps;

        PinInsulatorPort(List<PinInsulatorBlockEntity> pinInsulators, VoltageTier tier, List<Long> nodes, @Nullable EnergyHandler handler, PinInsulatorMode mode,
                double limitAmps) {
            this.pinInsulators = pinInsulators;
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
                    if (mode == PinInsulatorMode.INPUT) {
                        offer = handler.extract(maxPower(), tx);
                    } else if (mode == PinInsulatorMode.OUTPUT) {
                        demand = handler.insert(maxPower(), tx);
                    }
                    // Not committed: only asking.
                }
            }
            return new Port(id, tier, nodes, offer, demand, limitAmps);
        }

        @Override
        public int draw(int fe) {
            if (handler == null || mode != PinInsulatorMode.INPUT) {
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
            if (handler == null || mode != PinInsulatorMode.OUTPUT) {
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
            for (PinInsulatorBlockEntity pinInsulator : pinInsulators) {
                pinInsulator.status = status;
                pinInsulator.lastFlow = drawn > 0 ? drawn : delivered;
                pinInsulator.refreshDisplay(false);
            }
        }
    }

    // --- Saving and drawing ------------------------------------------------------------------------------------------

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store("mode", PinInsulatorMode.CODEC, mode);
        output.store("limit_eighths", Codec.INT, limitEighths);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        fresh = false;
        mode = input.read("mode", PinInsulatorMode.CODEC).orElse(PinInsulatorMode.OFF);
        limitEighths = Math.max(1, Math.min(8, input.read("limit_eighths", Codec.INT).orElse(8)));
        // The HUD's numbers, sent to the client only (getUpdateTag).
        input.read("hud_phase", Codec.STRING).ifPresent(id -> phase = Phase.byId(id));
        input.read("hud_status", Codec.STRING).ifPresent(name -> status = FlowSolver.PortStatus.valueOf(name));
        input.read("hud_flow", Codec.LONG).ifPresent(flow -> lastFlow = flow);
    }

    // What the client gets: the saved settings, and the HUD's label, status and flow.
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.putString("hud_phase", phase.id());
        tag.putString("hud_status", status.name());
        tag.putLong("hud_flow", Math.round(lastFlow));
        return tag;
    }

    public AABB getRenderBox() {
        return withWires(new AABB(worldPosition));
    }
}
