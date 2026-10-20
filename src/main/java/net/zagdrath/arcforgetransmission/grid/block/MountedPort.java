/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.block;

import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import net.zagdrath.arcforgetransmission.grid.FaultEvent;
import net.zagdrath.arcforgetransmission.grid.FlowSolver;
import net.zagdrath.arcforgetransmission.grid.GridFaults;
import net.zagdrath.arcforgetransmission.grid.Port;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.world.GridConfig;
import net.zagdrath.arcforgetransmission.grid.world.GridData;
import net.zagdrath.arcforgetransmission.grid.world.GridManager;
import net.zagdrath.arcforgetransmission.grid.world.Generators;
import net.zagdrath.arcforgetransmission.grid.world.Loads;
import net.zagdrath.arcforgetransmission.grid.world.VoltageRatings;

// A whole circuit of conductors mounted on a block, as a port: the block's FE in or out at up to the tier's voltage times
// the current limit (a block's Pin Insulators together, or one Cable Termination). Its mode and limit are the port's; its
// status goes back to whatever mounted it. The rules are the same for both:
//  - a foreign FE block may feed or be fed only at LV; switched on at a higher tier it's an overvoltage
//    (GridConfig.OVERVOLTAGE: broken, destroyed or blown up);
//  - a mapped power plant (Generators) gives a mounted port FE only at its own tier, up to its nameplate (refused below
//    it, an overvoltage above);
//  - a mapped machine (Loads) takes FE only at its own tier: at a lower one the port says WRONG_TIER_LOAD and delivers
//    nothing, at a higher one it's an overvoltage; and at most its share of its rating among all its feeds.
public final class MountedPort implements GridManager.PortHandler {
    // What mounted the port: told how each tick went.
    public interface Holder {
        void reported(FlowSolver.PortStatus status, PortHud.@Nullable Generator generator, PortHud.@Nullable Load load, double flow);
    }

    // The mounted block's energy capability, cached (rebuilt if it moves or its face changes).
    public static final class Mount {
        private @Nullable BlockCapabilityCache<EnergyHandler, @Nullable Direction> cache;

        public @Nullable EnergyHandler handler(ServerLevel level, BlockPos pos, Direction side) {
            if (cache == null || !cache.pos().equals(pos) || cache.context() != side) {
                cache = BlockCapabilityCache.create(Capabilities.Energy.BLOCK, level, pos, side);
            }
            return level.isLoaded(pos) ? cache.getCapability() : null;
        }
    }

    private final List<? extends Holder> holders;
    private final VoltageTier tier;
    private final List<Long> nodes;
    private final @Nullable EnergyHandler handler;
    private final PinInsulatorMode mode;
    private final double limitAmps;
    private final Generators.@Nullable Plant refusedPlant;
    private final ServerLevel level;
    private final Loads.@Nullable Machine machine;
    private final boolean wrongLoad;
    // The most it may deliver this tick (its share of a mapped machine's rating), FE/t.
    private final double cap;

    private MountedPort(ServerLevel level, List<? extends Holder> holders, VoltageTier tier, List<Long> nodes, @Nullable EnergyHandler handler,
            PinInsulatorMode mode, double limitAmps, Generators.@Nullable Plant refusedPlant, Loads.@Nullable Machine machine, boolean wrongLoad, double cap) {
        this.level = level;
        this.machine = machine;
        this.wrongLoad = wrongLoad;
        this.cap = cap;
        this.holders = holders;
        this.tier = tier;
        this.nodes = nodes;
        this.handler = handler;
        this.mode = mode;
        this.limitAmps = limitAmps;
        this.refusedPlant = refusedPlant;
    }

    // The port for conductors (nodes) of a tier mounted on `mount`, from its face `side`; added to out, unless switched on
    // against a block it overvoltages (then that happens instead).
    public static void make(ServerLevel level, GridData.Dimension dimension, List<? extends Holder> holders, VoltageTier tier, List<Long> nodes, BlockPos mount,
            Direction side, Mount cache, PinInsulatorMode mode, double limitAmps, List<GridManager.PortHandler> out) {
        if (nodes.isEmpty()) {
            return;
        }
        EnergyHandler handler = cache.handler(level, mount, side);
        // Overvoltage once it's live: switched on, against a block that can't take the tier, with a wire on any conductor.
        boolean wired = nodes.stream().anyMatch(id -> dimension.graph().networkOf(id) != null);
        // A mapped machine takes its own tier and below (below, nothing), and a mapped plant stands its own tier (it's
        // refused, below); any other foreign block LV only.
        Loads.Machine machine = handler == null ? null : Loads.at(level, mount, handler);
        Generators.Plant plant = handler == null ? null : Generators.at(level, mount);
        boolean over = machine != null ? tier.ordinal() > machine.tier().ordinal()
                : plant != null ? tier.ordinal() > plant.tier().ordinal() : !VoltageRatings.accepts(level, mount, tier);
        if (mode != PinInsulatorMode.OFF && wired && handler != null && over && GridConfig.OVERVOLTAGE.get() != GridConfig.Overvoltage.NONE) {
            overvoltage(level, mount, nodes.get(0), tier);
            return;
        }
        // A mapped plant gives a mounted port FE only at its own tier (as its unit transformer does), up to its nameplate;
        // at a lower one, nothing.
        if (plant != null && plant.tier() != tier) {
            out.add(new MountedPort(level, holders, tier, nodes, null, mode, limitAmps, plant, null, false, 0));
            return;
        }
        if (plant != null) {
            out.add(new MountedPort(level, holders, tier, nodes, handler, mode, limitAmps, null, null, false, plant.nameplate()));
            return;
        }
        if (machine != null && tier != machine.tier()) {
            out.add(new MountedPort(level, holders, tier, nodes, null, mode, limitAmps, null, machine, true, 0));
            return;
        }
        double cap = machine == null ? Double.POSITIVE_INFINITY : Loads.share(level, machine, mount.relative(side));
        out.add(new MountedPort(level, holders, tier, nodes, handler, mode, limitAmps, null, machine, false, cap));
    }

    public static void overvoltage(ServerLevel level, BlockPos pos, long node, VoltageTier tier) {
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

    // The most FE/t it moves: the tier's voltage times its limit, and its share of a mapped machine's rating, no more than
    // an int holds.
    private int maxPower() {
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, Math.min(cap, Math.floor(limitAmps * tier.volts()))));
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
        return handler == null || !mode.takes() ? 0 : handler.insert(Math.min(fe, maxPower()), tx);
    }

    @Override
    public void moved(int drawn, int delivered) {
        if (machine != null && !wrongLoad && delivered > 0) {
            Loads.delivered(level, machine, delivered);
        }
    }

    @Override
    public void report(FlowSolver.PortStatus status, double drawn, double delivered) {
        // Says what's wrong rather than looking wired up and idle: a plant or machine of another tier, or no FE block.
        FlowSolver.PortStatus shown = mode == PinInsulatorMode.OFF ? status
                : refusedPlant != null ? FlowSolver.PortStatus.WRONG_TIER_GENERATOR
                : wrongLoad ? FlowSolver.PortStatus.WRONG_TIER_LOAD
                : handler == null && status != FlowSolver.PortStatus.UNWIRED ? FlowSolver.PortStatus.NO_FE_BLOCK : status;
        PortHud.Generator generator = PortHud.Generator.of(refusedPlant, true);
        PortHud.Load load = PortHud.Load.of(level, machine, wrongLoad);
        for (Holder holder : holders) {
            holder.reported(shown, generator, load, drawn > 0 ? drawn : delivered);
        }
    }
}
