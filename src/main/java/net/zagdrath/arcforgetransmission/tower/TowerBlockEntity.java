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
    private int renderEighths = -1;
    // Transformers: a substation's buffer and which way it steps; a unit's mode.
    private int buffer;
    private boolean stepUp;
    private PinInsulatorMode mode = PinInsulatorMode.OFF;
    // Protection devices: closed (a fuse intact), locked out after its last reclose, the trips since it last held, when
    // it recloses (game time, 0 for never), the ticks it's been over its setting, when it last closed, and its trip
    // setting in eighths of the tier's rating. A service meter's count of FE delivered.
    private boolean closed = true, lockout;
    private int shots, overTicks, tripEighths = 8;
    private long recloseAt, closedAt;
    private long delivered;
    // Its guy wires' ground anchors (blocks).
    private List<BlockPos> guys = new ArrayList<>();
    private boolean sentClosed = true, sentLockout;
    private int sentTrip = 8;
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

    // Its orientation, eighths of a turn clockwise from north.
    public int eighths() {
        return TowerPlacement.eighths(getBlockState());
    }

    // Its insulators and link pairs, worked out once per orientation (they're asked for every tick and every frame).
    private @Nullable List<Insulator> insulatorCache;
    private @Nullable List<int[]> pairCache;
    private int cacheEighths = -1;

    // Every insulator, group by group, turned with the tower.
    @Override
    public List<Insulator> insulators() {
        int eighths = eighths();
        if (insulatorCache == null || cacheEighths != eighths) {
            insulatorCache = List.copyOf(buildInsulators(eighths));
            pairCache = null;
            cacheEighths = eighths;
        }
        return insulatorCache;
    }

    private List<Insulator> buildInsulators(int eighths) {
        TowerTier tier = tier();
        BlockPos centre = TowerPlacement.centre(tier, eighths, worldPosition);
        List<Insulator> out = new ArrayList<>();
        List<AttachGroups.Group> groups = AttachGroups.get(tier.id()).groups();
        for (int g = 0; g < groups.size(); g++) {
            AttachGroups.Group group = groups.get(g);
            VoltageTier voltage = group.tier() != null ? group.tier() : tier.voltage();
            for (AttachGroups.Terminal terminal : group.terminals()) {
                double[] p = terminal.pos();
                double[] t = TowerMesh.turnedPoint8((float) p[0], (float) p[1], (float) p[2], eighths);
                out.add(new Insulator(voltage, terminal.phase(), new Vec3(centre.getX() + t[0], centre.getY() + t[1], centre.getZ() + t[2]), g));
            }
        }
        return out;
    }

    public boolean isRemoving() {
        return removing;
    }

    // The tower's box, and its wires' (and guys').
    public AABB getRenderBox() {
        if (renderBox == null || renderEighths != eighths()) {
            renderEighths = eighths();
            renderBox = TowerPlacement.renderBox(tier(), renderEighths, worldPosition);
        }
        AABB box = withWires(renderBox);
        for (BlockPos guy : guys) {
            box = box.minmax(new AABB(guy));
        }
        return box;
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
            case TOWER, DEAD_END -> null;
            case SUBSTATION -> Component.translatable("hud." + m + ".substation." + (stepUp ? "step_up" : "step_down"), tier.secondary().name(),
                    tier.voltage().name());
            case UNIT -> Component.translatable("pin_insulator_mode." + m + "." + mode.id());
            case FUSE -> closed ? Component.translatable("hud." + m + ".fuse.intact", amps(fuseAmps())) : Component.translatable("hud." + m + ".fuse.blown");
            case BREAKER -> lockout ? Component.translatable("hud." + m + ".breaker.lockout")
                    : closed ? Component.translatable("hud." + m + ".breaker.closed", amps(tripAmps()), tripEighths)
                    : Component.translatable("hud." + m + ".breaker." + (recloseAt > 0 ? "reclosing" : "open"));
            case DISCONNECT -> Component.translatable("hud." + m + ".switch." + (closed ? "closed" : "open"));
            case SERVICE -> Component.translatable("hud." + m + ".service.delivered", String.format(java.util.Locale.ROOT, "%,d", delivered));
        };
    }

    // The HUD shows a status and FE/t only for what moves FE itself.
    @Override
    public boolean hudFlow() {
        return tier().isTransformer() || tier().kind() == TowerTier.Kind.SERVICE;
    }

    private static String amps(double amps) {
        return amps == Math.rint(amps) ? Long.toString((long) amps) : String.format(java.util.Locale.ROOT, "%.1f", amps);
    }

    // --- Protection devices ---------------------------------------------------------------------------------------

    public boolean closed() {
        return closed;
    }

    public boolean lockedOut() {
        return lockout;
    }

    // A fuse's rating (its tier's wire rating times the config's factor) and a breaker's trip setting, A per conductor.
    public double fuseAmps() {
        return GridConfig.SETTINGS.ratedAmps(tier().voltage()) * GridConfig.FUSE_FACTOR.getAsDouble();
    }

    public double tripAmps() {
        return GridConfig.SETTINGS.ratedAmps(tier().voltage()) * tripEighths / 8.0;
    }

    // The insulator pairs it joins through itself (by index in insulators()): group 0 with group 1, group 2 with 3, each
    // conductor with the one in the same place.
    public List<int[]> linkPairs() {
        List<Insulator> insulators = insulators();
        if (pairCache != null) {
            return pairCache;
        }
        java.util.Map<Integer, List<Integer>> byGroup = new java.util.TreeMap<>();
        for (int i = 0; i < insulators.size(); i++) {
            byGroup.computeIfAbsent(insulators.get(i).group(), g -> new ArrayList<>()).add(i);
        }
        List<int[]> pairs = new ArrayList<>();
        for (int g = 0; byGroup.containsKey(g + 1); g += 2) {
            List<Integer> a = byGroup.get(g), b = byGroup.get(g + 1);
            for (int k = 0; k < Math.min(a.size(), b.size()); k++) {
                pairs.add(new int[] { a.get(k), b.get(k) });
            }
        }
        pairCache = List.copyOf(pairs);
        return pairCache;
    }

    // The most current through any of its links last tick, A.
    public double linkAmps(ServerLevel level) {
        GridData.Dimension dimension = GridData.of(level);
        java.util.Map<Long, Double> last = GridManager.lastAmps(level);
        double most = 0;
        for (int[] pair : linkPairs()) {
            Long a = dimension.nodeAt(new GridData.Anchor(worldPosition, pair[0]));
            if (a == null) {
                continue;
            }
            for (var edge : dimension.graph().edgesOf(a)) {
                if (edge.isJumper()) {
                    most = Math.max(most, last.getOrDefault(edge.id(), 0.0));
                }
            }
        }
        return most;
    }

    private void relink(ServerLevel level) {
        if (GridManager.link(level, worldPosition, linkPairs(), closed || tier().kind() == TowerTier.Kind.DEAD_END)) {
            GridManager.display(level, this, true);
        }
    }

    // Each tick (from ports()): its links match its state, a fuse blows or a breaker trips on overcurrent, and a breaker
    // recloses when its delay is up.
    private void deviceTick(ServerLevel level) {
        relink(level);
        TowerTier tier = tier();
        long now = level.getGameTime();
        if (tier.kind() == TowerTier.Kind.FUSE && closed && linkAmps(level) > fuseAmps() + 1.0E-9) {
            open(level, "fuse.blew");
        } else if (tier.kind() == TowerTier.Kind.BREAKER) {
            if (closed) {
                overTicks = linkAmps(level) > tripAmps() + 1.0E-9 ? overTicks + 1 : 0;
                if (overTicks >= GridConfig.BREAKER_TRIP_TICKS.getAsInt()) {
                    trip(level);
                } else if (shots > 0 && now - closedAt > 600) {
                    shots = 0;
                }
            } else if (!lockout && recloseAt > 0 && now >= recloseAt) {
                recloseAt = 0;
                close(level);
                level.playSound(null, worldPosition, net.minecraft.sounds.SoundEvents.IRON_DOOR_CLOSE, net.minecraft.sounds.SoundSource.BLOCKS, 0.8F, 0.7F);
            }
        }
    }

    // A breaker trips: opens, and recloses after the delay unless that was its last shot (locked out).
    private void trip(ServerLevel level) {
        shots++;
        overTicks = 0;
        lockout = shots > GridConfig.RECLOSE_SHOTS.getAsInt();
        recloseAt = lockout ? 0 : level.getGameTime() + GridConfig.RECLOSE_DELAY.getAsInt();
        open(level, lockout ? "breaker.locked_out" : "breaker.tripped");
    }

    private void open(ServerLevel level, String event) {
        closed = false;
        setChanged();
        relink(level);
        level.playSound(null, worldPosition, net.minecraft.sounds.SoundEvents.LIGHTNING_BOLT_IMPACT, net.minecraft.sounds.SoundSource.BLOCKS, 0.3F, 2.0F);
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK, worldPosition.getX() + 0.5, worldPosition.getY() + 1.0,
                worldPosition.getZ() + 0.5, 16, 0.4, 0.4, 0.4, 0.2);
        GridManager.display(level, this, true);
    }

    private void close(ServerLevel level) {
        closed = true;
        overTicks = 0;
        closedAt = level.getGameTime();
        setChanged();
        relink(level);
        GridManager.display(level, this, true);
    }

    // A lightning surge on its line: a breaker trips (and recloses), a fuse blows. False if it was already open.
    public boolean surge(ServerLevel level) {
        if (!closed) {
            return false;
        }
        if (tier().kind() == TowerTier.Kind.BREAKER) {
            trip(level);
            return true;
        }
        if (tier().kind() == TowerTier.Kind.FUSE) {
            open(level, "fuse.blew");
            return true;
        }
        return false;
    }

    // Empty-handed use: a breaker or switch opens or closes by hand (closing a breaker clears its lockout). What it is
    // now, or null if it isn't worked by hand.
    public @Nullable Component operate(ServerLevel level) {
        TowerTier.Kind kind = tier().kind();
        if (kind != TowerTier.Kind.BREAKER && kind != TowerTier.Kind.DISCONNECT) {
            return null;
        }
        if (closed) {
            recloseAt = 0;
            open(level, "opened");
        } else {
            lockout = false;
            shots = 0;
            recloseAt = 0;
            close(level);
        }
        level.playSound(null, worldPosition, closed ? net.minecraft.sounds.SoundEvents.IRON_DOOR_CLOSE : net.minecraft.sounds.SoundEvents.IRON_DOOR_OPEN,
                net.minecraft.sounds.SoundSource.BLOCKS, 0.8F, 0.8F);
        return hudMode();
    }

    // A Fuse Link put into a blown cutout. False if it isn't one.
    public boolean refuse(ServerLevel level) {
        if (tier().kind() != TowerTier.Kind.FUSE || closed) {
            return false;
        }
        close(level);
        return true;
    }

    // What a comparator reads off its main block: a device's load (0 open), a transformer's or meter's throughput.
    public int comparator() {
        TowerTier tier = tier();
        double rated = GridConfig.SETTINGS.ratedAmps(tier.voltage());
        if (tier.isDevice()) {
            return !closed || !(level instanceof ServerLevel server) ? 0 : 1 + (int) Math.round(14 * Math.min(1.0, linkAmps(server) / rated));
        }
        if (tier.isTransformer() || tier.kind() == TowerTier.Kind.SERVICE) {
            return (int) Math.round(15 * Math.min(1.0, lastFlow / (rated * tier.voltage().volts())));
        }
        return 0;
    }

    // The draw for rendering: an open switch or breaker, a blown fuse.
    public boolean showsAlt() {
        return tier().isDevice() && !closed;
    }

    // The Wrench on Configure: a substation's direction, a unit's mode, a breaker's trip setting (an eighth of its rating
    // a step). What it's set to now, or null when there's nothing to set.
    public @Nullable Component configure() {
        TowerTier tier = tier();
        if (tier.kind() == TowerTier.Kind.SUBSTATION) {
            stepUp = !stepUp;
            buffer = 0;
        } else if (tier.kind() == TowerTier.Kind.UNIT) {
            mode = mode.next();
        } else if (tier.kind() == TowerTier.Kind.BREAKER) {
            tripEighths = tripEighths % 8 + 1;
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
        // An eighth of a turn a click (a quarter for what serves the block in front of it).
        int step = tier.servesBlock() ? 2 : 1, from = eighths(), to = Math.floorMod(from + (clockwise ? step : -step), 8);
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
            level.setBlock(worldPosition, TowerPlacement.oriented(getBlockState(), to), Block.UPDATE_ALL);
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

    // --- Guy wires --------------------------------------------------------------------------------------------------

    public List<BlockPos> guys() {
        return guys;
    }

    // Where its guys come off: near the top of its centre column.
    public net.minecraft.world.phys.Vec3 guyPoint() {
        BlockPos centre = TowerPlacement.centre(tier(), eighths(), worldPosition);
        return new net.minecraft.world.phys.Vec3(centre.getX() + 0.5, centre.getY() + tier().height() * 0.85, centre.getZ() + 0.5);
    }

    // A guy to an anchor block within reach (its height and a half, and lower than the guy point). False if not.
    public boolean addGuy(BlockPos anchor) {
        net.minecraft.world.phys.Vec3 top = guyPoint(), to = net.minecraft.world.phys.Vec3.atCenterOf(anchor).add(0, 0.5, 0);
        if (guys.size() >= 8 || to.y >= top.y || top.distanceTo(to) > tier().height() * 1.5 + 2 || guys.contains(anchor)) {
            return false;
        }
        guys.add(anchor.immutable());
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
        return true;
    }

    public boolean removeGuy() {
        if (guys.isEmpty()) {
            return false;
        }
        guys.remove(guys.size() - 1);
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
        return true;
    }

    // The FE block a unit transformer serves: the one its cable box faces.
    public BlockPos servedPos() {
        return worldPosition.relative(facing());
    }

    @Override
    public void ports(ServerLevel level, GridData.Dimension dimension, List<GridManager.PortHandler> out) {
        TowerTier tier = tier();
        if (tier.linked()) {
            deviceTick(level);
            return;
        }
        if (tier.kind() == TowerTier.Kind.SERVICE) {
            servicePorts(level, dimension, out);
            return;
        }
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

    // A service meter: the building's block fed from L1 + N and L2 + N (its spools, in that order L1 N L2).
    private void servicePorts(ServerLevel level, GridData.Dimension dimension, List<GridManager.PortHandler> out) {
        Long l1 = dimension.nodeAt(new GridData.Anchor(worldPosition, 0)), n = dimension.nodeAt(new GridData.Anchor(worldPosition, 1)),
                l2 = dimension.nodeAt(new GridData.Anchor(worldPosition, 2));
        if (l1 == null || n == null || l2 == null) {
            return;
        }
        BlockPos served = servedPos();
        EnergyHandler handler = level.isLoaded(served) ? level.getCapability(Capabilities.Energy.BLOCK, served, facing().getOpposite()) : null;
        lastFlow = 0;
        out.add(new ServiceSide(List.of(l1, n), handler));
        out.add(new ServiceSide(List.of(l2, n), handler));
    }

    // One leg of the service (L1 or L2 with the neutral) into the building's block.
    private final class ServiceSide implements GridManager.PortHandler {
        private final List<Long> nodes;
        private final @Nullable EnergyHandler handler;

        ServiceSide(List<Long> nodes, @Nullable EnergyHandler handler) {
            this.nodes = nodes;
            this.handler = handler;
        }

        @Override
        public Port port(long id) {
            double demand = 0;
            if (handler != null) {
                int most = (int) Math.floor(GridConfig.SETTINGS.ratedAmps(VoltageTier.LV) * VoltageTier.LV.volts() / 2.0);
                try (Transaction tx = Transaction.openRoot()) {
                    demand = handler.insert(most, tx);
                }
            }
            return new Port(id, VoltageTier.LV, nodes, 0, demand, Double.POSITIVE_INFINITY);
        }

        @Override
        public int draw(int fe) {
            return 0;
        }

        @Override
        public int deliver(int fe) {
            if (handler == null) {
                return 0;
            }
            try (Transaction tx = Transaction.openRoot()) {
                int got = handler.insert(fe, tx);
                tx.commit();
                delivered += got;
                setChanged();
                return got;
            }
        }

        @Override
        public void report(FlowSolver.PortStatus status, double drawn, double deliveredNow) {
            TowerBlockEntity.this.status = status;
            lastFlow += deliveredNow;
        }
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

    // A breaker or switch keeps its links in step with it from the start; a dead-end's are always there.
    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server && tier().linked()) {
            relink(server);
        }
    }

    // --- The HUD, saving, removal ---------------------------------------------------------------------------------

    @Override
    protected boolean hudStateChanged() {
        return status != sentStatus || stepUp != sentStepUp || mode != sentMode || closed != sentClosed || lockout != sentLockout
                || tripEighths != sentTrip;
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
        sentClosed = closed;
        sentLockout = lockout;
        sentTrip = tripEighths;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.putString("hud_status", status.name());
        tag.putLong("hud_flow", Math.round(lastFlow));
        tag.putLong("hud_reclose", recloseAt);
        return tag;
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store("guys", BlockPos.CODEC.listOf(), guys);
        if (tier().kind() != TowerTier.Kind.TOWER) {
            output.store("buffer", Codec.INT, buffer);
            output.store("step_up", Codec.BOOL, stepUp);
            output.store("mode", PinInsulatorMode.CODEC, mode);
            output.store("closed", Codec.BOOL, closed);
            output.store("lockout", Codec.BOOL, lockout);
            output.store("shots", Codec.INT, shots);
            output.store("trip_eighths", Codec.INT, tripEighths);
            output.store("delivered", Codec.LONG, delivered);
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        guys = new ArrayList<>(input.read("guys", BlockPos.CODEC.listOf()).orElse(List.of()));
        buffer = Math.max(0, input.read("buffer", Codec.INT).orElse(0));
        stepUp = input.read("step_up", Codec.BOOL).orElse(false);
        mode = input.read("mode", PinInsulatorMode.CODEC).orElse(PinInsulatorMode.OFF);
        closed = input.read("closed", Codec.BOOL).orElse(true);
        lockout = input.read("lockout", Codec.BOOL).orElse(false);
        shots = input.read("shots", Codec.INT).orElse(0);
        tripEighths = Math.max(1, Math.min(8, input.read("trip_eighths", Codec.INT).orElse(8)));
        delivered = input.read("delivered", Codec.LONG).orElse(0L);
        input.read("hud_reclose", Codec.LONG).ifPresent(at -> recloseAt = at);
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
        for (BlockPos guy : guys) {
            Block.popResource(level, pos, new net.minecraft.world.item.ItemStack(net.zagdrath.arcforgetransmission.grid.block.GridContent.GUY_WIRE.get()));
        }
        int eighths = TowerPlacement.eighths(state);
        for (BlockPos part : TowerPlacement.positions(tower.tier(), eighths, TowerPlacement.centre(tower.tier(), eighths, pos))) {
            if (!part.equals(pos) && level.getBlockState(part).getBlock() instanceof TowerPartBlock
                    && pos.equals(TowerPartBlock.mainOf(level, part))) {
                level.setBlock(part, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }
}
