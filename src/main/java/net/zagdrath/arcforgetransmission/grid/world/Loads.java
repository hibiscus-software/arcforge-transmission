/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.world;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;

// Machines as tiered loads, the mirror of Generators: the data map arcforgetransmission:load (on blocks) gives a machine
// the tier it takes and its rating (FE/t), or "auto" to read its own energy capacity (it never takes more than its own
// input limit either). A Cable Termination or Pin Insulator on a mapped machine delivers only at the machine's tier: at a
// lower one it delivers nothing (WRONG_TIER_LOAD), at a higher one it's an overvoltage. A machine may have several feeds of
// its tier: they share its rating, each still limited to its own circuit. A block without an entry is as it always was
// (FE at LV only). Arcforge's machines are mapped out of the box (data/arcforgetransmission/data_maps/block/load.json); a
// datapack can map any mod's.
public final class Loads {
    public static final DataMapType<Block, Generators.Rating> LOAD = DataMapType.builder(Identifier.fromNamespaceAndPath(ArcforgeTransmission.MODID, "load"),
            Registries.BLOCK, Generators.Rating.CODEC).build();

    // A mapped machine at a block: its name, tier and rating (FE/t), and what tells two blocks of one machine apart (its
    // block entity, the multiblock's master).
    public record Machine(Component name, VoltageTier tier, int rating, Object key) {}

    // Entries set from code over the data map's (game tests, which ship no datapack of their own).
    private static final Map<Block, Generators.Rating> OVERRIDES = new ConcurrentHashMap<>();

    // Each machine's feeds and the FE delivered to it, this tick and last.
    private static final class Ledger {
        long tick = Long.MIN_VALUE;
        Set<BlockPos> feeds = new HashSet<>(), lastFeeds = Set.of();
        long delivered, lastDelivered;
    }

    private static final Map<Object, Ledger> LEDGERS = new WeakHashMap<>();

    private Loads() {}

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener((RegisterDataMapTypesEvent event) -> event.register(LOAD));
    }

    // Maps a block as a load from code (null to drop it), over the data map.
    public static void override(Block block, Generators.@Nullable Rating rating) {
        if (rating == null) {
            OVERRIDES.remove(block);
        } else {
            OVERRIDES.put(block, rating);
        }
    }

    public static Generators.@Nullable Rating rating(BlockState state) {
        Generators.Rating rating = OVERRIDES.get(state.getBlock());
        return rating != null ? rating : BuiltInRegistries.BLOCK.wrapAsHolder(state.getBlock()).getData(LOAD);
    }

    // The mapped machine a block is part of (its energy capability `handler`, for "auto"), or null.
    public static @Nullable Machine at(Level level, BlockPos pos, @Nullable EnergyHandler handler) {
        BlockState state = level.getBlockState(pos);
        Generators.Rating rating = rating(state);
        if (rating == null) {
            return null;
        }
        BlockEntity entity = Generators.entityOf(level, pos, state);
        int most = rating.nameplate().isPresent() ? rating.nameplate().getAsInt()
                : handler == null ? 0 : (int) Math.max(1, Math.min(Integer.MAX_VALUE, handler.getCapacityAsLong()));
        Object key = entity != null ? entity : level.dimension().identifier() + "@" + pos.asLong();
        return new Machine(Generators.name(entity, state.getBlock()), rating.tier(), most, key);
    }

    private static Ledger ledger(Level level, Machine machine) {
        synchronized (LEDGERS) {
            Ledger ledger = LEDGERS.computeIfAbsent(machine.key(), k -> new Ledger());
            long now = level.getGameTime();
            if (ledger.tick != now) {
                boolean previous = ledger.tick == now - 1;
                ledger.lastFeeds = previous ? ledger.feeds : Set.of();
                ledger.lastDelivered = previous ? ledger.delivered : 0;
                ledger.feeds = new HashSet<>();
                ledger.delivered = 0;
                ledger.tick = now;
            }
            return ledger;
        }
    }

    // A feed's share of its machine's rating this tick (FE/t): the rating split among its feeds (this tick's and last's).
    public static double share(Level level, Machine machine, BlockPos feed) {
        Ledger ledger = ledger(level, machine);
        synchronized (LEDGERS) {
            ledger.feeds.add(feed.immutable());
            Set<BlockPos> all = new HashSet<>(ledger.feeds);
            all.addAll(ledger.lastFeeds);
            return machine.rating() / (double) Math.max(1, all.size());
        }
    }

    // FE a feed delivered to its machine this tick.
    public static void delivered(Level level, Machine machine, int fe) {
        Ledger ledger = ledger(level, machine);
        synchronized (LEDGERS) {
            ledger.delivered += fe;
        }
    }

    // What all its feeds delivered to a machine last tick (FE/t): its draw.
    public static long draw(Level level, Machine machine) {
        Ledger ledger = ledger(level, machine);
        synchronized (LEDGERS) {
            return ledger.lastDelivered;
        }
    }
}
