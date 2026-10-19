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
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
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
import net.zagdrath.arcforgetransmission.grid.world.VoltageRatings;

// A Pin Insulator: one insulator, one conductor's end, mounted on a face of the block it serves. Every pin insulator on one block
// makes one port (a whole circuit: A, B and C, or a line and N); the block's FE goes in or out through it, at up to the
// tier's voltage times the current limit. Mode and limit are the port's: setting them on one pin insulator sets every
// pin insulator on that block.
//
// Foreign blocks (any mod's but this one) may feed or be fed only at LV. An MV, HV or EHV pin insulator switched on against
// one is an overvoltage: the block is broken, destroyed or blown up (GridConfig.OVERVOLTAGE).
public class PinInsulatorBlockEntity extends GridEndpointBlockEntity implements GridManager.PortSource, PortHud {
    private PinInsulatorMode mode = PinInsulatorMode.OFF;
    // The current limit in eighths of the tier's rating (1 to 8).
    private int limitEighths = 8;
    // How the last tick went, for the status line.
    private FlowSolver.PortStatus status = FlowSolver.PortStatus.UNWIRED;
    private double lastFlow;
    // Just placed (nothing loaded from a save): takes its port's settings from the pin insulators already there.
    private boolean fresh = true;
    // The HUD's port state last sent to the client (server side).
    private FlowSolver.@Nullable PortStatus sentStatus;
    private @Nullable PinInsulatorMode sentMode;
    private int sentLimit = -1;
    private long sentFlow = -1;

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
        return List.of(new Insulator(tier, Phase.A, at, 0, "pin"));
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

    @Override
    public FlowSolver.PortStatus status() {
        return status;
    }

    @Override
    public double lastFlow() {
        return lastFlow;
    }

    // Its port's mode and current limit (on a Wall Bushing, that it passes wires through).
    @Override
    public Component hudMode() {
        String m = ArcforgeTransmission.MODID;
        if (onWallBushing()) {
            return Component.translatable("hud." + m + ".pin_insulator.bushing");
        }
        double amps = limitAmps();
        String limit = amps == Math.rint(amps) ? Long.toString((long) amps) : String.format(java.util.Locale.ROOT, "%.1f", amps);
        return Component.translatable("hud." + m + ".pin_insulator.mode", Component.translatable("pin_insulator_mode." + m + "." + mode.id()), limit,
                limitEighths);
    }

    // Its phase label (on the client, as last sent).
    public Phase phase() {
        Phase phase = hudPhase(0);
        return phase != null ? phase : Phase.A;
    }

    void set(PinInsulatorMode mode, int limitEighths) {
        this.mode = mode;
        this.limitEighths = Math.max(1, Math.min(8, limitEighths));
        setChanged();
        if (level instanceof ServerLevel server) {
            GridManager.display(server, this, true);
        }
    }

    @Override
    protected boolean hudStateChanged() {
        return status != sentStatus || mode != sentMode || limitEighths != sentLimit;
    }

    @Override
    protected boolean hudLiveChanged() {
        return Math.round(lastFlow) != sentFlow;
    }

    @Override
    protected void hudSent() {
        sentStatus = status;
        sentMode = mode;
        sentLimit = limitEighths;
        sentFlow = Math.round(lastFlow);
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
        if (level instanceof ServerLevel server) {
            GridManager.rejoin(server, mountPos());
        }
    }

    // Off a Wall Bushing, the ones left on it are joined again without it.
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (level instanceof ServerLevel server) {
            GridManager.rejoin(server, mountPos());
        }
    }

    // On a Wall Bushing it only passes wires through (GridManager.rejoin): no FE of its own.
    public boolean onWallBushing() {
        return level != null && level.getBlockState(mountPos()).getBlock() instanceof WallBushingBlock;
    }

    // --- The port ---------------------------------------------------------------------------------------------------

    @Override
    public void ports(ServerLevel level, GridData.Dimension dimension, List<GridManager.PortHandler> out) {
        List<PinInsulatorBlockEntity> siblings = siblings();
        // One port per block: made by its first pin insulator. None on a Wall Bushing.
        if (siblings.get(0) != this || onWallBushing()) {
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
        if (mountCache == null || !mountCache.pos().equals(mount) || mountCache.context() != side) {
            mountCache = BlockCapabilityCache.create(Capabilities.Energy.BLOCK, level, mount, side);
        }
        EnergyHandler handler = level.isLoaded(mount) ? mountCache.getCapability() : null;
        VoltageTier tier = tier();
        // Overvoltage once it's live: switched on, against a foreign block, with a wire on any of its conductors.
        boolean wired = nodes.stream().anyMatch(id -> dimension.graph().networkOf(id) != null);
        if (mode != PinInsulatorMode.OFF && wired && handler != null && !VoltageRatings.accepts(level, mount, tier)
                && GridConfig.OVERVOLTAGE.get() != GridConfig.Overvoltage.NONE) {
            overvoltage(level, mount, nodes.get(0), tier);
            return;
        }
        out.add(new PinInsulatorPort(siblings, tier, nodes, handler, mode, limitAmps()));
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

    // The mounted block's energy capability, cached (rebuilt if the insulator turns).
    private @Nullable BlockCapabilityCache<EnergyHandler, @Nullable Direction> mountCache;

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
                    if (mode.gives()) {
                        offer = handler.extract(maxPower(), tx);
                    }
                }
                try (Transaction tx = Transaction.openRoot()) {
                    if (mode.takes()) {
                        demand = handler.insert(maxPower(), tx);
                    }
                    // Not committed: only asking.
                }
            }
            return new Port(id, tier, nodes, offer, demand, limitAmps, mode == PinInsulatorMode.STORAGE, 1.0);
        }

        @Override
        public int draw(int fe, TransactionContext tx) {
            return handler == null || !mode.gives() ? 0 : handler.extract(fe, tx);
        }

        @Override
        public int deliver(int fe, TransactionContext tx) {
            return handler == null || !mode.takes() ? 0 : handler.insert(fe, tx);
        }

        @Override
        public void report(FlowSolver.PortStatus status, double drawn, double delivered) {
            // No FE block where it's mounted: says so rather than looking wired up and idle.
            FlowSolver.PortStatus shown = handler == null && mode != PinInsulatorMode.OFF && status != FlowSolver.PortStatus.UNWIRED
                    ? FlowSolver.PortStatus.NO_FE_BLOCK : status;
            for (PinInsulatorBlockEntity pinInsulator : pinInsulators) {
                pinInsulator.status = shown;
                pinInsulator.lastFlow = drawn > 0 ? drawn : delivered;
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
        input.read("hud_status", Codec.STRING).ifPresent(name -> status = FlowSolver.PortStatus.valueOf(name));
        input.read("hud_flow", Codec.LONG).ifPresent(flow -> lastFlow = flow);
    }

    // What the client gets: the saved settings, the insulator's HUD numbers, and its port's status and flow.
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.putString("hud_status", status.name());
        tag.putLong("hud_flow", Math.round(lastFlow));
        return tag;
    }

    public AABB getRenderBox() {
        return withWires(new AABB(worldPosition));
    }
}
