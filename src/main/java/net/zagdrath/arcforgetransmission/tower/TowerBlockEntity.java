/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.tower;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.Codec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.grid.Coupling;
import net.zagdrath.arcforgetransmission.grid.FaultEvent;
import net.zagdrath.arcforgetransmission.grid.FlowSolver;
import net.zagdrath.arcforgetransmission.grid.GridEdge;
import net.zagdrath.arcforgetransmission.grid.GridFaults;
import net.zagdrath.arcforgetransmission.grid.Port;
import net.zagdrath.arcforgetransmission.grid.Shunt;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.block.GridEndpointBlockEntity;
import net.zagdrath.arcforgetransmission.grid.block.PinInsulatorMode;
import net.zagdrath.arcforgetransmission.grid.block.PortHud;
import net.zagdrath.arcforgetransmission.grid.data.AttachGroups;
import net.zagdrath.arcforgetransmission.grid.protection.Differential;
import net.zagdrath.arcforgetransmission.grid.protection.FuseElement;
import net.zagdrath.arcforgetransmission.grid.protection.Interlocks;
import net.zagdrath.arcforgetransmission.grid.protection.Overcurrent;
import net.zagdrath.arcforgetransmission.grid.protection.Reclosing;
import net.zagdrath.arcforgetransmission.grid.protection.Sectionalizer;
import net.zagdrath.arcforgetransmission.grid.protection.TapControl;
import net.zagdrath.arcforgetransmission.grid.protection.TransferSwitch;
import net.zagdrath.arcforgetransmission.grid.protection.Unbalance;
import net.zagdrath.arcforgetransmission.grid.protection.UnbalanceWatch;
import net.zagdrath.arcforgetransmission.grid.world.GridConfig;
import net.zagdrath.arcforgetransmission.grid.world.GridData;
import net.zagdrath.arcforgetransmission.grid.world.GridManager;
import net.zagdrath.arcforgetransmission.grid.world.Insulator;
import net.zagdrath.arcforgetransmission.tower.model.TowerMesh;

// A placed structure's main block entity (a tower, a transformer, a device): what TowerRenderer draws it from, what clears
// its parts away when it goes, and its insulators for the grid (each its own wire point, from its attach groups, at the
// group's tier or its own). The tier is its block, the facing its block state, the labels GridData's.
//
// What each takes part in each grid tick (GridManager.PortSource):
//  - a Substation Transformer is an electrical link (a Coupling) from its primary (its tier) to its secondary (a tier
//    down), either way, holding nothing; its load tap changer (on unless turned off with the Wrench on Configure) holds its
//    secondary's voltage;
//  - a Unit Transformer joins its A B C to the FE block in front of it: Input, Output, or Storage (a battery: covering
//    what the grid's sources can't, taking what they have spare);
//  - the protection devices join each line-side conductor to its load-side one while closed: a fuse melts on its curve,
//    a recloser or breaker trips on its (graded, so the one nearest the trouble goes first; a Relay Panel's inverse curve
//    and instantaneous element where it has a CT), recloses and locks out; a sectionalizer counts the recloser's
//    operations and opens in the dead time; a disconnect switch opens by hand (interlocked with its breaker);
//  - a voltage regulator joins its source side to its load side through its boost, holding the load side's voltage;
//  - a ground switch grounds its line while closed (only closed on a dead line); a capacitor bank or reactor is a shunt
//    while switched in (by hand, by redstone, or by its Relay Panel on the voltage);
//  - an automatic transfer switch joins its load to its preferred feeder, or its alternate when the preferred is dead;
//  - a bus section joins its phases to the sections in line with it.
public class TowerBlockEntity extends GridEndpointBlockEntity implements GridManager.PortSource, PortHud {
    // A relay's time dials (its inverse curve's multiplier), stepped with the Wrench on Configure.
    private static final double[] TIME_DIALS = { 0.05, 0.1, 0.2, 0.3, 0.5 };

    private boolean removing;
    // The box the tower is drawn in, and the facing it was worked out for (a transformer can turn).
    private @Nullable AABB renderBox;
    private int renderEighths = -1;
    // A unit transformer's mode.
    private PinInsulatorMode mode = PinInsulatorMode.OFF;
    // Switching: closed (a fuse intact, a breaker or switch closed, a ground switch grounding, a shunt switched in), a
    // breaker's reclosing (its shots, lockout and when it recloses), and its trip setting in eighths of the tier's rating.
    // A service meter's count of FE delivered.
    private boolean closed;
    private int tripEighths = 8;
    private final Reclosing reclosing = new Reclosing();
    private long delivered;
    // Protection and control: the fuse's element, the overcurrent timer, the sectionalizer's count, the transfer switch's
    // position, the tap changer's or regulator's tap (and whether it runs itself), CT and PT fitted, a relay panel's or
    // disconnect's links (Control Cable), the panel's time dial and fuse-saving, and its watch for unbalance or a lost phase.
    private final FuseElement fuse = new FuseElement();
    private final Overcurrent overcurrent = new Overcurrent();
    private final Sectionalizer sectionalizer = new Sectionalizer();
    private final TransferSwitch transfer = new TransferSwitch();
    private final TapControl tap = new TapControl();
    private boolean auto = true, ct, pt, fuseSaving = true, lastPowered;
    private List<BlockPos> controls = new ArrayList<>();
    private int dial = 1;
    private final UnbalanceWatch unbalance = new UnbalanceWatch();
    // What its insulators watch (per unit), for the HUD: a regulator's or tap changer's load side, a shunt's bus.
    private double watchedVolts;
    // Its guy wires' ground anchors (blocks).
    private List<BlockPos> guys = new ArrayList<>();
    private boolean sentClosed, sentLockout, sentCt, sentPt, sentAuto, sentFuseSaving;
    private int sentTrip = 8, sentTap, sentDial = -1, sentCount;
    private TransferSwitch.@Nullable Position sentPosition;
    // How the last tick went (the in side's status, FE/t through), and what the client was last told.
    private FlowSolver.PortStatus status = FlowSolver.PortStatus.UNWIRED;
    private double lastFlow;
    private FlowSolver.@Nullable PortStatus sentStatus;
    private @Nullable PinInsulatorMode sentMode;
    private long sentFlow = -1, sentWatched = -1;
    // A unit transformer's push buffer: what generators push into it (on Input, up to a tick's rated power), put onto the
    // line with what its front face's blocks give; the energy capability its blocks show (taking nothing on Output or
    // Storage, giving nothing); and its front face's FE blocks, by capability cache (built per orientation).
    private final SimpleEnergyHandler pushed;
    // What a comparator read last tick.
    private int lastComparator;
    private final EnergyHandler intake = new EnergyHandler() {
        @Override
        public long getAmountAsLong() {
            return pushed.getAmountAsLong();
        }

        @Override
        public long getCapacityAsLong() {
            return pushed.getCapacityAsLong();
        }

        @Override
        public int insert(int amount, TransactionContext transaction) {
            return mode == PinInsulatorMode.INPUT ? pushed.insert(amount, transaction) : 0;
        }

        @Override
        public int extract(int amount, TransactionContext transaction) {
            return 0;
        }
    };
    private @Nullable List<BlockCapabilityCache<EnergyHandler, @Nullable Direction>> face;
    private @Nullable BlockCapabilityCache<EnergyHandler, @Nullable Direction> servedCache;
    private int faceEighths = -1;

    public TowerBlockEntity(BlockPos pos, BlockState state) {
        super(TowerContent.TOWER_ENTITY.get(), pos, state);
        // A ground switch starts open (not grounding), a shunt switched out; everything else closed.
        TowerTier tier = state.getBlock() instanceof TowerBlock tower ? tower.tier() : TowerTier.LV;
        closed = tier.kind() != TowerTier.Kind.GROUND && tier.kind() != TowerTier.Kind.SHUNT;
        sentClosed = closed;
        int power = tier.kind() == TowerTier.Kind.UNIT ? unitPower(tier.voltage()) : 0;
        pushed = new SimpleEnergyHandler(power, power, power);
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
                out.add(new Insulator(voltage, terminal.phase(), new Vec3(centre.getX() + t[0], centre.getY() + t[1], centre.getZ() + t[2]), g,
                        group.name() + "/" + terminal.label()));
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

    // --- The HUD ----------------------------------------------------------------------------------------------------

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
        String m = "hud." + ArcforgeTransmission.MODID + ".";
        TowerTier tier = tier();
        Component fitted = fittings();
        Component main = switch (tier.kind()) {
            case TOWER, DEAD_END -> null;
            case SUBSTATION -> Component.translatable(m + "substation.tap", tier.voltage().name(), tier.secondary().name(), tapText(),
                    Component.translatable(m + (auto ? "auto" : "manual")));
            case UNIT -> Component.translatable("pin_insulator_mode." + ArcforgeTransmission.MODID + "." + mode.id());
            case FUSE -> closed ? Component.translatable(m + "fuse.intact", amps(fuseAmps())) : Component.translatable(m + "fuse.blown");
            case BREAKER -> reclosing.lockedOut() ? Component.translatable(m + "breaker.lockout")
                    : closed ? Component.translatable(m + "breaker.closed", amps(tripAmps()), tripEighths)
                    : Component.translatable(m + "breaker." + (reclosing.recloseAt() > 0 ? "reclosing" : "open"));
            case DISCONNECT -> Component.translatable(m + "switch." + (closed ? "closed" : "open"));
            case SERVICE -> Component.translatable(m + "service.delivered", String.format(java.util.Locale.ROOT, "%,d", delivered));
            case BUS -> Component.translatable(m + "bus");
            case REGULATOR -> Component.translatable(m + "regulator", tapText(), volts(watchedVolts), Component.translatable(m + (auto ? "auto" : "manual")));
            case RELAY -> Component.translatable(m + "relay", controls.size(), String.format(java.util.Locale.ROOT, "%.2f", TIME_DIALS[dial]),
                    Component.translatable(m + (fuseSaving ? "fuse_saving" : "fuse_blowing")));
            case GROUND -> Component.translatable(m + "ground." + (closed ? "closed" : "open"));
            case SHUNT -> Component.translatable(m + (tier.reactor() ? "reactor." : "capacitor.") + (closed ? "in" : "out"), volts(watchedVolts));
            case SECTIONALIZER -> closed ? Component.translatable(m + "sectionalizer.closed", sectionalizer.counted(), GridConfig.sectionalizerCount())
                    : Component.translatable(m + "sectionalizer.open");
            case ATS -> Component.translatable(m + "ats." + (transfer.position() == TransferSwitch.Position.PREFERRED ? "preferred" : "alternate"),
                    Component.translatable(m + (auto ? "auto" : "manual")));
        };
        if (main == null || fitted == null) {
            return main;
        }
        return main.copy().append(" ").append(fitted);
    }

    // CT and PT, where fitted.
    private @Nullable Component fittings() {
        if (!ct && !pt) {
            return null;
        }
        String m = "hud." + ArcforgeTransmission.MODID + ".";
        return Component.translatable(m + (ct && pt ? "fitted.both" : ct ? "fitted.ct" : "fitted.pt"));
    }

    private String tapText() {
        int p = tap.position();
        return (p > 0 ? "+" : "") + p + " (" + String.format(java.util.Locale.ROOT, "%+.1f%%", tap.boost(GridConfig.tapStep()) * 100) + ")";
    }

    private static String volts(double perUnit) {
        return String.format(java.util.Locale.ROOT, "%.1f%%", perUnit * 100);
    }

    // The HUD shows a status and FE/t only for what moves FE itself.
    @Override
    public boolean hudFlow() {
        return tier().isTransformer() || tier().kind() == TowerTier.Kind.SERVICE;
    }

    private static String amps(double amps) {
        return amps == Math.rint(amps) ? Long.toString((long) amps) : String.format(java.util.Locale.ROOT, "%.1f", amps);
    }

    // --- Protection devices -----------------------------------------------------------------------------------------

    public boolean closed() {
        return closed;
    }

    public boolean lockedOut() {
        return reclosing.lockedOut();
    }

    // A fuse's rating (its tier's wire rating times the config's factor) and a breaker's trip setting, A per conductor.
    // A breaker's trips since it last held closed (its reclose shots).
    public int tripsSinceHeld() {
        return reclosing.shots();
    }

    public double fuseAmps() {
        return GridConfig.SETTINGS.ratedAmps(tier().voltage()) * GridConfig.fuseFactor();
    }

    public double tripAmps() {
        return GridConfig.SETTINGS.ratedAmps(tier().voltage()) * tripEighths / 8.0;
    }

    // The insulator pairs it joins through itself (by index in insulators()): group 0 with group 1, group 2 with 3, each
    // conductor with the one in the same place.
    public List<int[]> linkPairs() {
        insulators();
        if (pairCache == null) {
            List<int[]> pairs = new ArrayList<>();
            Map<Integer, List<Integer>> byGroup = byGroup();
            for (int g = 0; byGroup.containsKey(g + 1); g += 2) {
                pairs.addAll(pairsBetween(byGroup, g, g + 1));
            }
            pairCache = List.copyOf(pairs);
        }
        return pairCache;
    }

    private Map<Integer, List<Integer>> byGroup() {
        List<Insulator> insulators = insulators();
        Map<Integer, List<Integer>> byGroup = new TreeMap<>();
        for (int i = 0; i < insulators.size(); i++) {
            byGroup.computeIfAbsent(insulators.get(i).group(), g -> new ArrayList<>()).add(i);
        }
        return byGroup;
    }

    private static List<int[]> pairsBetween(Map<Integer, List<Integer>> byGroup, int a, int b) {
        List<int[]> pairs = new ArrayList<>();
        List<Integer> ga = byGroup.getOrDefault(a, List.of()), gb = byGroup.getOrDefault(b, List.of());
        for (int k = 0; k < Math.min(ga.size(), gb.size()); k++) {
            pairs.add(new int[] { ga.get(k), gb.get(k) });
        }
        return pairs;
    }

    // The node ids of one group's insulators (null where one isn't in the grid).
    private List<Long> groupNodes(GridData.Dimension dimension, int group) {
        List<Long> out = new ArrayList<>();
        for (int i : byGroup().getOrDefault(group, List.of())) {
            Long id = dimension.nodeAt(new GridData.Anchor(worldPosition, i));
            if (id != null && id >= 0) {
                out.add(id);
            }
        }
        return out;
    }

    // Its links' jumpers (edge ids), one a conductor, in its pairs' order.
    private List<Long> linkEdges(GridData.Dimension dimension) {
        List<Long> out = new ArrayList<>();
        for (int[] pair : linkPairs()) {
            Long a = dimension.nodeAt(new GridData.Anchor(worldPosition, pair[0])), b = dimension.nodeAt(new GridData.Anchor(worldPosition, pair[1]));
            if (a == null || b == null) {
                continue;
            }
            for (GridEdge edge : dimension.graph().edgesOf(a)) {
                if (edge.isJumper() && edge.other(a) == b) {
                    out.add(edge.id());
                }
            }
        }
        return out;
    }

    // The most current through any of its links last tick, A (a fault's too).
    public double linkAmps(ServerLevel level) {
        GridManager.TickView view = GridManager.view(level);
        double most = 0;
        for (long edge : linkEdges(GridData.of(level))) {
            most = Math.max(most, view.amps(edge));
        }
        return most;
    }

    private void relink(ServerLevel level) {
        TowerTier.Kind kind = tier().kind();
        boolean changed;
        if (kind == TowerTier.Kind.ATS) {
            // Its link is a transfer coupling (couplings), so each feeder reads live only from its own side: no jumpers (a
            // save from before had them).
            Map<Integer, List<Integer>> byGroup = byGroup();
            changed = GridManager.link(level, worldPosition, pairsBetween(byGroup, 0, 1), false);
            changed |= GridManager.link(level, worldPosition, pairsBetween(byGroup, 2, 1), false);
        } else {
            changed = GridManager.link(level, worldPosition, linkPairs(), closed || kind == TowerTier.Kind.DEAD_END || kind == TowerTier.Kind.REGULATOR);
        }
        if (changed) {
            GridManager.display(level, this, true);
        }
    }

    // A bus section joins its phases to the next section ahead and behind (two blocks along the line, the same tier,
    // facing either way along it; facing the other way its phases are mirrored).
    private void joinBus(ServerLevel level) {
        int eighths = eighths();
        if (eighths % 2 != 0) {
            return;
        }
        Direction along = TowerPlacement.facingOf(eighths / 2);
        for (int sign = -1; sign <= 1; sign += 2) {
            BlockPos other = worldPosition.relative(along, 2 * sign);
            if (!level.isLoaded(other) || !(level.getBlockEntity(other) instanceof TowerBlockEntity bus) || bus.tier() != tier() || bus.isRemoved()) {
                continue;
            }
            int theirs = bus.eighths();
            boolean same = theirs == eighths, opposite = theirs == (eighths + 4) % 8;
            if (!same && !opposite) {
                continue;
            }
            for (int k = 0; k < 3; k++) {
                GridManager.join(level, new GridData.Anchor(worldPosition, k), new GridData.Anchor(other, same ? k : 2 - k), true);
            }
        }
    }

    // A breaker or switch opens: its links come off, with a spark and a bang.
    private void open(ServerLevel level) {
        closed = false;
        setChanged();
        relink(level);
        level.playSound(null, worldPosition, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.BLOCKS, 0.3F, 2.0F);
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK, worldPosition.getX() + 0.5, worldPosition.getY() + 1.0,
                worldPosition.getZ() + 0.5, 16, 0.4, 0.4, 0.4, 0.2);
        GridManager.display(level, this, true);
    }

    private void close(ServerLevel level) {
        closed = true;
        reclosing.closed(level.getGameTime());
        overcurrent.reset();
        fuse.reset();
        setChanged();
        relink(level);
        GridManager.display(level, this, true);
    }

    // A breaker trips: opens, and recloses after the delay unless that was its last shot (locked out).
    private void trip(ServerLevel level) {
        reclosing.trip(level.getGameTime(), GridConfig.RECLOSE_SHOTS.getAsInt(), GridConfig.RECLOSE_DELAY.getAsInt());
        open(level);
    }

    // A surge inside the transformer (lightning with no arrester near), `surge` per unit of its rating, and a Relay Panel's
    // differential protection (87T): with both sides' currents counted into it, the load through it cancels and the surge
    // doesn't, so past differentialPickup and differentialSlope (Differential) it trips the breakers it's linked to. True
    // if it tripped (a panel linked to this transformer and a CT fitted here); the transformer then survives the fault.
    public boolean differentialTrip(ServerLevel level, double surge) {
        TowerBlockEntity panel = panelFor(level, worldPosition);
        if (panel == null || !ct) {
            return false;
        }
        double rating = GridConfig.SETTINGS.ratedAmps(tier().voltage()) * tier().voltage().volts() * GridConfig.SUBSTATION_RATING.getAsDouble();
        double through = rating <= 0 ? 0 : lastFlow / rating;
        if (!Differential.trips(through + surge, -through, GridConfig.DIFFERENTIAL_PICKUP.getAsDouble(), GridConfig.DIFFERENTIAL_SLOPE.getAsDouble())) {
            return false;
        }
        for (BlockPos pos : panel.controls) {
            if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof TowerBlockEntity breaker && breaker.tier().kind() == TowerTier.Kind.BREAKER
                    && breaker.closed) {
                breaker.reclosing.lockOut();
                breaker.open(level);
            }
        }
        return true;
    }

    // Whether any ground switch grounds either side of this (its insulators' lines), last tick.
    private boolean groundedEitherSide(ServerLevel level) {
        GridData.Dimension dimension = GridData.of(level);
        GridManager.TickView view = GridManager.view(level);
        for (long node : dimension.nodesAt(worldPosition)) {
            if (node >= 0 && view.grounded(dimension.graph(), node)) {
                return true;
            }
        }
        return false;
    }

    private boolean anyLive(ServerLevel level, int group) {
        GridData.Dimension dimension = GridData.of(level);
        GridManager.TickView view = GridManager.view(level);
        for (long node : groupNodes(dimension, group)) {
            if (view.live(dimension.graph(), node)) {
                return true;
            }
        }
        return false;
    }

    // Empty-handed use: a breaker, recloser, sectionalizer or switch opens or closes by hand (closing a breaker clears its
    // lockout), a ground switch, a bank or a transfer switch changes over, a relay panel tells its settings; each within
    // its interlocks. What it is now (or why not), or null if it isn't worked by hand.
    public @Nullable Component operate(ServerLevel level) {
        TowerTier.Kind kind = tier().kind();
        String m = "message." + ArcforgeTransmission.MODID + ".interlock.";
        Interlocks.Refusal refusal = null;
        switch (kind) {
            case BREAKER, SECTIONALIZER, DISCONNECT -> {
                if (kind == TowerTier.Kind.DISCONNECT) {
                    refusal = Interlocks.disconnect(anyControlClosed(level), !closed, !closed && groundedEitherSide(level));
                } else if (!closed) {
                    refusal = Interlocks.closeBreaker(groundedEitherSide(level));
                }
                if (refusal == null) {
                    if (closed) {
                        reclosing.cancel();
                        open(level);
                    } else {
                        reclosing.reset();
                        sectionalizer.close();
                        close(level);
                    }
                }
            }
            case GROUND -> {
                refusal = Interlocks.groundSwitch(!closed, anyLive(level, 0));
                if (refusal == null) {
                    closed = !closed;
                    setChanged();
                    GridManager.display(level, this, true);
                }
            }
            case SHUNT -> {
                closed = !closed;
                setChanged();
                GridManager.display(level, this, true);
            }
            case ATS -> {
                transfer.restore(transfer.position() == TransferSwitch.Position.PREFERRED ? TransferSwitch.Position.ALTERNATE
                        : TransferSwitch.Position.PREFERRED);
                setChanged();
                relink(level);
                GridManager.display(level, this, true);
            }
            case RELAY -> {
                return hudMode();
            }
            default -> {
                return null;
            }
        }
        if (refusal != null) {
            level.playSound(null, worldPosition, SoundEvents.VILLAGER_NO, SoundSource.BLOCKS, 0.4F, 0.8F);
            return Component.translatable(m + refusal.key());
        }
        level.playSound(null, worldPosition, closed ? SoundEvents.IRON_DOOR_CLOSE : SoundEvents.IRON_DOOR_OPEN, SoundSource.BLOCKS, 0.8F, 0.8F);
        return hudMode();
    }

    // Whether a breaker this (a disconnect) is interlocked with is closed.
    // Whether any breaker this disconnect is interlocked with is closed (one in an unloaded chunk can't be seen to be open,
    // so it counts as closed).
    private boolean anyControlClosed(ServerLevel level) {
        for (BlockPos pos : controls) {
            if (!level.isLoaded(pos)) {
                return true;
            }
            if (level.getBlockEntity(pos) instanceof TowerBlockEntity breaker && breaker.tier().kind() == TowerTier.Kind.BREAKER && breaker.closed) {
                return true;
            }
        }
        return false;
    }

    // Whether either of an ATS's feeders (group 0 preferred, 2 alternate) is live from its own side.
    private boolean feederLive(ServerLevel level, int group) {
        GridData.Dimension dimension = GridData.of(level);
        GridManager.TickView view = GridManager.view(level);
        for (long node : groupNodes(dimension, group)) {
            if (view.sourceLive(dimension.graph(), node)) {
                return true;
            }
        }
        return false;
    }

    // A Fuse Link put into a blown cutout. False if it isn't one.
    public boolean refuse(ServerLevel level) {
        if (tier().kind() != TowerTier.Kind.FUSE || closed) {
            return false;
        }
        close(level);
        return true;
    }

    // A CT or PT fitted: "fitted", "already" (one there) or "cant" (nothing here measures it).
    public String fit(boolean current) {
        TowerTier.Kind kind = tier().kind();
        boolean fits = current ? kind == TowerTier.Kind.BREAKER || kind == TowerTier.Kind.SUBSTATION
                : kind == TowerTier.Kind.BREAKER || kind == TowerTier.Kind.SUBSTATION || kind == TowerTier.Kind.SHUNT;
        if (!fits) {
            return "cant";
        }
        if (current ? ct : pt) {
            return "already";
        }
        if (current) {
            ct = true;
        } else {
            pt = true;
        }
        setChanged();
        if (level instanceof ServerLevel server) {
            GridManager.display(server, this, true);
        }
        return "fitted";
    }

    // Control Cable: links (or unlinks) a block to this one. True if it's linked now.
    public boolean toggleControl(BlockPos pos) {
        boolean linked = !controls.remove(pos);
        if (linked) {
            controls.add(pos.immutable());
        }
        setChanged();
        if (level instanceof ServerLevel server) {
            GridManager.display(server, this, true);
        }
        return linked;
    }

    // The Relay Panel linked to a block, or null (looked up once a tick).
    private static final Map<ServerLevel, Map<BlockPos, BlockPos>> PANELS = new WeakHashMap<>();
    private static final Map<ServerLevel, Long> PANELS_AT = new WeakHashMap<>();

    private static @Nullable TowerBlockEntity panelFor(ServerLevel level, BlockPos pos) {
        long now = level.getGameTime();
        if (!Long.valueOf(now).equals(PANELS_AT.get(level))) {
            Map<BlockPos, BlockPos> index = new java.util.HashMap<>();
            for (GridEndpointBlockEntity endpoint : GridManager.loadedIn(level)) {
                if (endpoint instanceof TowerBlockEntity panel && !panel.isRemoved() && panel.tier().kind() == TowerTier.Kind.RELAY) {
                    panel.controls.forEach(controlled -> index.putIfAbsent(controlled, panel.worldPosition));
                }
            }
            PANELS.put(level, index);
            PANELS_AT.put(level, now);
        }
        BlockPos panel = PANELS.get(level).get(pos);
        return panel != null && level.getBlockEntity(panel) instanceof TowerBlockEntity tower ? tower : null;
    }

    // What a comparator reads off its main block (0 to 15).
    public int comparator() {
        TowerTier tier = tier();
        if (!(level instanceof ServerLevel server)) {
            return 0;
        }
        double rated = GridConfig.SETTINGS.ratedAmps(tier.voltage());
        return switch (tier.kind()) {
            case FUSE, BREAKER, DISCONNECT, SECTIONALIZER -> !closed ? 0 : 1 + (int) Math.round(14 * Math.min(1.0, linkAmps(server) / rated));
            case SUBSTATION, UNIT, SERVICE -> (int) Math.round(15 * Math.min(1.0, lastFlow / (rated * tier.voltage().volts())));
            case DEAD_END, BUS -> (int) Math.round(15 * Math.min(1.0, mostAmps(server) / rated));
            case REGULATOR -> Math.max(0, Math.min(15, 8 + tap.position() / 2));
            case GROUND, SHUNT -> closed ? 15 : 0;
            case ATS -> !anyLive(server, 1) ? 0 : transfer.position() == TransferSwitch.Position.PREFERRED ? 8 : 15;
            case RELAY -> (int) Math.min(15, controls.stream().filter(pos -> server.isLoaded(pos) && server.getBlockEntity(pos) instanceof TowerBlockEntity b
                    && b.tier().kind() == TowerTier.Kind.BREAKER && !b.closed).count());
            case TOWER -> 0;
        };
    }

    // The most current on any wire at its insulators.
    private double mostAmps(ServerLevel level) {
        GridData.Dimension dimension = GridData.of(level);
        GridManager.TickView view = GridManager.view(level);
        double most = 0;
        for (long node : dimension.nodesAt(worldPosition)) {
            if (node >= 0) {
                for (GridEdge edge : dimension.graph().edgesOf(node)) {
                    most = Math.max(most, view.amps(edge.id()));
                }
            }
        }
        return most;
    }

    // The draw for rendering: an open switch, breaker or sectionalizer, a blown fuse, a ground switch or shunt out, a
    // transfer switch on its alternate.
    public boolean showsAlt() {
        TowerTier tier = tier();
        if (tier.kind() == TowerTier.Kind.ATS) {
            return transfer.position() == TransferSwitch.Position.ALTERNATE;
        }
        return tier.switched() && !closed;
    }

    // The Wrench on Configure: a tap changer, regulator or transfer switch on its own or held, a unit's mode, a breaker's trip setting (an
    // eighth of its rating a step), a relay panel's time dial (sneaking: fuse-saving or fuse-blowing). What it's set to
    // now, or null when there's nothing to set.
    public @Nullable Component configure(boolean sneaking) {
        TowerTier tier = tier();
        switch (tier.kind()) {
            case SUBSTATION, REGULATOR -> {
                auto = !auto;
                if (!auto) {
                    tap.restore(0);
                }
            }
            case UNIT -> mode = mode.next();
            case ATS -> auto = !auto;
            case BREAKER -> tripEighths = tripEighths % 8 + 1;
            case RELAY -> {
                if (sneaking) {
                    fuseSaving = !fuseSaving;
                } else {
                    dial = (dial + 1) % TIME_DIALS.length;
                }
            }
            default -> {
                return null;
            }
        }
        setChanged();
        if (level instanceof ServerLevel server) {
            GridManager.display(server, this, true);
        }
        return hudMode();
    }

    // The Wrench on Rotate: a transformer or device turns an eighth of a turn about its main block (a quarter for what
    // serves the block in front of it; clockwise seen from above, or counter-clockwise), if every block it would newly take
    // is free. Its wires stay on and re-hang to the bushings' new places. False if there's no room (or it's a tower).
    public boolean rotate(boolean clockwise) {
        TowerTier tier = tier();
        if (!(level instanceof ServerLevel server) || !tier.rotatable()) {
            return false;
        }
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
        setChanged();
        GridManager.moved(server, worldPosition, this);
        return true;
    }

    // --- Guy wires --------------------------------------------------------------------------------------------------

    public List<BlockPos> guys() {
        return guys;
    }

    // Where its guys come off: near the top of its centre column.
    public Vec3 guyPoint() {
        BlockPos centre = TowerPlacement.centre(tier(), eighths(), worldPosition);
        return new Vec3(centre.getX() + 0.5, centre.getY() + tier().height() * 0.85, centre.getZ() + 0.5);
    }

    // A guy to an anchor block within reach (its height and a half, and lower than the guy point). False if not.
    public boolean addGuy(BlockPos anchor) {
        Vec3 top = guyPoint(), to = Vec3.atCenterOf(anchor).add(0, 0.5, 0);
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

    // The FE block a unit transformer or service meter serves: the one in front of it.
    public BlockPos servedPos() {
        return worldPosition.relative(facing());
    }

    // --- The grid tick ----------------------------------------------------------------------------------------------

    @Override
    public void ports(ServerLevel level, GridData.Dimension dimension, List<GridManager.PortHandler> out) {
        TowerTier tier = tier();
        if (tier.linked()) {
            relink(level);
        }
        switch (tier.kind()) {
            case BUS -> joinBus(level);
            case SERVICE -> servicePorts(level, dimension, out);
            case UNIT -> {
                List<Long> nodes = groupNodes(dimension, 0);
                if (nodes.size() == insulators().size()) {
                    lastFlow = 0;
                    out.add(new UnitSide(nodes, tier.voltage(), faceHandlers(level)));
                }
            }
            default -> {}
        }
    }

    @Override
    public void couplings(ServerLevel level, GridData.Dimension dimension, List<Coupling> out) {
        TowerTier tier = tier();
        if (tier.kind() == TowerTier.Kind.ATS) {
            // The load (group 1) on the feeder it's on, through a transfer link.
            List<Long> feeder = groupNodes(dimension, transfer.position() == TransferSwitch.Position.PREFERRED ? 0 : 2), load = groupNodes(dimension, 1);
            if (feeder.size() == 3 && load.size() == 3) {
                double rating = GridConfig.SETTINGS.ratedAmps(tier.voltage()) * tier.voltage().volts();
                out.add(new Coupling(worldPosition.asLong(), tier.voltage(), feeder, tier.voltage(), List.of(load), rating, 0, true, true));
            }
            return;
        }
        if (tier.kind() != TowerTier.Kind.SUBSTATION) {
            return;
        }
        List<Long> primary = groupNodes(dimension, 0), secondary = groupNodes(dimension, 1);
        if (primary.size() == 3 && secondary.size() == 3) {
            double rating = GridConfig.SETTINGS.ratedAmps(tier.voltage()) * tier.voltage().volts() * GridConfig.SUBSTATION_RATING.getAsDouble();
            out.add(new Coupling(worldPosition.asLong(), tier.voltage(), primary, tier.secondary(), List.of(secondary), rating,
                    tap.boost(GridConfig.tapStep()), true));
        }
    }

    @Override
    public void shunts(ServerLevel level, GridData.Dimension dimension, List<Shunt> out) {
        TowerTier tier = tier();
        if (tier.kind() != TowerTier.Kind.SHUNT || !closed) {
            return;
        }
        List<Long> nodes = groupNodes(dimension, 0);
        double rated = GridConfig.SETTINGS.ratedAmps(tier.voltage()) * tier.voltage().volts();
        double vars = tier.reactor() ? -GridConfig.REACTOR_VARS.getAsDouble() * rated : GridConfig.CAPACITOR_VARS.getAsDouble() * rated;
        if (nodes.size() == 3) {
            out.add(new Shunt(worldPosition.asLong(), tier.voltage(), nodes, vars, true));
        }
    }

    @Override
    public void grounded(ServerLevel level, GridData.Dimension dimension, Set<Long> out) {
        if (tier().kind() == TowerTier.Kind.GROUND && closed) {
            out.addAll(groupNodes(dimension, 0));
        }
    }

    @Override
    public void protective(ServerLevel level, GridData.Dimension dimension, Map<Long, FlowSolver.Protective> out) {
        if (tier().protective() && closed) {
            boolean isFuse = tier().kind() == TowerTier.Kind.FUSE;
            FlowSolver.Protective p = new FlowSolver.Protective(isFuse ? fuseAmps() : tripAmps(), isFuse);
            for (long edge : linkEdges(dimension)) {
                out.put(edge, p);
            }
        }
    }

    // After the tick is solved: protection operates, tap changers and regulators step, sectionalizers count, transfer
    // switches change over, banks switch.
    @Override
    public void afterTick(ServerLevel level, GridData.Dimension dimension, GridManager.TickView view) {
        react(level, dimension, view);
        // A comparator beside it hears when what it reads changes.
        int reading = comparator();
        if (reading != lastComparator) {
            lastComparator = reading;
            level.updateNeighbourForOutputSignal(worldPosition, getBlockState().getBlock());
        }
    }

    private void react(ServerLevel level, GridData.Dimension dimension, GridManager.TickView view) {
        TowerTier tier = tier();
        switch (tier.kind()) {
            case FUSE -> {
                if (closed && fuse.tick(linkAmps(dimension, view) / fuseAmps(), GridConfig.FUSE_MELT.getAsDouble(), GridConfig.FUSE_COOLING.getAsDouble())) {
                    open(level);
                }
            }
            case BREAKER -> breakerTick(level, dimension, view);
            case SECTIONALIZER -> {
                double actuating = GridConfig.SETTINGS.ratedAmps(tier.voltage()) * GridConfig.SECTIONALIZER_ACTUATING.getAsDouble();
                boolean live = false;
                for (long node : groupNodes(dimension, 0)) {
                    live |= view.live(dimension.graph(), node);
                }
                if (closed && sectionalizer.tick(linkAmps(dimension, view) > actuating, live, GridConfig.sectionalizerCount(),
                        GridConfig.SECTIONALIZER_RESET.getAsInt())) {
                    open(level);
                }
            }
            case REGULATOR -> {
                watchedVolts = averageVolts(dimension, view, 1);
                if (auto && watchedVolts > 0) {
                    tap.tick(watchedVolts, GridConfig.TAP_BAND.getAsDouble(), GridConfig.tapStep(), GridConfig.TAP_STEPS.getAsInt(),
                            GridConfig.TAP_DELAY.getAsInt());
                }
                GridManager.boost(level, worldPosition, linkPairs(), tap.boost(GridConfig.tapStep()));
            }
            case SUBSTATION -> {
                lastFlow = Math.abs(view.result().couplingFlow().getOrDefault(worldPosition.asLong(), 0.0));
                watchedVolts = averageVolts(dimension, view, 1);
                status = groupNodes(dimension, 0).size() == 3 && groupNodes(dimension, 1).size() == 3 ? FlowSolver.PortStatus.OK
                        : FlowSolver.PortStatus.UNWIRED;
                if (auto && watchedVolts > 0) {
                    tap.tick(watchedVolts, GridConfig.TAP_BAND.getAsDouble(), GridConfig.tapStep(), GridConfig.TAP_STEPS.getAsInt(),
                            GridConfig.TAP_DELAY.getAsInt());
                }
            }
            case SHUNT -> shuntTick(level, dimension, view);
            case ATS -> {
                // Automatic: each feeder judged from its own side (the load's own sources don't make the preferred look live).
                if (auto && transfer.tick(feederLive(level, 0), feederLive(level, 2), GridConfig.TRANSFER_TICKS.getAsInt(),
                        GridConfig.RETRANSFER_TICKS.getAsInt())) {
                    setChanged();
                    relink(level);
                    level.playSound(null, worldPosition, SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 0.8F, 0.7F);
                    GridManager.display(level, this, true);
                }
            }
            default -> {}
        }
    }

    private double linkAmps(GridData.Dimension dimension, GridManager.TickView view) {
        double most = 0;
        for (long edge : linkEdges(dimension)) {
            most = Math.max(most, view.amps(edge));
        }
        return most;
    }

    private double averageVolts(GridData.Dimension dimension, GridManager.TickView view, int group) {
        double sum = 0;
        int n = 0;
        for (long node : groupNodes(dimension, group)) {
            double v = view.volts(dimension.graph(), node);
            if (v > 0) {
                sum += v;
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    // A recloser or breaker: trips on overcurrent (graded; a Relay Panel's curve where it has a CT, and on a lost phase or
    // unbalance), recloses after its delay unless locked out (and never onto a grounded line).
    private void breakerTick(ServerLevel level, GridData.Dimension dimension, GridManager.TickView view) {
        long now = level.getGameTime();
        if (!closed) {
            if (reclosing.due(now)) {
                if (groundedEitherSide(level)) {
                    reclosing.lockOut();
                    GridManager.display(level, this, true);
                    return;
                }
                close(level);
                level.playSound(null, worldPosition, SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 0.8F, 0.7F);
            }
            return;
        }
        List<Long> links = linkEdges(dimension);
        double most = 0;
        FlowSolver.Beyond beyond = FlowSolver.Beyond.NONE;
        double[] each = new double[links.size()];
        for (int i = 0; i < links.size(); i++) {
            each[i] = view.amps(links.get(i));
            if (each[i] > most) {
                most = each[i];
                beyond = view.beyond(links.get(i));
            }
        }
        double rated = GridConfig.SETTINGS.ratedAmps(tier().voltage());
        TowerBlockEntity panel = panelFor(level, worldPosition);
        boolean relayed = panel != null && ct;
        Overcurrent.Settings settings = relayed
                ? new Overcurrent.Settings(tripAmps(), GridConfig.INSTANT_MULTIPLE.getAsDouble() * rated, TIME_DIALS[panel.dial], true, 0,
                        GridConfig.GRADING_INTERVAL.getAsInt(), panel.fuseSaving, GridConfig.FAST_SHOTS.getAsInt())
                : new Overcurrent.Settings(tripAmps(), 0, 0, false, GridConfig.BREAKER_TRIP_TICKS.getAsInt(), GridConfig.GRADING_INTERVAL.getAsInt(), false, 0);
        if (overcurrent.tick(most, beyond, reclosing.shots(), settings, GridConfig.FUSE_MELT.getAsDouble())) {
            trip(level);
            return;
        }
        // Unbalance (a lost phase is the worst): told once when it starts; a relayed breaker trips on it.
        boolean tripping = unbalance.tick(each, rated, GridConfig.UNBALANCE_THRESHOLD.getAsDouble(), GridConfig.UNBALANCE_TICKS.getAsInt());
        if (unbalance.started()) {
            GridFaults.raise(FaultEvent.edge(links.get(0), FaultEvent.Cause.UNBALANCE, Unbalance.of(each)));
        }
        if (relayed && tripping) {
            trip(level);
            return;
        }
        reclosing.forget(now);
    }

    // A capacitor bank or reactor: redstone switches it while it changes; a Relay Panel (a PT fitted here) on the voltage.
    private void shuntTick(ServerLevel level, GridData.Dimension dimension, GridManager.TickView view) {
        watchedVolts = averageVolts(dimension, view, 0);
        boolean powered = level.hasNeighborSignal(worldPosition);
        if (powered != lastPowered) {
            lastPowered = powered;
            setSwitched(level, powered);
            return;
        }
        if (!pt || panelFor(level, worldPosition) == null || watchedVolts <= 0) {
            return;
        }
        double low = GridConfig.SWITCH_LOW.getAsDouble(), high = GridConfig.SWITCH_HIGH.getAsDouble();
        boolean reactor = tier().reactor();
        if (closed && (reactor ? watchedVolts < low : watchedVolts > high)) {
            setSwitched(level, false);
        } else if (!closed && (reactor ? watchedVolts > high : watchedVolts < low)) {
            setSwitched(level, true);
        }
    }

    private void setSwitched(ServerLevel level, boolean in) {
        if (closed != in) {
            closed = in;
            setChanged();
            level.playSound(null, worldPosition, in ? SoundEvents.IRON_DOOR_CLOSE : SoundEvents.IRON_DOOR_OPEN, SoundSource.BLOCKS, 0.6F, 1.1F);
            GridManager.display(level, this, true);
        }
    }

    // A service meter: the building's block fed from L1 + N and L2 + N (its spools, in that order L1 N L2).
    private void servicePorts(ServerLevel level, GridData.Dimension dimension, List<GridManager.PortHandler> out) {
        Long l1 = dimension.nodeAt(new GridData.Anchor(worldPosition, 0)), n = dimension.nodeAt(new GridData.Anchor(worldPosition, 1)),
                l2 = dimension.nodeAt(new GridData.Anchor(worldPosition, 2));
        if (l1 == null || n == null || l2 == null) {
            return;
        }
        BlockPos served = servedPos();
        if (servedCache == null || !servedCache.pos().equals(served)) {
            servedCache = BlockCapabilityCache.create(Capabilities.Energy.BLOCK, level, served, facing().getOpposite());
        }
        EnergyHandler handler = level.isLoaded(served) ? servedCache.getCapability() : null;
        lastFlow = 0;
        // The two legs share the block's room: asked once, split between them.
        int room = 0;
        if (handler != null) {
            int most = (int) Math.floor(GridConfig.SETTINGS.ratedAmps(VoltageTier.LV) * VoltageTier.LV.volts());
            try (Transaction tx = Transaction.openRoot()) {
                room = handler.insert(most, tx);
            }
        }
        out.add(new ServiceSide(List.of(l1, n), handler, room - room / 2));
        out.add(new ServiceSide(List.of(l2, n), handler, room / 2));
    }

    // One leg of the service (L1 or L2 with the neutral) into the building's block.
    private final class ServiceSide implements GridManager.PortHandler {
        private final List<Long> nodes;
        private final @Nullable EnergyHandler handler;
        private final int room;

        ServiceSide(List<Long> nodes, @Nullable EnergyHandler handler, int room) {
            this.nodes = nodes;
            this.handler = handler;
            this.room = room;
        }

        @Override
        public Port port(long id) {
            return new Port(id, VoltageTier.LV, nodes, 0, handler == null ? 0 : room, Double.POSITIVE_INFINITY);
        }

        @Override
        public int draw(int fe, TransactionContext tx) {
            return 0;
        }

        @Override
        public int deliver(int fe, TransactionContext tx) {
            return handler == null ? 0 : handler.insert(fe, tx);
        }

        @Override
        public void moved(int drawn, int deliveredNow) {
            delivered += deliveredNow;
            setChanged();
        }

        @Override
        public void report(FlowSolver.PortStatus status, double drawn, double deliveredNow) {
            TowerBlockEntity.this.status = status;
            lastFlow += deliveredNow;
        }
    }

    // A unit transformer's rated power, FE/t: the most it puts on or takes off the line in a tick.
    static int unitPower(VoltageTier tier) {
        return (int) Math.min(Integer.MAX_VALUE, Math.floor(GridConfig.SETTINGS.ratedAmps(tier) * tier.volts()));
    }

    // The energy capability its blocks show (a unit transformer's: generators push into it on Input), else none.
    public @Nullable EnergyHandler energyCapability() {
        return tier().kind() == TowerTier.Kind.UNIT ? intake : null;
    }

    // The FE blocks touching a unit transformer's whole front face (each block of it, looking out the front), each once.
    private List<EnergyHandler> faceHandlers(ServerLevel level) {
        if (face == null || faceEighths != eighths()) {
            TowerTier tier = tier();
            Direction front = facing();
            List<BlockPos> mine = TowerPlacement.positions(tier, eighths(), TowerPlacement.centre(tier, eighths(), worldPosition));
            java.util.Set<BlockPos> cells = new java.util.HashSet<>(mine);
            java.util.Set<BlockPos> seen = new java.util.HashSet<>();
            face = new ArrayList<>();
            for (BlockPos pos : mine) {
                BlockPos out = pos.relative(front);
                if (!cells.contains(out) && seen.add(out)) {
                    face.add(BlockCapabilityCache.create(Capabilities.Energy.BLOCK, level, out, front.getOpposite()));
                }
            }
            faceEighths = eighths();
        }
        List<EnergyHandler> out = new ArrayList<>();
        for (BlockCapabilityCache<EnergyHandler, @Nullable Direction> cache : face) {
            if (!level.isLoaded(cache.pos())) {
                continue;
            }
            EnergyHandler handler = cache.getCapability();
            if (handler != null && handler != intake && out.stream().noneMatch(h -> h == handler)) {
                out.add(handler);
            }
        }
        return out;
    }

    // A unit transformer's line side: its front face's FE blocks in (Input, with what generators pushed into it), out
    // (Output) or either (Storage, a battery), up to the tier's rated power. No FE block and nothing pushed: NO_FE_BLOCK.
    private final class UnitSide implements GridManager.PortHandler {
        private final List<Long> nodes;
        private final VoltageTier tier;
        private final List<EnergyHandler> handlers;
        private boolean anything;

        UnitSide(List<Long> nodes, VoltageTier tier, List<EnergyHandler> handlers) {
            this.nodes = nodes;
            this.tier = tier;
            this.handlers = handlers;
        }

        @Override
        public Port port(long id) {
            int most = unitPower(tier);
            double offer = 0, demand = 0;
            // Not committed: only asking (all in one transaction, so a block on two faces is counted once).
            if (mode.gives()) {
                try (Transaction tx = Transaction.openRoot()) {
                    offer = mode == PinInsulatorMode.INPUT ? pushed.extract(most, tx) : 0;
                    for (EnergyHandler handler : handlers) {
                        offer += handler.extract((int) (most - offer), tx);
                    }
                }
            }
            if (mode.takes()) {
                try (Transaction tx = Transaction.openRoot()) {
                    for (EnergyHandler handler : handlers) {
                        demand += handler.insert((int) (most - demand), tx);
                    }
                }
            }
            anything = !handlers.isEmpty() || pushed.getAmountAsInt() > 0;
            return new Port(id, tier, nodes, offer, demand, Double.POSITIVE_INFINITY, mode == PinInsulatorMode.STORAGE, 1.0);
        }

        @Override
        public int draw(int fe, TransactionContext tx) {
            if (!mode.gives()) {
                return 0;
            }
            int got = mode == PinInsulatorMode.INPUT ? pushed.extract(fe, tx) : 0;
            for (EnergyHandler handler : handlers) {
                if (got >= fe) {
                    break;
                }
                got += handler.extract(fe - got, tx);
            }
            return got;
        }

        @Override
        public int deliver(int fe, TransactionContext tx) {
            if (!mode.takes()) {
                return 0;
            }
            int took = 0;
            for (EnergyHandler handler : handlers) {
                if (took >= fe) {
                    break;
                }
                took += handler.insert(fe - took, tx);
            }
            return took;
        }

        @Override
        public void report(FlowSolver.PortStatus status, double drawn, double delivered) {
            TowerBlockEntity.this.status = !anything && mode != PinInsulatorMode.OFF && status != FlowSolver.PortStatus.UNWIRED ? FlowSolver.PortStatus.NO_FE_BLOCK
                    : status;
            lastFlow = drawn > 0 ? drawn : delivered;
        }
    }

    // A device keeps its links in step with it from the start; a dead-end's are always there.
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
        return status != sentStatus || mode != sentMode || closed != sentClosed || reclosing.lockedOut() != sentLockout || tripEighths != sentTrip || ct != sentCt
                || pt != sentPt || auto != sentAuto || fuseSaving != sentFuseSaving || dial != sentDial || tap.position() != sentTap
                || transfer.position() != sentPosition || sectionalizer.counted() != sentCount;
    }

    @Override
    protected boolean hudLiveChanged() {
        return Math.round(lastFlow) != sentFlow || Math.round(watchedVolts * 1000) != sentWatched;
    }

    @Override
    protected void hudSent() {
        sentStatus = status;
        sentMode = mode;
        sentFlow = Math.round(lastFlow);
        sentWatched = Math.round(watchedVolts * 1000);
        sentClosed = closed;
        sentLockout = reclosing.lockedOut();
        sentTrip = tripEighths;
        sentCt = ct;
        sentPt = pt;
        sentAuto = auto;
        sentFuseSaving = fuseSaving;
        sentDial = dial;
        sentTap = tap.position();
        sentPosition = transfer.position();
        sentCount = sectionalizer.counted();
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.putString("hud_status", status.name());
        tag.putLong("hud_flow", Math.round(lastFlow));
        tag.putLong("hud_reclose", reclosing.recloseAt());
        tag.putLong("hud_watched", Math.round(watchedVolts * 1000));
        tag.putInt("hud_count", sectionalizer.counted());
        return tag;
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store("guys", BlockPos.CODEC.listOf(), guys);
        if (tier().kind() != TowerTier.Kind.TOWER) {
            output.store("mode", PinInsulatorMode.CODEC, mode);
            output.store("closed", Codec.BOOL, closed);
            output.store("lockout", Codec.BOOL, reclosing.lockedOut());
            output.store("shots", Codec.INT, reclosing.shots());
            output.store("trip_eighths", Codec.INT, tripEighths);
            output.store("delivered", Codec.LONG, delivered);
            output.store("tap", Codec.INT, tap.position());
            output.store("auto", Codec.BOOL, auto);
            output.store("ct", Codec.BOOL, ct);
            output.store("pt", Codec.BOOL, pt);
            output.store("controls", BlockPos.CODEC.listOf(), controls);
            output.store("dial", Codec.INT, dial);
            output.store("fuse_saving", Codec.BOOL, fuseSaving);
            output.store("counted", Codec.INT, sectionalizer.counted());
            output.store("alternate", Codec.BOOL, transfer.position() == TransferSwitch.Position.ALTERNATE);
            output.store("powered", Codec.BOOL, lastPowered);
            output.store("reclose_at", Codec.LONG, reclosing.recloseAt());
            output.store("closed_at", Codec.LONG, reclosing.closedAt());
            output.store("pushed", Codec.INT, pushed.getAmountAsInt());
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        guys = new ArrayList<>(input.read("guys", BlockPos.CODEC.listOf()).orElse(List.of()));
        mode = input.read("mode", PinInsulatorMode.CODEC).orElse(PinInsulatorMode.OFF);
        input.read("closed", Codec.BOOL).ifPresent(value -> closed = value);
        tripEighths = Math.max(1, Math.min(8, input.read("trip_eighths", Codec.INT).orElse(8)));
        delivered = input.read("delivered", Codec.LONG).orElse(0L);
        tap.restore(input.read("tap", Codec.INT).orElse(0));
        auto = input.read("auto", Codec.BOOL).orElse(true);
        ct = input.read("ct", Codec.BOOL).orElse(false);
        pt = input.read("pt", Codec.BOOL).orElse(false);
        controls = new ArrayList<>(input.read("controls", BlockPos.CODEC.listOf()).orElse(List.of()));
        dial = Math.max(0, Math.min(TIME_DIALS.length - 1, input.read("dial", Codec.INT).orElse(1)));
        fuseSaving = input.read("fuse_saving", Codec.BOOL).orElse(true);
        sectionalizer.restore(input.read("counted", Codec.INT).orElse(0), false);
        transfer.restore(input.read("alternate", Codec.BOOL).orElse(false) ? TransferSwitch.Position.ALTERNATE : TransferSwitch.Position.PREFERRED);
        lastPowered = input.read("powered", Codec.BOOL).orElse(false);
        pushed.set(Math.max(0, Math.min(pushed.getCapacityAsInt(), input.read("pushed", Codec.INT).orElse(0))));
        long recloseAt = input.read("hud_reclose", Codec.LONG).orElse(input.read("reclose_at", Codec.LONG).orElse(0L));
        reclosing.restore(input.read("shots", Codec.INT).orElse(0), input.read("lockout", Codec.BOOL).orElse(false), recloseAt,
                input.read("closed_at", Codec.LONG).orElse(0L));
        input.read("hud_status", Codec.STRING).ifPresent(name -> status = FlowSolver.PortStatus.valueOf(name));
        input.read("hud_flow", Codec.LONG).ifPresent(flow -> lastFlow = flow);
        input.read("hud_watched", Codec.LONG).ifPresent(v -> watchedVolts = v / 1000.0);
        input.read("hud_count", Codec.INT).ifPresent(count -> sectionalizer.restore(count, false));
    }

    // The whole tower goes with its main block; its parts drop nothing. Its wires go too, dropping their coils
    // (GridEndpointBlockEntity); and its guys, and any CT and PT fitted.
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        removing = true;
        super.preRemoveSideEffects(pos, state);
        if (!(level instanceof ServerLevel server) || !(state.getBlock() instanceof TowerBlock tower)) {
            return;
        }
        boolean drops = GridManager.dropsAt(server, pos);
        for (BlockPos guy : drops ? guys : List.<BlockPos>of()) {
            Block.popResource(level, pos, new net.minecraft.world.item.ItemStack(net.zagdrath.arcforgetransmission.grid.block.GridContent.GUY_WIRE.get()));
        }
        if (ct && drops) {
            Block.popResource(level, pos, new net.minecraft.world.item.ItemStack(net.zagdrath.arcforgetransmission.grid.block.GridContent.CURRENT_TRANSFORMER.get()));
        }
        if (pt && drops) {
            Block.popResource(level, pos, new net.minecraft.world.item.ItemStack(net.zagdrath.arcforgetransmission.grid.block.GridContent.POTENTIAL_TRANSFORMER.get()));
        }
        int eighths = TowerPlacement.eighths(state);
        for (BlockPos part : TowerPlacement.positions(tower.tier(), eighths, TowerPlacement.centre(tower.tier(), eighths, pos))) {
            if (!part.equals(pos) && level.getBlockState(part).getBlock() instanceof TowerPartBlock && pos.equals(TowerPartBlock.mainOf(level, part))) {
                level.setBlock(part, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }
}
