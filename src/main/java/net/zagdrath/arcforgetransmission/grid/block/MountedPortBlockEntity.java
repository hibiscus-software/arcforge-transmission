/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.block;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.Codec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.grid.FlowSolver;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.world.GridConfig;
import net.zagdrath.arcforgetransmission.grid.world.GridManager;

// Something with conductors mounted on a face of the block it serves, making a port of it (MountedPort): a Pin Insulator
// (with the others on its block) or a Cable Termination. Its mode (off, input, output, storage) and current limit (eighths
// of its tier's rating) are set with the Wrench on Configure; it keeps how its port's last tick went for its HUD, and
// sends that to the client as it changes.
public abstract class MountedPortBlockEntity extends GridEndpointBlockEntity implements GridManager.PortSource, PortHud, PortSettings, MountedPort.Holder {
    protected PinInsulatorMode mode = PinInsulatorMode.OFF;
    // The current limit in eighths of the tier's rating (1 to 8).
    protected int limitEighths = 8;
    // How the last tick went, for the status line.
    protected FlowSolver.PortStatus status = FlowSolver.PortStatus.UNWIRED;
    protected double lastFlow;
    // A mapped plant of another tier it refuses (its HUD says which and where it connects).
    protected PortHud.@Nullable Generator generator;
    // The mapped machine it feeds, or one of another tier it refuses (its HUD and comparator).
    protected PortHud.@Nullable Load load;
    // The block it's mounted on, its energy capability cached.
    protected final MountedPort.Mount mount = new MountedPort.Mount();
    // The HUD's port state last sent to the client (server side).
    private FlowSolver.@Nullable PortStatus sentStatus;
    private @Nullable PinInsulatorMode sentMode;
    private int sentLimit = -1;
    private long sentFlow = -1;
    private PortHud.@Nullable Generator sentGenerator;
    private PortHud.@Nullable Load sentLoad;

    protected MountedPortBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public abstract VoltageTier tier();

    // The way it points, away from the block it's mounted on.
    public abstract Direction facing();

    // The block it's mounted on.
    public BlockPos mountPos() {
        return worldPosition.relative(facing().getOpposite());
    }

    @Override
    public PinInsulatorMode mode() {
        return mode;
    }

    @Override
    public int limitEighths() {
        return limitEighths;
    }

    @Override
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

    @Override
    public PortHud.@Nullable Generator hudGenerator() {
        return generator;
    }

    @Override
    public PortHud.@Nullable Load hudLoad() {
        return load;
    }

    // A comparator's reading: the machine it feeds, its draw as a share of its rating.
    public int loadSignal() {
        return load == null ? 0 : load.signal();
    }

    // Its port's mode and current limit.
    @Override
    public @Nullable Component hudMode() {
        String m = ArcforgeTransmission.MODID;
        double amps = limitAmps();
        String limit = amps == Math.rint(amps) ? Long.toString((long) amps) : String.format(java.util.Locale.ROOT, "%.1f", amps);
        return Component.translatable("hud." + m + ".pin_insulator.mode", Component.translatable("pin_insulator_mode." + m + "." + mode.id()), limit,
                limitEighths);
    }

    // Sets its own mode and limit (setForPort sets the whole port's).
    protected void set(PinInsulatorMode mode, int limitEighths) {
        this.mode = mode;
        this.limitEighths = Math.max(1, Math.min(8, limitEighths));
        setChanged();
        if (level instanceof ServerLevel server) {
            GridManager.display(server, this, true);
        }
    }

    @Override
    public void setForPort(PinInsulatorMode mode, int limitEighths) {
        set(mode, limitEighths);
    }

    // How its port's tick went (MountedPort tells everything mounted as one port).
    @Override
    public void reported(FlowSolver.PortStatus status, PortHud.@Nullable Generator generator, PortHud.@Nullable Load load, double flow) {
        int signal = loadSignal();
        this.status = status;
        this.generator = generator;
        this.load = load;
        this.lastFlow = flow;
        if (signal != loadSignal() && level != null) {
            level.updateNeighbourForOutputSignal(worldPosition, getBlockState().getBlock());
        }
    }

    @Override
    protected boolean hudStateChanged() {
        return status != sentStatus || mode != sentMode || limitEighths != sentLimit;
    }

    @Override
    protected boolean hudLiveChanged() {
        return Math.round(lastFlow) != sentFlow || !java.util.Objects.equals(generator, sentGenerator) || !java.util.Objects.equals(load, sentLoad);
    }

    @Override
    protected void hudSent() {
        sentStatus = status;
        sentMode = mode;
        sentLimit = limitEighths;
        sentFlow = Math.round(lastFlow);
        sentGenerator = generator;
        sentLoad = load;
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store("mode", PinInsulatorMode.CODEC, mode);
        output.store("limit_eighths", Codec.INT, limitEighths);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        mode = input.read("mode", PinInsulatorMode.CODEC).orElse(PinInsulatorMode.OFF);
        limitEighths = Math.max(1, Math.min(8, input.read("limit_eighths", Codec.INT).orElse(8)));
        // The HUD's numbers, sent to the client only (getUpdateTag).
        input.read("hud_status", Codec.STRING).ifPresent(name -> status = FlowSolver.PortStatus.valueOf(name));
        input.read("hud_flow", Codec.LONG).ifPresent(flow -> lastFlow = flow);
        generator = PortHud.Generator.read(input, generator);
        load = PortHud.Load.read(input, load);
    }

    // What the client gets: the saved settings, the HUD's numbers, and its port's status and flow.
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.putString("hud_status", status.name());
        tag.putLong("hud_flow", Math.round(lastFlow));
        PortHud.Generator.write(tag, generator);
        PortHud.Load.write(tag, load);
        return tag;
    }
}
