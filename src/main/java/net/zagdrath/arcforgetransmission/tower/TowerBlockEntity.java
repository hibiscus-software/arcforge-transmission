/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.tower;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.Codec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.grid.FlowSolver;
import net.zagdrath.arcforgetransmission.grid.Port;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.block.GridEndpointBlockEntity;
import net.zagdrath.arcforgetransmission.grid.block.PinInsulatorMode;
import net.zagdrath.arcforgetransmission.grid.block.PortHud;
import net.zagdrath.arcforgetransmission.grid.data.AttachGroups;
import net.zagdrath.arcforgetransmission.grid.world.GridConfig;
import net.zagdrath.arcforgetransmission.grid.world.GridData;
import net.zagdrath.arcforgetransmission.grid.world.GridManager;
import net.zagdrath.arcforgetransmission.grid.world.Insulator;
import net.zagdrath.arcforgetransmission.tower.model.TowerMesh;

// A placed structure's main block entity (a tower, a substation or a unit transformer): what TowerRenderer draws it from,
// what clears its parts away when it goes, and its insulators for the grid (each its own wire point, from its attach
// groups, at the group's tier or its own). The tier is its block, the facing its block state, the labels GridData's.
//
// The transformers are ports as well (GridManager.PortSource), never making energy:
//  - a Substation Transformer joins its primary (A B C at its tier) to its secondary (A B C a tier down) through an FE
//    buffer, taking in on one side what fits and giving out on the other what it holds: stepping down (primary in) or up
//    (secondary in), as the Wrench on Configure sets;
//  - a Unit Transformer joins its A B C to the FE block in front of it (its cable box's side): Input takes that block's
//    FE onto the line (a generator's step-up), Output feeds the line's FE into it, at any tier (it's the transformer down
//    to the machine's own voltage, so no overvoltage).
public class TowerBlockEntity extends GridEndpointBlockEntity implements GridManager.PortSource, PortHud {
    private boolean removing;
    // The box the tower is drawn in, and the facing it was worked out for (a transformer can turn).
    private @Nullable AABB renderBox;
    private @Nullable Direction renderFacing;
    // Transformers: a substation's buffer and which way it steps; a unit's mode.
    private int buffer;
    private boolean stepUp;
    private PinInsulatorMode mode = PinInsulatorMode.OFF;
    // How the last tick went (the in side's status, FE/t through), and what the client was last told.
    private FlowSolver.PortStatus status = FlowSolver.PortStatus.UNWIRED;
    private double lastFlow;
    private FlowSolver.@Nullable PortStatus sentStatus;
    private boolean sentStepUp;
    private @Nullable PinInsulatorMode sentMode;
    private long sentFlow = -1;

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
        List<Insulator> out = new ArrayList<>();
        List<AttachGroups.Group> groups = AttachGroups.get(tier.id()).groups();
        for (int g = 0; g < groups.size(); g++) {
            AttachGroups.Group group = groups.get(g);
            VoltageTier voltage = group.tier() != null ? group.tier() : tier.voltage();
            for (AttachGroups.Terminal terminal : group.terminals()) {
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
        if (renderBox == null || renderFacing != facing()) {
            renderFacing = facing();
            renderBox = TowerPlacement.renderBox(tier(), renderFacing, worldPosition);
        }
        return withWires(renderBox);
    }

    // --- Transformers ---------------------------------------------------------------------------------------------

    public boolean stepUp() {
        return stepUp;
    }

    public PinInsulatorMode mode() {
        return mode;
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
    public @Nullable Component hudMode() {
        String m = ArcforgeTransmission.MODID;
        TowerTier tier = tier();
        return switch (tier.kind()) {
            case TOWER -> null;
            case SUBSTATION -> Component.translatable("hud." + m + ".substation." + (stepUp ? "step_up" : "step_down"), tier.secondary().name(),
                    tier.voltage().name());
            case UNIT -> Component.translatable("pin_insulator_mode." + m + "." + mode.id());
        };
    }

    // The Wrench on Configure: a substation's direction, a unit's mode. What it's set to now, or null for a tower.
    public @Nullable Component configure() {
        TowerTier tier = tier();
        if (tier.kind() == TowerTier.Kind.SUBSTATION) {
            stepUp = !stepUp;
            buffer = 0;
        } else if (tier.kind() == TowerTier.Kind.UNIT) {
            mode = mode.next();
        } else {
            return null;
        }
        setChanged();
        if (level instanceof ServerLevel server) {
            GridManager.display(server, this, true);
        }
        return hudMode();
    }

    // The Wrench on Rotate: a transformer turns a quarter turn about its main block (clockwise seen from above, or
    // counter-clockwise), if every block it would newly take is free. Its wires stay on and re-hang to the bushings' new
    // places; a unit transformer then serves the block its cable box faces now. False if there's no room (or it's a tower).
    public boolean rotate(boolean clockwise) {
        TowerTier tier = tier();
        if (!(level instanceof ServerLevel server) || !tier.isTransformer()) {
            return false;
        }
        Direction from = facing(), to = clockwise ? from.getClockWise() : from.getCounterClockWise();
        BlockPos centre = TowerPlacement.centre(tier, from, worldPosition);
        if (!centre.equals(TowerPlacement.centre(tier, to, worldPosition))) {
            return false;
        }
        List<BlockPos> old = TowerPlacement.positions(tier, from, centre), next = TowerPlacement.positions(tier, to, centre);
        java.util.Set<BlockPos> mine = new java.util.HashSet<>(old);
        for (BlockPos pos : next) {
            if (!mine.contains(pos) && (!level.getBlockState(pos).canBeReplaced() || !level.getWorldBorder().isWithinBounds(pos)
                    || level.isOutsideBuildHeight(pos))) {
                return false;
            }
        }
        // Its own parts come and go without taking it down (TowerPartBlockEntity checks isRemoving).
        removing = true;
        try {
            for (BlockPos pos : old) {
                if (!pos.equals(worldPosition) && !next.contains(pos) && level.getBlockState(pos).getBlock() instanceof TowerPartBlock
                        && worldPosition.equals(TowerPartBlock.mainOf(level, pos))) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
            level.setBlock(worldPosition, getBlockState().setValue(TowerBlock.FACING, to), Block.UPDATE_ALL);
            BlockState part = TowerContent.TOWER_PART.get().defaultBlockState().setValue(TowerPartBlock.STEEL, tier.steel());
            for (BlockPos pos : next) {
                if (pos.equals(worldPosition)) {
                    continue;
                }
                if (level.getBlockState(pos).getBlock() instanceof TowerPartBlock && worldPosition.equals(TowerPartBlock.mainOf(level, pos))) {
                    // Kept: its shape follows the new facing.
                    level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), Block.UPDATE_ALL);
                    continue;
                }
                level.setBlock(pos, part, Block.UPDATE_ALL | Block.UPDATE_KNOWN_SHAPE);
                if (level.getBlockEntity(pos) instanceof TowerPartBlockEntity bounding) {
                    bounding.setMain(worldPosition);
                }
            }
        } finally {
            removing = false;
        }
        renderBox = null;
        buffer = 0;
        setChanged();
        GridManager.moved(server, worldPosition, this);
        return true;
    }

    // The FE block a unit transformer serves: the one its cable box faces.
    public BlockPos servedPos() {
        return worldPosition.relative(facing());
    }

    @Override
    public void ports(ServerLevel level, GridData.Dimension dimension, List<GridManager.PortHandler> out) {
        TowerTier tier = tier();
        if (!tier.isTransformer()) {
            return;
        }
        List<Insulator> insulators = insulators();
        List<Long> primary = new ArrayList<>(), secondary = new ArrayList<>();
        for (int i = 0; i < insulators.size(); i++) {
            Long id = dimension.nodeAt(new GridData.Anchor(worldPosition, i));
            if (id == null) {
                return;
            }
            (insulators.get(i).group() == 0 ? primary : secondary).add(id);
        }
        lastFlow = 0;
        if (tier.kind() == TowerTier.Kind.UNIT) {
            BlockPos served = servedPos();
            EnergyHandler handler = level.isLoaded(served) ? level.getCapability(Capabilities.Energy.BLOCK, served, facing().getOpposite()) : null;
            out.add(new UnitSide(primary, tier.voltage(), handler));
            return;
        }
        int capacity = capacity();
        buffer = Math.min(buffer, capacity);
        List<Long> in = stepUp ? secondary : primary, outSide = stepUp ? primary : secondary;
        VoltageTier inTier = stepUp ? tier.secondary() : tier.voltage(), outTier = stepUp ? tier.voltage() : tier.secondary();
        out.add(new BufferSide(in, inTier, 0, capacity - buffer, true));
        out.add(new BufferSide(outSide, outTier, buffer, 0, false));
    }

    // A substation's buffer: two ticks of its high side's rated power, so a full line passes.
    private int capacity() {
        TowerTier tier = tier();
        return (int) Math.min(Integer.MAX_VALUE, 2.0 * GridConfig.SETTINGS.ratedAmps(tier.voltage()) * tier.voltage().volts());
    }

    // One side of a substation: the in side takes what fits in the buffer, the out side gives what it holds.
    private final class BufferSide implements GridManager.PortHandler {
        private final List<Long> nodes;
        private final VoltageTier tier;
        private final int offer, demand;
        private final boolean in;

        BufferSide(List<Long> nodes, VoltageTier tier, int offer, int demand, boolean in) {
            this.nodes = nodes;
            this.tier = tier;
            this.offer = offer;
            this.demand = demand;
            this.in = in;
        }

        @Override
        public Port port(long id) {
            return new Port(id, tier, nodes, offer, demand, Double.POSITIVE_INFINITY);
        }

        @Override
        public int draw(int fe) {
            int got = Math.max(0, Math.min(Math.min(fe, offer), buffer));
            buffer -= got;
            setChanged();
            return got;
        }

        @Override
        public int deliver(int fe) {
            int got = Math.max(0, Math.min(fe, capacity() - buffer));
            buffer += got;
            setChanged();
            return got;
        }

        @Override
        public void report(FlowSolver.PortStatus status, double drawn, double delivered) {
            if (in) {
                TowerBlockEntity.this.status = status;
                lastFlow = delivered;
            }
        }
    }

    // A unit transformer's line side: the served block's FE in (Input) or out (Output), up to the tier's rated power.
    private final class UnitSide implements GridManager.PortHandler {
        private final List<Long> nodes;
        private final VoltageTier tier;
        private final @Nullable EnergyHandler handler;

        UnitSide(List<Long> nodes, VoltageTier tier, @Nullable EnergyHandler handler) {
            this.nodes = nodes;
            this.tier = tier;
            this.handler = handler;
        }

        private int maxPower() {
            return (int) Math.min(Integer.MAX_VALUE, Math.floor(GridConfig.SETTINGS.ratedAmps(tier) * tier.volts()));
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
            return new Port(id, tier, nodes, offer, demand, Double.POSITIVE_INFINITY);
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
            TowerBlockEntity.this.status = status;
            lastFlow = drawn > 0 ? drawn : delivered;
        }
    }

    // --- The HUD, saving, removal ---------------------------------------------------------------------------------

    @Override
    protected boolean hudStateChanged() {
        return status != sentStatus || stepUp != sentStepUp || mode != sentMode;
    }

    @Override
    protected boolean hudLiveChanged() {
        return Math.round(lastFlow) != sentFlow;
    }

    @Override
    protected void hudSent() {
        sentStatus = status;
        sentStepUp = stepUp;
        sentMode = mode;
        sentFlow = Math.round(lastFlow);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.putString("hud_status", status.name());
        tag.putLong("hud_flow", Math.round(lastFlow));
        return tag;
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (tier().isTransformer()) {
            output.store("buffer", Codec.INT, buffer);
            output.store("step_up", Codec.BOOL, stepUp);
            output.store("mode", PinInsulatorMode.CODEC, mode);
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        buffer = Math.max(0, input.read("buffer", Codec.INT).orElse(0));
        stepUp = input.read("step_up", Codec.BOOL).orElse(false);
        mode = input.read("mode", PinInsulatorMode.CODEC).orElse(PinInsulatorMode.OFF);
        input.read("hud_status", Codec.STRING).ifPresent(name -> status = FlowSolver.PortStatus.valueOf(name));
        input.read("hud_flow", Codec.LONG).ifPresent(flow -> lastFlow = flow);
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
