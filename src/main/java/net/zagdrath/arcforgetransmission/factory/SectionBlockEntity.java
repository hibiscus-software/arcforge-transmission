/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.factory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.zagdrath.arcforge.item.tool.WrenchItem;
import net.zagdrath.arcforge.item.tool.WrenchMode;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.effects.ArcEvent;
import net.zagdrath.arcforgetransmission.grid.Coupling;
import net.zagdrath.arcforgetransmission.grid.FlowSolver;
import net.zagdrath.arcforgetransmission.grid.GridEdge;
import net.zagdrath.arcforgetransmission.grid.GridNetwork;
import net.zagdrath.arcforgetransmission.grid.Phase;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.block.GridEndpointBlockEntity;
import net.zagdrath.arcforgetransmission.grid.protection.Interlocks;
import net.zagdrath.arcforgetransmission.grid.world.GridArcs;
import net.zagdrath.arcforgetransmission.grid.world.GridConfig;
import net.zagdrath.arcforgetransmission.grid.world.GridData;
import net.zagdrath.arcforgetransmission.grid.world.GridManager;
import net.zagdrath.arcforgetransmission.grid.world.Insulator;
import net.zagdrath.arcforgetransmission.tower.model.FactoryMesh;
import net.zagdrath.arcforgetransmission.tower.model.FactoryMesh.Op;
import net.zagdrath.arcforgetransmission.tower.model.FactoryMesh.OpState;
import net.zagdrath.arcforgetransmission.tower.model.FactoryMesh.Section;

// A switchgear lineup's section in the world. Its conductors (whole circuits of three, A B C) by what it is:
//   Incoming     line (its roof or air bushings' tops: the overhead line's attach points, or a Cable Termination on it)
//                and bus, joined while its switch (LV, MV: load-break, with current-limiting fuses) or its breaker and
//                disconnector (HV, EHV; an earthing switch grounds the line) are closed;
//   Transformer  primary, secondary (a tier down) and bus: a coupling (the grid's transformer link) primary to
//                secondary, its winding's %R and %Z as the secondary's links to the bus; fan cooled past 100%, heating
//                past its fan rating until it trips its incomer on over-temperature;
//   Main, Tie    in and out (a tie: the west half's bus and the east's), joined while its breaker is closed;
//   Feeder       bus and outlets (two at LV and MV, one a bay at HV and EHV), each joined while its breaker is closed; an
//                outlet leaves by a Cable Termination on the section (the lower breaker's on its lower block, the upper's on
//                its upper) or a busway feed-in on its roof.
// Sections side by side, facing the same way, of one tier and in a lineup's order (Lineup) join their buses to their
// neighbours' with links of no length; any other row reports what's wrong and does nothing. Its breakers are protective
// links on the grid's own graded protection (SectionBreaker), worked by hand, by redstone into their block, and read by
// comparator; interlocked as a substation's are.
public class SectionBlockEntity extends GridEndpointBlockEntity implements GridManager.PortSource, TerminationHost {
    private final List<SectionBreaker> breakers = new ArrayList<>();
    // An HV or EHV incomer's disconnector and earthing switch.
    private boolean disconnector = true, earth;
    // A transformer section's off-load tap (-2 to 2) and its heat (1: its trip temperature), and whether its fans run.
    private int tap;
    private double heat;
    private boolean fans;
    // The redstone each breaker's block last heard.
    private boolean[] powered = new boolean[0];
    // Its place in its lineup (null: none planned yet), the lineup's problem, and the row (west to east).
    private Lineup.@Nullable Role role;
    private Lineup.@Nullable Problem problem;
    private List<BlockPos> row = List.of();
    private boolean replan = true;
    private long nextPlan;
    // The lineup's HUD as the client last heard: rows of numbers (Hud), and the problem's key.
    private long[] hud = new long[0];
    private String hudProblem = "";
    private long[] sentHud = new long[0];
    private String sentProblem = "";
    private int lastComparator = -1;

    public SectionBlockEntity(BlockPos pos, BlockState state) {
        super(FactoryContent.SECTION_ENTITY.get(), pos, state);
        for (Op op : opsOf(block())) {
            if (op == Op.BREAKER || op == Op.SWITCH) {
                breakers.add(new SectionBreaker());
            }
        }
        powered = new boolean[breakers.size()];
    }

    private static List<Op> opsOf(SectionBlock block) {
        List<Op> out = new ArrayList<>();
        FactoryMesh.operators(block.section(), block.tier().ordinal()).forEach(o -> out.add(o.op()));
        return out;
    }

    public SectionBlock block() {
        return (SectionBlock) getBlockState().getBlock();
    }

    public Section section() {
        return block().section();
    }

    public VoltageTier tier() {
        return block().tier();
    }

    public Direction facing() {
        BlockState state = getBlockState();
        return state.hasProperty(SectionBlock.FACING) ? state.getValue(SectionBlock.FACING) : Direction.NORTH;
    }

    public List<SectionBreaker> breakers() {
        return breakers;
    }

    public boolean disconnector() {
        return disconnector;
    }

    public boolean earthed() {
        return earth;
    }

    public double heat() {
        return heat;
    }

    public int tap() {
        return tap;
    }

    public Lineup.@Nullable Problem problem() {
        return problem;
    }

    public boolean planned() {
        return role != null && problem == null;
    }

    public Lineup.@Nullable Role role() {
        return role;
    }

    // --- Its conductors -------------------------------------------------------------------------------------------

    // Its groups of three (A B C): their count and each one's tier below the lineup's.
    private int groups() {
        return switch (section()) {
            case TRANSFORMER -> 3;
            case FEEDER -> tier().ordinal() <= 1 ? 3 : 2;
            default -> 2;
        };
    }

    private int drop(int group) {
        Lineup.Role r = planned() ? role : null;
        return switch (section()) {
            case INCOMING -> 0;
            case TRANSFORMER -> group == 0 ? 0 : 1;
            default -> r == null ? 0 : r.dropIn();
        };
    }

    public VoltageTier groupTier(int group) {
        return LineupRatings.down(tier(), drop(group));
    }

    // The group toward its half's incomer and the one away (-1 none): the incomer's own bus is away; a feeder's bus both.
    private int incomerSide() {
        return switch (section()) {
            case INCOMING -> -1;
            case FEEDER -> 0;
            default -> 0;
        };
    }

    private int farSide() {
        return switch (section()) {
            case INCOMING -> 1;
            case TRANSFORMER -> 2;
            case FEEDER -> 0;
            default -> 1;
        };
    }

    // Its groups on its west and east (along the lineup), for joining its neighbours'.
    public int westGroup() {
        if (section() == Section.TIE) {
            return 0;
        }
        return role != null && role.incomer() == 1 ? farSide() : incomerSide();
    }

    public int eastGroup() {
        if (section() == Section.TIE) {
            return 1;
        }
        return role != null && role.incomer() == 1 ? incomerSide() : farSide();
    }

    private static final Phase[] ABC = { Phase.A, Phase.B, Phase.C };
    private static final String[] GROUP_NAMES = { "first", "second", "third" };

    @Override
    public List<Insulator> insulators() {
        List<Insulator> out = new ArrayList<>();
        int t = tier().ordinal();
        boolean gis = t >= 2;
        List<float[]> bushings = FactoryMesh.section(section(), t).insulators();
        for (int g = 0; g < groups(); g++) {
            VoltageTier tier = groupTier(g);
            for (int k = 0; k < 3; k++) {
                Vec3 at;
                if (section() == Section.INCOMING && g == 0 && k < bushings.size()) {
                    float[] b = bushings.get(k);
                    at = SectionGeometry.point(worldPosition, facing(), (b[0] + b[3]) / 2, b[4], (b[2] + b[5]) / 2);
                } else {
                    at = SectionGeometry.point(worldPosition, facing(), gis ? 8 + 8 * k : 4 + 4 * k, gis ? 16 + 10 * g : 6 + 8 * g, 8);
                }
                out.add(new Insulator(tier, ABC[k], at, g, GROUP_NAMES[g] + "_" + ABC[k].id()));
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

    private static List<int[]> pairs(int a, int b) {
        return List.of(new int[] { a * 3, b * 3 }, new int[] { a * 3 + 1, b * 3 + 1 }, new int[] { a * 3 + 2, b * 3 + 2 });
    }

    // Each breaker's pairs of groups: what it joins while closed.
    private int[] joins(int breaker) {
        return switch (section()) {
            case INCOMING, MAIN, TIE -> new int[] { 0, 1 };
            case FEEDER -> new int[] { 0, 1 + breaker };
            case TRANSFORMER -> new int[] { 1, 2 };
        };
    }

    // Whether a breaker's link is made: closed, the section in a lineup, and (an HV or EHV incomer) its disconnector closed.
    private boolean linked(int breaker) {
        return planned() && breakers.get(breaker).closed() && (section() != Section.INCOMING || tier().ordinal() <= 1 || disconnector);
    }

    // Makes its links match its breakers (and a transformer's winding, always there in a lineup).
    void relink(ServerLevel level) {
        for (int i = 0; i < breakers.size(); i++) {
            int[] j = joins(i);
            GridManager.link(level, worldPosition, pairs(j[0], j[1]), linked(i));
        }
        if (section() == Section.TRANSFORMER) {
            GridManager.link(level, worldPosition, pairs(1, 2), planned());
        }
        GridManager.display(level, this, true);
    }

    private List<Long> linkEdges(GridData.Dimension dimension, int a, int b) {
        List<Long> out = new ArrayList<>();
        List<Long> from = nodes(dimension, a), to = nodes(dimension, b);
        for (int k = 0; k < Math.min(from.size(), to.size()); k++) {
            for (GridEdge edge : dimension.graph().edgesOf(from.get(k))) {
                if (edge.isJumper() && edge.other(from.get(k)) == to.get(k)) {
                    out.add(edge.id());
                }
            }
        }
        return out;
    }

    // --- The lineup -----------------------------------------------------------------------------------------------

    // The section main block at a position (its main or a part), or null.
    private static @Nullable SectionBlockEntity sectionAt(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return null;
        }
        BlockPos main = level.getBlockEntity(pos) instanceof SectionPartBlockEntity part ? part.mainPos() : pos;
        return level.getBlockEntity(main) instanceof SectionBlockEntity section && !section.isRemoved() ? section : null;
    }

    private BlockPos local(int x) {
        return SectionGeometry.world(worldPosition, facing(), new int[] { x, 0, 0 });
    }

    // Its neighbour along the lineup: west (its east edge at our west) or east (its main at our east edge), facing the
    // same way and level with us; null if none (or its block isn't loaded: unknown).
    private @Nullable SectionBlockEntity neighbour(Level level, boolean east) {
        BlockPos probe = local(east ? FactoryMesh.sectionWidth(tier().ordinal()) : -1);
        SectionBlockEntity other = sectionAt(level, probe);
        if (other == null || other.facing() != facing() || other.worldPosition.getY() != worldPosition.getY()) {
            return null;
        }
        int width = FactoryMesh.sectionWidth(other.tier().ordinal());
        BlockPos expected = east ? probe : local(-width);
        return other.worldPosition.equals(expected) ? other : null;
    }

    // Whether a neighbour's block is unloaded (the row can't be read whole: keep what's planned).
    private boolean edgeUnloaded(Level level, boolean east) {
        return !level.isLoaded(local(east ? FactoryMesh.sectionWidth(tier().ordinal()) : -1));
    }

    // Reads its row, plans it (Lineup), and makes its conductors, joins and links match.
    private void plan(ServerLevel level) {
        List<SectionBlockEntity> sections = new ArrayList<>();
        sections.add(this);
        SectionBlockEntity at = this;
        for (int i = 0; i < 64; i++) {
            if (at.edgeUnloaded(level, false)) {
                return;
            }
            SectionBlockEntity west = at.neighbour(level, false);
            if (west == null || sections.contains(west)) {
                break;
            }
            sections.add(0, west);
            at = west;
        }
        at = this;
        for (int i = 0; i < 64; i++) {
            if (at.edgeUnloaded(level, true)) {
                return;
            }
            SectionBlockEntity east = at.neighbour(level, true);
            if (east == null || sections.contains(east)) {
                break;
            }
            sections.add(east);
            at = east;
        }
        List<Section> kinds = new ArrayList<>();
        List<Integer> tiers = new ArrayList<>();
        for (SectionBlockEntity s : sections) {
            kinds.add(s.section());
            tiers.add(s.tier().ordinal());
        }
        Lineup.Plan plan = Lineup.plan(kinds, tiers, GridConfig.MAX_FEEDER_SECTIONS.getAsInt());
        int index = sections.indexOf(this);
        Lineup.Role newRole = plan.valid() ? plan.roles().get(index) : new Lineup.Role(-1, 0, 0, 0);
        boolean changed = !newRole.equals(role) || plan.problem() != problem;
        role = newRole;
        problem = plan.problem();
        List<BlockPos> newRow = new ArrayList<>();
        sections.forEach(s -> newRow.add(s.worldPosition));
        row = newRow;
        if (changed) {
            setChanged();
            GridManager.attach(level, worldPosition, insulators(), this);
        }
        rejoin(level, index + 1 < sections.size() ? sections.get(index + 1) : null);
        relink(level);
    }

    // Makes its joins to other blocks match what they should be: its east group to its east neighbour's west group (a
    // lineup), and each Cable Termination on it to the group its cable serves.
    private void rejoin(ServerLevel level, @Nullable SectionBlockEntity east) {
        GridData.Dimension dimension = GridData.of(level);
        Set<Long> mine = new HashSet<>();
        for (int g = 0; g < groups(); g++) {
            mine.addAll(nodes(dimension, g));
        }
        // What should be joined: pairs of (one of ours, one of theirs).
        Set<String> want = new LinkedHashSet<>();
        if (planned() && east != null && east.planned()) {
            List<Long> a = nodes(dimension, eastGroup()), b = east.nodes(dimension, east.westGroup());
            for (int k = 0; k < Math.min(a.size(), b.size()); k++) {
                want.add(a.get(k) + ":" + b.get(k));
            }
        }
        for (Map.Entry<CableTerminationBlockEntity, Integer> entry : terminations(level).entrySet()) {
            List<Long> a = nodes(dimension, entry.getValue()), b = entry.getKey().nodes(dimension);
            for (int k = 0; k < Math.min(a.size(), b.size()); k++) {
                want.add(a.get(k) + ":" + b.get(k));
            }
        }
        // What is: jumpers from ours to a node not ours whose far end this block owns joining (not a west neighbour's to
        // us: that one's its to make).
        Set<Long> westNodes = new HashSet<>();
        SectionBlockEntity west = neighbour(level, false);
        if (west != null) {
            for (int g = 0; g < west.groups(); g++) {
                westNodes.addAll(west.nodes(dimension, g));
            }
        }
        List<Long> remove = new ArrayList<>();
        Set<String> have = new HashSet<>();
        for (long node : mine) {
            for (GridEdge edge : dimension.graph().edgesOf(node)) {
                long other = edge.other(node);
                if (!edge.isJumper() || mine.contains(other) || westNodes.contains(other)) {
                    continue;
                }
                String key = node + ":" + other;
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
                GridManager.jumper(level, Long.parseLong(ab[0]), Long.parseLong(ab[1]));
            }
        }
    }

    // The Cable Terminations mounted on its blocks (not its front), each with the group it joins: an incomer's line, a
    // feeder's outlet (at LV and MV the lower breaker's on its lower block, the upper's on its upper).
    private Map<CableTerminationBlockEntity, Integer> terminations(Level level) {
        Map<CableTerminationBlockEntity, Integer> out = new java.util.LinkedHashMap<>();
        if (section() != Section.INCOMING && section() != Section.FEEDER) {
            return out;
        }
        for (int[] cell : SectionGeometry.cells(section(), tier().ordinal())) {
            BlockPos pos = SectionGeometry.world(worldPosition, facing(), cell);
            for (Direction side : Direction.values()) {
                BlockPos next = pos.relative(side);
                if (side == facing() || !level.isLoaded(next)) {
                    continue;
                }
                if (level.getBlockEntity(next) instanceof CableTerminationBlockEntity termination && termination.mountPos().equals(pos)) {
                    int group = section() == Section.INCOMING ? 0 : tier().ordinal() <= 1 && cell[1] >= 1 ? 2 : 1;
                    out.put(termination, group);
                }
            }
        }
        return out;
    }

    // A neighbour changed (a section placed or gone beside it, a termination mounted, a redstone signal): plan again
    // next tick, and hear the redstone now.
    public void neighbourChanged(ServerLevel level) {
        replan = true;
        redstone(level);
    }

    // --- Operating ------------------------------------------------------------------------------------------------

    // The other sections of its lineup (loaded ones), west to east.
    private List<SectionBlockEntity> lineup(Level level) {
        List<SectionBlockEntity> out = new ArrayList<>();
        for (BlockPos pos : row) {
            if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof SectionBlockEntity s) {
                out.add(s);
            }
        }
        return out;
    }

    // The incomer of its bus half.
    private @Nullable SectionBlockEntity incomer(Level level) {
        if (role == null) {
            return null;
        }
        for (SectionBlockEntity s : lineup(level)) {
            if (s.section() == Section.INCOMING && s.role != null && s.role.half() == role.half()) {
                return s;
            }
        }
        return null;
    }

    // Whether its half has a transformer section.
    private boolean transformed(Level level) {
        for (SectionBlockEntity s : lineup(level)) {
            if (s.section() == Section.TRANSFORMER && s.role != null && role != null && s.role.half() == role.half()) {
                return true;
            }
        }
        return false;
    }

    // The nearest operator to a point (world).
    private int nearestOperator(Vec3 hit) {
        float[] p = SectionGeometry.local(worldPosition, facing(), hit);
        List<FactoryMesh.Operator> ops = FactoryMesh.operators(section(), tier().ordinal());
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < ops.size(); i++) {
            FactoryMesh.Operator o = ops.get(i);
            double d = (o.x() - p[0]) * (o.x() - p[0]) + (o.y() - p[1]) * (o.y() - p[1]);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    // An operator's breaker (by its place among the breakers), or -1 for a disconnector or earthing switch.
    private int breakerOf(int operator) {
        List<FactoryMesh.Operator> ops = FactoryMesh.operators(section(), tier().ordinal());
        int n = 0;
        for (int i = 0; i < ops.size(); i++) {
            Op op = ops.get(i).op();
            if (op == Op.BREAKER || op == Op.SWITCH) {
                if (i == operator) {
                    return n;
                }
                n++;
            }
        }
        return -1;
    }

    // An empty hand on its front: the handle nearest the click opens or closes (a tripped one resets to open first).
    public Component operate(ServerLevel level, Vec3 hit) {
        int operator = nearestOperator(hit);
        if (operator < 0) {
            return message("no_handle");
        }
        Op op = FactoryMesh.operators(section(), tier().ordinal()).get(operator).op();
        if (op == Op.DISCONNECTOR) {
            Interlocks.Refusal refusal = Interlocks.disconnect(!breakers.isEmpty() && breakers.get(0).closed());
            if (refusal != null) {
                return Component.translatable("message." + ArcforgeTransmission.MODID + ".interlock." + refusal.key());
            }
            disconnector = !disconnector;
            done(level);
            return message(disconnector ? "disconnector_closed" : "disconnector_open");
        }
        if (op == Op.EARTH) {
            Interlocks.Refusal refusal = Interlocks.groundSwitch(!earth, lineLive(level));
            if (refusal != null) {
                return Component.translatable("message." + ArcforgeTransmission.MODID + ".interlock." + refusal.key());
            }
            earth = !earth;
            done(level);
            return message(earth ? "earth_closed" : "earth_open");
        }
        return toggle(level, breakerOf(operator));
    }

    // Opens a closed breaker, resets a tripped one, closes an open one (if its interlocks allow).
    public Component toggle(ServerLevel level, int index) {
        if (index < 0 || index >= breakers.size()) {
            return message("no_handle");
        }
        SectionBreaker breaker = breakers.get(index);
        switch (breaker.state()) {
            case CLOSED -> {
                breaker.setState(OpState.OPEN);
                done(level);
                return message("opened");
            }
            case TRIPPED -> {
                breaker.setState(OpState.OPEN);
                done(level);
                return message("reset");
            }
            default -> {
                Component refused = refuseClose(level);
                if (refused != null) {
                    return refused;
                }
                breaker.setState(OpState.CLOSED);
                done(level);
                return message("closed");
            }
        }
    }

    // Why a breaker here may not close, or null: the section isn't in a lineup; an incomer earthed; a main onto a faulted
    // or earthed bus; the two mains and the tie all closed at once (two sources in parallel) unless the config allows it.
    private @Nullable Component refuseClose(ServerLevel level) {
        String m = ArcforgeTransmission.MODID;
        if (!planned()) {
            return problemLine();
        }
        if (section() == Section.INCOMING && earth) {
            return Component.translatable("message." + m + ".interlock." + Interlocks.Refusal.LINE_GROUNDED.key());
        }
        GridData.Dimension dimension = GridData.of(level);
        GridManager.TickView view = GridManager.view(level);
        if (section() == Section.MAIN) {
            for (long node : nodes(dimension, farSide())) {
                if (view.grounded(dimension.graph(), node) || faulted(level, node)) {
                    return message("bus_faulted");
                }
            }
        }
        if ((section() == Section.MAIN || section() == Section.TIE) && !GridConfig.ALLOW_PARALLEL_SOURCES.getAsBoolean()) {
            int closed = 0, mainsAndTie = 0;
            for (SectionBlockEntity s : lineup(level)) {
                if (s.section() == Section.MAIN || s.section() == Section.TIE) {
                    mainsAndTie++;
                    closed += s != this && !s.breakers.isEmpty() && s.breakers.get(0).closed() ? 1 : 0;
                }
            }
            if (mainsAndTie == 3 && closed == 2) {
                return message("parallel");
            }
        }
        return null;
    }

    // Whether a node's network carries a fault (fault current on any of its links).
    private static boolean faulted(ServerLevel level, long node) {
        GridData.Dimension dimension = GridData.of(level);
        GridNetwork network = dimension.graph().networkOf(node);
        if (network == null) {
            return false;
        }
        GridManager.TickView view = GridManager.view(level);
        for (GridEdge edge : network.edges()) {
            if (view.faulted(edge.id())) {
                return true;
            }
        }
        return false;
    }

    private boolean lineLive(ServerLevel level) {
        GridData.Dimension dimension = GridData.of(level);
        for (long node : nodes(dimension, 0)) {
            if (GridManager.view(level).live(dimension.graph(), node)) {
                return true;
            }
        }
        return false;
    }

    private void done(ServerLevel level) {
        setChanged();
        relink(level);
        level.playSound(null, worldPosition, SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 0.6F, 1.2F);
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
    }

    // A breaker trips on `amps` over `setting`: opens with an arc and a clunk.
    private void tripped(ServerLevel level, int index, double amps, double setting) {
        relink(level);
        GridArcs.arc(level, arcPoint(index), section() == Section.INCOMING && tier().ordinal() <= 1 ? ArcEvent.Kind.FUSE : ArcEvent.Kind.BREAKER,
                tier(), setting <= 0 ? 1 : amps / setting);
        setChanged();
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
    }

    // Where a breaker's arc shows: its handle.
    private Vec3 arcPoint(int index) {
        List<FactoryMesh.Operator> ops = FactoryMesh.operators(section(), tier().ordinal());
        int n = 0;
        for (FactoryMesh.Operator o : ops) {
            if (o.op() == Op.BREAKER || o.op() == Op.SWITCH) {
                if (n++ == index) {
                    return SectionGeometry.point(worldPosition, facing(), o.x(), o.y(), o.z() - 1);
                }
            }
        }
        return Vec3.atCenterOf(worldPosition);
    }

    // The incomer trips on its transformer's over-temperature (a shunt trip): opens, tripped.
    void overTemperature(ServerLevel level) {
        if (!breakers.isEmpty() && breakers.get(0).closed()) {
            breakers.get(0).setState(OpState.TRIPPED);
            tripped(level, 0, 1, 1);
        }
    }

    // Redstone: a rising signal into a breaker's block works it (as a hand on its handle would).
    private void redstone(ServerLevel level) {
        List<FactoryMesh.Operator> ops = FactoryMesh.operators(section(), tier().ordinal());
        int n = 0;
        for (FactoryMesh.Operator o : ops) {
            if (o.op() != Op.BREAKER && o.op() != Op.SWITCH) {
                continue;
            }
            int index = n++;
            BlockPos cell = SectionGeometry.world(worldPosition, facing(), new int[] { (int) Math.floor(o.x() / 16), (int) Math.floor(o.y() / 16), 0 });
            boolean on = level.hasNeighborSignal(cell);
            if (index < powered.length && on != powered[index]) {
                powered[index] = on;
                setChanged();
                if (on) {
                    toggle(level, index);
                }
            }
        }
    }

    // A comparator on a block of it reads the breaker in that block: 0 open or tripped, 1 to 15 with its load.
    public int comparator(BlockPos pos) {
        int[] cell = SectionGeometry.cellOf(section(), tier().ordinal(), worldPosition, facing(), pos);
        if (cell == null || level == null) {
            return 0;
        }
        List<FactoryMesh.Operator> ops = FactoryMesh.operators(section(), tier().ordinal());
        int n = 0, best = 0;
        for (FactoryMesh.Operator o : ops) {
            if (o.op() != Op.BREAKER && o.op() != Op.SWITCH) {
                continue;
            }
            int index = n++;
            boolean here = (int) Math.floor(o.y() / 16) == cell[1] && (int) Math.floor(o.x() / 16) == cell[0];
            if (here && index < loads.length && breakers.get(index).closed()) {
                best = Math.max(best, LineupRatings.comparator(loads[index]));
            }
        }
        return best;
    }

    // Each breaker's load last tick: its current over its setting.
    private double[] loads = new double[0];

    // --- The Wrench -----------------------------------------------------------------------------------------------

    // The Wrench on Configure: a feeder breaker's frame (the nearest handle's), a main's or tie's trip unit (Sneak picks
    // long-time, short-time or instantaneous; Configure steps it), a transformer's off-load tap (only with its incomer
    // open). Null for anything GridInteractions does (its labels).
    public @Nullable InteractionResult wrench(ItemStack stack, Level level, Player player, Vec3 hit) {
        if (WrenchItem.mode(stack) != WrenchMode.CONFIGURE) {
            // Sections don't turn or relabel: their lineup decides their conductors.
            return WrenchItem.mode(stack) == WrenchMode.DISMANTLE ? null : InteractionResult.PASS;
        }
        if (!(level instanceof ServerLevel server)) {
            return InteractionResult.SUCCESS;
        }
        boolean sneaking = player.isSecondaryUseActive();
        String m = ArcforgeTransmission.MODID;
        Component said;
        switch (section()) {
            case TRANSFORMER -> {
                SectionBlockEntity in = incomer(server);
                if (in != null && !in.breakers.isEmpty() && in.breakers.get(0).closed()) {
                    said = message("open_incomer");
                } else {
                    int taps = GridConfig.OFFLOAD_TAPS.getAsInt();
                    tap = tap >= taps ? -taps : tap + 1;
                    said = Component.translatable("message." + m + ".lineup.tap", String.format(java.util.Locale.ROOT, "%+.1f", tap * GridConfig.OFFLOAD_TAP_STEP
                            .getAsDouble() * 100));
                }
            }
            case FEEDER -> {
                int index = Math.max(0, breakerOf(nearestOperator(hit)));
                SectionBreaker breaker = breakers.get(index);
                breaker.setFrame(breaker.frame() + 1);
                said = Component.translatable("message." + m + ".lineup.frame", index + 1,
                        net.zagdrath.arcforgetransmission.grid.block.GridInteractions.format(LineupRatings.feederFrame(GridConfig.SETTINGS, groupTier(0),
                                breaker.frame())));
            }
            case MAIN, TIE, INCOMING -> {
                if (breakers.isEmpty() || section() == Section.INCOMING && tier().ordinal() <= 1) {
                    said = message("no_settings");
                } else {
                    SectionBreaker breaker = breakers.get(0);
                    if (sneaking) {
                        breaker.nextFocus();
                    } else {
                        breaker.stepFocus();
                    }
                    said = Component.translatable("message." + m + ".lineup.trip_unit", Component.translatable("trip_setting." + m + "."
                            + breaker.focus().name().toLowerCase(java.util.Locale.ROOT)), String.format(java.util.Locale.ROOT, "%.3g", breaker.focusValue()));
                }
            }
            default -> said = message("no_settings");
        }
        setChanged();
        player.sendOverlayMessage(said);
        level.playSound(null, worldPosition, SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.BLOCKS, 0.3F, 1.4F);
        return InteractionResult.SUCCESS;
    }

    private static Component message(String key) {
        return Component.translatable("message." + ArcforgeTransmission.MODID + ".lineup." + key);
    }

    private Component problemLine() {
        return Component.translatable("hud." + ArcforgeTransmission.MODID + ".lineup.problem",
                Component.translatable("hud." + ArcforgeTransmission.MODID + ".lineup." + (problem == null ? "unplanned" : problem.key())));
    }

    // --- The grid's tick ------------------------------------------------------------------------------------------

    @Override
    public void ports(ServerLevel level, GridData.Dimension dimension, List<GridManager.PortHandler> out) {}

    // A transformer section in a lineup: its coupling (up to its limit; its off-load tap), its winding's impedance set on
    // its links for this tick's solve.
    @Override
    public void couplings(ServerLevel level, GridData.Dimension dimension, List<Coupling> out) {
        if (section() != Section.TRANSFORMER || !planned()) {
            return;
        }
        long rating = LineupRatings.transformerRating(GridConfig.SETTINGS, tier(), GridConfig.LINEUP_TRANSFORMER_RATING.getAsDouble());
        VoltageTier secondary = LineupRatings.lower(tier());
        double r = LineupRatings.windingOhms(secondary, rating, GridConfig.LINEUP_RESISTANCE.getAsDouble());
        double x = LineupRatings.windingReactance(secondary, rating, GridConfig.LINEUP_IMPEDANCE.getAsDouble(), GridConfig.LINEUP_RESISTANCE.getAsDouble());
        for (long edge : linkEdges(dimension, 1, 2)) {
            GridEdge e = dimension.graph().edge(edge);
            if (e != null) {
                e.setImpedance(r, x);
            }
        }
        List<Long> primary = nodes(dimension, 0), second = nodes(dimension, 1);
        if (primary.size() == 3 && second.size() == 3) {
            out.add(new Coupling(worldPosition.asLong(), tier(), primary, secondary, List.of(second), rating * GridConfig.TRANSFORMER_LIMIT.getAsDouble(),
                    tap * GridConfig.OFFLOAD_TAP_STEP.getAsDouble(), true));
        }
    }

    // An HV or EHV incomer's closed earthing switch grounds its line.
    @Override
    public void grounded(ServerLevel level, GridData.Dimension dimension, Set<Long> out) {
        if (section() == Section.INCOMING && earth) {
            out.addAll(nodes(dimension, 0));
        }
    }

    // Each closed breaker's links, at its pickup (an incomer's fuses as fuses): the grid grades them with every other
    // protective device.
    @Override
    public void protective(ServerLevel level, GridData.Dimension dimension, Map<Long, FlowSolver.Protective> out) {
        for (int i = 0; i < breakers.size(); i++) {
            if (!linked(i)) {
                continue;
            }
            int[] j = joins(i);
            FlowSolver.Protective p = new FlowSolver.Protective(pickup(level, i), fused());
            for (long edge : linkEdges(dimension, j[0], j[1])) {
                out.put(edge, p);
            }
        }
    }

    private boolean fused() {
        return section() == Section.INCOMING && tier().ordinal() <= 1;
    }

    // A breaker's frame (A, at its group's tier) and its long-time pickup.
    private double frame(Level level, int index) {
        double share = GridConfig.LINEUP_TRANSFORMER_RATING.getAsDouble(), fan = GridConfig.FAN_RATING.getAsDouble();
        return switch (section()) {
            case FEEDER -> LineupRatings.feederFrame(GridConfig.SETTINGS, groupTier(0), breakers.get(index).frame());
            case INCOMING -> LineupRatings.incomerPickup(GridConfig.SETTINGS, tier(), transformed(level), share, GridConfig.TRANSFORMER_LIMIT.getAsDouble(), fan,
                    GridConfig.INCOMER_MARGIN.getAsDouble());
            default -> LineupRatings.mainFrame(GridConfig.SETTINGS, tier(), transformed(level), share, fan);
        };
    }

    private double pickup(Level level, int index) {
        return fused() ? frame(level, index) : breakers.get(index).pickup(frame(level, index));
    }

    @Override
    public void afterTick(ServerLevel level, GridData.Dimension dimension, GridManager.TickView view) {
        if (replan || level.getGameTime() >= nextPlan) {
            replan = false;
            nextPlan = level.getGameTime() + 20;
            plan(level);
        }
        if (loads.length != breakers.size()) {
            loads = new double[breakers.size()];
        }
        for (int i = 0; i < breakers.size(); i++) {
            SectionBreaker breaker = breakers.get(i);
            int[] j = joins(i);
            double most = 0;
            FlowSolver.Beyond beyond = FlowSolver.Beyond.NONE;
            for (long edge : linkEdges(dimension, j[0], j[1])) {
                double amps = view.amps(edge);
                if (amps > most) {
                    most = amps;
                    beyond = view.beyond(edge);
                }
            }
            double setting = pickup(level, i);
            loads[i] = setting > 0 ? most / setting : 0;
            if (!linked(i)) {
                continue;
            }
            boolean trip = fused() ? breaker.tickFuse(most, setting, GridConfig.FUSE_MELT.getAsDouble(), GridConfig.FUSE_COOLING.getAsDouble())
                    : breaker.tickBreaker(most, beyond, frame(level, i), section() == Section.FEEDER ? GridConfig.FEEDER_DIAL.getAsDouble()
                            : GridConfig.MAIN_DIAL.getAsDouble(), GridConfig.BREAKER_TRIP_TICKS.getAsInt(), GridConfig.GRADING_INTERVAL.getAsInt(),
                            GridConfig.FUSE_MELT.getAsDouble());
            if (trip) {
                tripped(level, i, most, setting);
            }
        }
        if (section() == Section.TRANSFORMER && planned()) {
            thermal(level, view);
        }
        if (level.getGameTime() % 10 == 0) {
            hud(level, dimension, view);
        }
        int reading = comparator(worldPosition);
        if (reading != lastComparator) {
            lastComparator = reading;
            level.updateNeighbourForOutputSignal(worldPosition, getBlockState().getBlock());
        }
    }

    // Its load (a share of its rating, from the power through its coupling), its fans past their threshold, and its heat:
    // rising past its fan rating, falling under it, at a step that takes overtempTicks from cold to its trip temperature;
    // there it trips its incomer.
    private double load;

    private void thermal(ServerLevel level, GridManager.TickView view) {
        long rating = LineupRatings.transformerRating(GridConfig.SETTINGS, tier(), GridConfig.LINEUP_TRANSFORMER_RATING.getAsDouble());
        load = Math.abs(view.result().couplingFlow().getOrDefault(worldPosition.asLong(), 0.0)) / Math.max(1, rating);
        boolean wereOn = fans;
        fans = load > GridConfig.FAN_THRESHOLD.getAsDouble();
        double step = 1.0 / GridConfig.OVERTEMP_TICKS.getAsInt();
        heat = load > GridConfig.FAN_RATING.getAsDouble() ? heat + step : Math.max(0, heat - step);
        if (heat >= 1.0) {
            heat = 1.0;
            SectionBlockEntity in = incomer(level);
            if (in != null) {
                in.overTemperature(level);
            }
        }
        if (wereOn != fans) {
            setChanged();
        }
    }

    public double load() {
        return load;
    }

    public boolean fans() {
        return fans;
    }

    // --- The lineup's HUD -----------------------------------------------------------------------------------------

    // Each row of the lineup's HUD, as numbers: what (Hud.Kind), which (its number among its kind), its state (OpState
    // ordinal, -1 none), its tier, its voltage (thousandths of its tier's), its current (centiamps), its power (FE/t), its
    // load (thousandths of its setting or rating), a transformer's temperature (thousandths of its trip point) and fans.
    public static final int HUD_FIELDS = 10;

    private void hud(ServerLevel level, GridData.Dimension dimension, GridManager.TickView view) {
        List<Long> rows = new ArrayList<>();
        if (planned()) {
            int incomers = 0, transformers = 0, mains = 0, feeders = 0;
            for (SectionBlockEntity s : lineup(level)) {
                switch (s.section()) {
                    case INCOMING -> s.row(rows, Hud.INCOMER, ++incomers, 0, view, dimension);
                    case TRANSFORMER -> {
                        transformers++;
                        rows.addAll(List.of((long) Hud.TRANSFORMER, (long) transformers, -1L, (long) s.tier().ordinal(), 0L, 0L,
                                Math.round(s.load * LineupRatings.transformerRating(GridConfig.SETTINGS, s.tier(), GridConfig.LINEUP_TRANSFORMER_RATING
                                        .getAsDouble())), Math.round(s.load * 1000), Math.round(s.heat * 1000), s.fans ? 1L : 0L));
                    }
                    case MAIN -> s.row(rows, Hud.MAIN, ++mains, 0, view, dimension);
                    case TIE -> s.row(rows, Hud.TIE, 1, 0, view, dimension);
                    case FEEDER -> {
                        for (int i = 0; i < s.breakers.size(); i++) {
                            s.row(rows, Hud.FEEDER, ++feeders, i, view, dimension);
                        }
                    }
                }
            }
        }
        hud = rows.stream().mapToLong(Long::longValue).toArray();
        hudProblem = problem == null ? "" : problem.key();
        if (!java.util.Arrays.equals(hud, sentHud) || !hudProblem.equals(sentProblem)) {
            sentHud = hud;
            sentProblem = hudProblem;
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        }
    }

    // One breaker's row: its state, the voltage on its far side, its current and power, and its load.
    private void row(List<Long> rows, int kind, int number, int index, GridManager.TickView view, GridData.Dimension dimension) {
        if (index >= breakers.size()) {
            return;
        }
        int[] j = joins(index);
        double amps = 0;
        for (long edge : linkEdges(dimension, j[0], j[1])) {
            amps = Math.max(amps, view.result().amps(edge));
        }
        List<Long> far = nodes(dimension, j[1]);
        double volts = far.isEmpty() ? 0 : view.volts(dimension.graph(), far.get(0));
        VoltageTier tier = groupTier(j[1]);
        long power = Math.round(amps * tier.volts() * volts);
        rows.addAll(List.of((long) kind, (long) number, (long) breakers.get(index).state().ordinal(), (long) tier.ordinal(), Math.round(volts * 1000),
                Math.round(amps * 100), power, Math.round((index < loads.length ? loads[index] : 0) * 1000), 0L, 0L));
    }

    // The client's copy.
    public long[] hud() {
        return hud;
    }

    public String hudProblem() {
        return hudProblem;
    }

    // What the HUD's rows are.
    public static final class Hud {
        public static final int INCOMER = 0, TRANSFORMER = 1, MAIN = 2, TIE = 3, FEEDER = 4;

        private Hud() {}
    }

    // --- Saving ---------------------------------------------------------------------------------------------------

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        List<Integer> flat = new ArrayList<>();
        for (SectionBreaker breaker : breakers) {
            for (int v : breaker.save()) {
                flat.add(v);
            }
        }
        output.store("breakers", Codec.INT_STREAM, flat.stream().mapToInt(Integer::intValue));
        output.store("disconnector", Codec.BOOL, disconnector);
        output.store("earth", Codec.BOOL, earth);
        output.store("tap", Codec.INT, tap);
        output.store("heat", Codec.DOUBLE, heat);
        int bits = 0;
        for (int i = 0; i < powered.length; i++) {
            bits |= powered[i] ? 1 << i : 0;
        }
        output.store("powered", Codec.INT, bits);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        input.read("breakers", Codec.INT_STREAM).ifPresent(stream -> {
            int[] flat = stream.toArray();
            int each = new SectionBreaker().save().length;
            for (int i = 0; i < breakers.size() && (i + 1) * each <= flat.length; i++) {
                breakers.get(i).load(java.util.Arrays.copyOfRange(flat, i * each, (i + 1) * each));
            }
        });
        disconnector = input.read("disconnector", Codec.BOOL).orElse(true);
        earth = input.read("earth", Codec.BOOL).orElse(false);
        tap = input.read("tap", Codec.INT).orElse(0);
        heat = input.read("heat", Codec.DOUBLE).orElse(0.0);
        int bits = input.read("powered", Codec.INT).orElse(0);
        for (int i = 0; i < powered.length; i++) {
            powered[i] = (bits & 1 << i) != 0;
        }
        input.read("hud_rows", Codec.LONG_STREAM).ifPresent(stream -> hud = stream.toArray());
        input.read("hud_problem", Codec.STRING).ifPresent(p -> hudProblem = p);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.putLongArray("hud_rows", hud);
        tag.putString("hud_problem", hudProblem);
        return tag;
    }

    public AABB getRenderBox() {
        AABB box = new AABB(worldPosition);
        for (BlockPos cell : SectionGeometry.worldCells(section(), tier().ordinal(), worldPosition, facing())) {
            box = box.minmax(new AABB(cell));
        }
        return withWires(box);
    }
}
