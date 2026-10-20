/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.factory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.grid.ConnectionRules;
import net.zagdrath.arcforgetransmission.grid.GridEdge;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.world.GridConfig;
import net.zagdrath.arcforgetransmission.grid.world.GridData;
import net.zagdrath.arcforgetransmission.grid.world.GridManager;

// Tray cables in the world: laying one between two Cable Terminations of its tier (the shortest path along connected
// tray, a free drop of up to factory.tray.maxFreeDrop blocks at each end; with no tray, only a drop that short), the tray's
// rules (each block's fill in slots, LV kept apart from MV and above, HV and EHV only in heavy-duty tray), derating (each
// cable rated for the most crowded tray block on its path), and taking cables up (pulled, cut, a tray block or a
// termination broken, burnt out), dropping their items. Every change tells the tray blocks it touches what they hold.
public final class CableNetwork {
    private CableNetwork() {}

    // --- The rules -------------------------------------------------------------------------------------------------

    // A tray block's slots, by its kind.
    public static int fill(CableTrayBlock.Kind kind) {
        return switch (kind) {
            case STANDARD -> GridConfig.TRAY_FILL_STANDARD.getAsInt();
            case WIDE -> GridConfig.TRAY_FILL_WIDE.getAsInt();
            case HEAVY -> GridConfig.TRAY_FILL_HEAVY.getAsInt();
        };
    }

    // The share of its ampacity a cable keeps with `cables` cables in a tray block.
    public static double derating(int cables) {
        if (cables <= GridConfig.DERATE_FULL_UP_TO.getAsInt()) {
            return 1.0;
        }
        return cables <= GridConfig.DERATE_MID_UP_TO.getAsInt() ? GridConfig.DERATE_MID.getAsDouble() : GridConfig.DERATE_LOW.getAsDouble();
    }

    // Why a cable of a tier can't go in a tray block holding `others` (null if it can): too full, LV beside MV and up,
    // HV and EHV outside heavy-duty tray.
    public static @Nullable String refusal(CableTrayBlock.Kind kind, VoltageTier tier, List<CableData.Cable> others) {
        if (GridConfig.SEGREGATE_TIERS.getAsBoolean()) {
            if (tier.ordinal() >= 2 && kind != CableTrayBlock.Kind.HEAVY) {
                return "heavy_only";
            }
            for (CableData.Cable other : others) {
                if ((tier == VoltageTier.LV) != (other.tier() == VoltageTier.LV)) {
                    return "segregated";
                }
            }
        }
        int used = GridConfig.cableSlots(tier);
        for (CableData.Cable other : others) {
            used += GridConfig.cableSlots(other.tier());
        }
        return used > fill(kind) ? "full" : null;
    }

    // --- Laying --------------------------------------------------------------------------------------------------

    // A cable's route: the tray blocks in order, and its length (the drops at each end plus a block a tray block); null
    // when there's none (no tray within a drop of both ends, and the ends further apart than a drop).
    public record Route(List<BlockPos> path, double length) {}

    public static @Nullable Route route(ServerLevel level, Vec3 from, Vec3 to) {
        int drop = GridConfig.MAX_FREE_DROP.getAsInt();
        double weight = GridConfig.DROP_WEIGHT.getAsDouble(), direct = from.distanceTo(to);
        Route best = direct <= drop + 1.0E-6 ? new Route(List.of(), Math.max(1, direct)) : null;
        double bestCost = best == null ? Double.MAX_VALUE : weight * direct;
        // Dijkstra along connected tray from every tray block within a drop of the first end. A free drop costs more than
        // tray (cable goes into the nearest tray, not across the room to a far one), though its length is what it is.
        Map<BlockPos, Double> cost = new HashMap<>(), dropped = new HashMap<>();
        Map<BlockPos, BlockPos> via = new HashMap<>();
        PriorityQueue<Object[]> queue = new PriorityQueue<>((a, b) -> Double.compare((double) a[0], (double) b[0]));
        for (BlockPos tray : traysNear(level, from, drop)) {
            double d = from.distanceTo(Vec3.atCenterOf(tray));
            cost.put(tray, weight * d);
            dropped.put(tray, d);
            queue.add(new Object[] { weight * d, tray });
        }
        while (!queue.isEmpty()) {
            Object[] head = queue.poll();
            double c = (double) head[0];
            BlockPos at = (BlockPos) head[1];
            if (c > cost.getOrDefault(at, Double.MAX_VALUE) + 1.0E-9 || c > 4096) {
                continue;
            }
            double end = Vec3.atCenterOf(at).distanceTo(to);
            if (end <= drop + 1.0E-6 && c + weight * end < bestCost - 1.0E-9) {
                List<BlockPos> path = new ArrayList<>();
                for (BlockPos p = at; p != null; p = via.get(p)) {
                    path.add(0, p);
                }
                bestCost = c + weight * end;
                best = new Route(path, dropped.get(at) + (path.size() - 1) + end);
            }
            for (Direction side : CableTrayBlock.joined(level.getBlockState(at))) {
                BlockPos next = at.relative(side);
                if (level.isLoaded(next) && level.getBlockState(next).getBlock() instanceof CableTrayBlock && c + 1 < cost.getOrDefault(next, Double.MAX_VALUE)) {
                    cost.put(next, c + 1);
                    dropped.put(next, dropped.get(at));
                    via.put(next, at);
                    queue.add(new Object[] { c + 1, next });
                }
            }
        }
        return best;
    }

    private static List<BlockPos> traysNear(ServerLevel level, Vec3 at, int drop) {
        List<BlockPos> out = new ArrayList<>();
        BlockPos centre = BlockPos.containing(at);
        for (BlockPos p : BlockPos.betweenClosed(centre.offset(-drop, -drop, -drop), centre.offset(drop, drop, drop))) {
            if (level.isLoaded(p) && level.getBlockState(p).getBlock() instanceof CableTrayBlock && at.distanceTo(Vec3.atCenterOf(p)) <= drop + 1.0E-6) {
                out.add(p.immutable());
            }
        }
        return out;
    }

    // Items a cable `length` blocks long takes.
    public static int items(double length) {
        return (int) Math.max(1, Math.ceil(length / GridConfig.CABLE_BLOCKS_PER_ITEM.getAsInt() - 1.0E-9));
    }

    // Lays a cable of a tier from one termination to another (`have` items to spend, unlimited for a creative player):
    // its route, its rules, its three conductors. Null when laid; otherwise why not.
    public static @Nullable Component lay(ServerLevel level, CableTerminationBlockEntity a, CableTerminationBlockEntity b, VoltageTier tier, int have,
            int[] spent) {
        String m = ArcforgeTransmission.MODID;
        if (a == b) {
            return Component.translatable("message." + m + ".cable.same_end");
        }
        if (a.tier() != tier || b.tier() != tier) {
            return Component.translatable("message." + m + ".cable.wrong_tier", tier.name());
        }
        Route route = route(level, a.cableEnd(), b.cableEnd());
        if (route == null) {
            return Component.translatable("message." + m + ".cable.no_tray", GridConfig.MAX_FREE_DROP.getAsInt());
        }
        CableData.Dimension cables = CableData.of(level);
        for (BlockPos tray : route.path()) {
            CableTrayBlock.Kind kind = ((CableTrayBlock) level.getBlockState(tray).getBlock()).kind();
            String why = refusal(kind, tier, cables.inTray(tray));
            if (why != null) {
                return Component.translatable("message." + m + ".cable." + why, tray.getX(), tray.getY(), tray.getZ());
            }
        }
        int items = items(route.length());
        if (items > have) {
            return Component.translatable("message." + m + ".cable.too_few", items);
        }
        GridData.Dimension grid = GridData.of(level);
        ConnectionRules.Refusal[] why = new ConnectionRules.Refusal[1];
        List<Long> edges = GridManager.connectCircuit(level, a.nodes(grid), b.nodes(grid), GridEdge.Kind.CABLE, route.length(), 1, 1.0, why);
        if (edges.isEmpty()) {
            return Component.translatable("message." + m + ".wire." + (why[0] == null ? ConnectionRules.Refusal.MISSING : why[0]).key());
        }
        cables.put(new CableData.Cable(cables.newId(), tier, a.getBlockPos(), b.getBlockPos(), route.path(), route.length(), items, edges));
        changed(level, route.path(), a.getBlockPos(), b.getBlockPos());
        spent[0] = items;
        return null;
    }

    // --- Taking up -----------------------------------------------------------------------------------------------

    // Whether a cable's conductors are live (it won't be cut then).
    public static boolean energized(ServerLevel level, CableData.Cable cable) {
        GridData.Dimension grid = GridData.of(level);
        GridManager.TickView view = GridManager.view(level);
        for (long id : cable.edges()) {
            GridEdge edge = grid.graph().edge(id);
            if (edge != null && view.live(grid.graph(), edge.a())) {
                return true;
            }
        }
        return false;
    }

    // Takes a cable up: its conductors off the grid, its items dropped at `drop` (none for a creative player), the trays
    // and terminations told.
    public static void remove(ServerLevel level, CableData.Cable cable, @Nullable Vec3 drop) {
        CableData.Dimension cables = CableData.of(level);
        cables.remove(cable.id());
        GridManager.disconnect(level, cable.edges());
        if (drop != null) {
            Block.popResource(level, BlockPos.containing(drop), new ItemStack(FactoryContent.CABLES.get(cable.tier()).get(), cable.items()));
        }
        changed(level, cable.path(), cable.from(), cable.to());
    }

    // The cables through a tray block that's gone: each dropped there (unless a creative player broke it).
    public static void trayGone(ServerLevel level, BlockPos tray) {
        for (CableData.Cable cable : CableData.of(level).inTray(tray)) {
            remove(level, cable, GridManager.dropsAt(level, tray) ? Vec3.atCenterOf(tray) : null);
        }
    }

    // The cables ending at a termination that's gone.
    public static void terminationGone(ServerLevel level, BlockPos termination) {
        for (CableData.Cable cable : CableData.of(level).atEnd(termination)) {
            remove(level, cable, GridManager.dropsAt(level, termination) ? Vec3.atCenterOf(termination) : null);
        }
    }

    // A conductor burnt out: the whole cable is gone (its Burnt Wire already dropped).
    public static void burnt(ServerLevel level, long edge) {
        CableData.Cable cable = CableData.of(level).ofEdge(edge);
        if (cable != null) {
            remove(level, cable, null);
        }
    }

    // --- Derating and the trays' view --------------------------------------------------------------------------------

    // The cables through some tray blocks changed: every cable through them is derated again (the lowest along its path),
    // and every tray block they touch shows what it holds.
    static void changed(ServerLevel level, List<BlockPos> trays, BlockPos... ends) {
        CableData.Dimension cables = CableData.of(level);
        Set<Long> affected = new LinkedHashSet<>();
        Set<BlockPos> touched = new LinkedHashSet<>(trays);
        for (BlockPos tray : trays) {
            cables.inTray(tray).forEach(c -> affected.add(c.id()));
        }
        GridData.Dimension grid = GridData.of(level);
        for (long id : affected) {
            CableData.Cable cable = cables.cable(id);
            double derate = 1.0;
            for (BlockPos tray : cable.path()) {
                derate = Math.min(derate, derating(cables.inTray(tray).size()));
                touched.add(tray);
            }
            for (long edge : cable.edges()) {
                GridEdge e = grid.graph().edge(edge);
                if (e != null) {
                    e.setDerate(derate);
                }
            }
        }
        for (BlockPos tray : touched) {
            if (level.isLoaded(tray) && level.getBlockEntity(tray) instanceof CableTrayBlockEntity entity) {
                entity.show(level);
            }
        }
        for (BlockPos end : ends) {
            if (level.isLoaded(end) && level.getBlockEntity(end) instanceof CableTerminationBlockEntity termination) {
                termination.showDrops(level);
                GridManager.display(level, termination, true);
            }
        }
    }

    // A cable's derating and the most cables in any tray block on its path (its cause).
    public static double derate(ServerLevel level, CableData.Cable cable) {
        double derate = 1.0;
        for (BlockPos tray : cable.path()) {
            derate = Math.min(derate, derating(CableData.of(level).inTray(tray).size()));
        }
        return derate;
    }

    public static int crowd(ServerLevel level, CableData.Cable cable) {
        int most = 0;
        for (BlockPos tray : cable.path()) {
            most = Math.max(most, CableData.of(level).inTray(tray).size());
        }
        return most;
    }

    // The most current on a cable's conductors last tick, A.
    public static double amps(ServerLevel level, CableData.Cable cable) {
        double most = 0;
        for (long edge : cable.edges()) {
            most = Math.max(most, GridManager.lastAmps(level).getOrDefault(edge, 0.0));
        }
        return most;
    }

    // Where each cable through a tray block lies in it: its slot (from the first rail), its tier, and the sides it comes in
    // and goes out by (-1 for a free drop, with the drop's far end, relative to the block's corner).
    public record Lie(int tier, int slot, int in, int out, float dx, float dy, float dz) {}

    public static List<Lie> lies(ServerLevel level, BlockPos tray) {
        List<Lie> out = new ArrayList<>();
        int slot = 0;
        for (CableData.Cable cable : CableData.of(level).inTray(tray)) {
            int at = cable.path().indexOf(tray);
            BlockPos prev = at > 0 ? cable.path().get(at - 1) : null, next = at + 1 < cable.path().size() ? cable.path().get(at + 1) : null;
            int in = prev == null ? -1 : side(tray, prev), outSide = next == null ? -1 : side(tray, next);
            Vec3 drop = Vec3.ZERO;
            if (prev == null || next == null) {
                BlockPos end = prev == null ? cable.from() : cable.to();
                if (level.getBlockEntity(end) instanceof CableTerminationBlockEntity termination) {
                    drop = termination.cableEnd().subtract(tray.getX(), tray.getY(), tray.getZ());
                }
            }
            out.add(new Lie(cable.tier().ordinal(), slot, in, outSide, (float) drop.x, (float) drop.y, (float) drop.z));
            slot += GridConfig.cableSlots(cable.tier());
        }
        return out;
    }

    private static int side(BlockPos from, BlockPos to) {
        for (Direction dir : Direction.values()) {
            if (from.relative(dir).equals(to)) {
                return dir.ordinal();
            }
        }
        return -1;
    }

    // The cables straight from a termination to another, no tray between (a drop): their far glands.
    public static List<Vec3> directFrom(ServerLevel level, BlockPos termination) {
        List<Vec3> out = new ArrayList<>();
        for (CableData.Cable cable : CableData.of(level).atEnd(termination)) {
            if (cable.path().isEmpty() && cable.from().equals(termination) && level.getBlockEntity(cable.to()) instanceof CableTerminationBlockEntity far) {
                out.add(far.cableEnd());
            }
        }
        return out;
    }
}
