/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.factory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;

// Every tray cable of every dimension, saved once for the server (beside the grid's own GridData, which holds the
// cables' conductors as edges): each cable's tier, its two terminations, the tray blocks it lies in (in order, from its
// first termination), its length (blocks), the items it took, and its three conductor edges. What the tray blocks show,
// their fill and the cables' derating are all worked out from it (CableNetwork).
public class CableData extends SavedData {
    // One cable as saved.
    public record Cable(long id, VoltageTier tier, BlockPos from, BlockPos to, List<BlockPos> path, double length, int items, List<Long> edges) {
        static final Codec<VoltageTier> TIER = Codec.STRING.xmap(VoltageTier::byId, VoltageTier::id);
        static final Codec<Cable> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.LONG.fieldOf("id").forGetter(Cable::id),
                TIER.fieldOf("tier").forGetter(Cable::tier),
                BlockPos.CODEC.fieldOf("from").forGetter(Cable::from),
                BlockPos.CODEC.fieldOf("to").forGetter(Cable::to),
                BlockPos.CODEC.listOf().fieldOf("path").forGetter(Cable::path),
                Codec.DOUBLE.fieldOf("length").forGetter(Cable::length),
                Codec.INT.fieldOf("items").forGetter(Cable::items),
                Codec.LONG.listOf().fieldOf("edges").forGetter(Cable::edges)).apply(i, Cable::new));

        public Cable {
            path = List.copyOf(path);
            edges = List.copyOf(edges);
        }
    }

    record DimensionRecord(String dimension, long nextId, List<Cable> cables) {
        static final Codec<DimensionRecord> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("dimension").forGetter(DimensionRecord::dimension),
                Codec.LONG.fieldOf("next_id").forGetter(DimensionRecord::nextId),
                Cable.CODEC.listOf().fieldOf("cables").forGetter(DimensionRecord::cables)).apply(i, DimensionRecord::new));
    }

    private static final Codec<CableData> CODEC = DimensionRecord.CODEC.listOf().xmap(CableData::new, CableData::save);
    public static final SavedDataType<CableData> TYPE = new SavedDataType<>(Identifier.fromNamespaceAndPath(ArcforgeTransmission.MODID, "tray_cables"),
            CableData::new, CODEC);

    // One dimension's cables, by id, with each tray block's and termination's.
    public final class Dimension {
        final Map<Long, Cable> cables = new TreeMap<>();
        final Map<Long, List<Long>> byTray = new HashMap<>(), byEnd = new HashMap<>();
        long nextId = 1;

        public @Nullable Cable cable(long id) {
            return cables.get(id);
        }

        public List<Cable> all() {
            return List.copyOf(cables.values());
        }

        // The cables lying in a tray block, by id.
        public List<Cable> inTray(BlockPos tray) {
            List<Cable> out = new ArrayList<>();
            for (long id : byTray.getOrDefault(tray.asLong(), List.of())) {
                out.add(cables.get(id));
            }
            return out;
        }

        // The cables ending at a termination.
        public List<Cable> atEnd(BlockPos termination) {
            List<Cable> out = new ArrayList<>();
            for (long id : byEnd.getOrDefault(termination.asLong(), List.of())) {
                out.add(cables.get(id));
            }
            return out;
        }

        // The cable a conductor edge belongs to, or null.
        public @Nullable Cable ofEdge(long edge) {
            for (Cable cable : cables.values()) {
                if (cable.edges().contains(edge)) {
                    return cable;
                }
            }
            return null;
        }

        long newId() {
            setDirty();
            return nextId++;
        }

        void put(Cable cable) {
            cables.put(cable.id(), cable);
            for (BlockPos tray : cable.path()) {
                byTray.computeIfAbsent(tray.asLong(), k -> new ArrayList<>()).add(cable.id());
            }
            byEnd.computeIfAbsent(cable.from().asLong(), k -> new ArrayList<>()).add(cable.id());
            byEnd.computeIfAbsent(cable.to().asLong(), k -> new ArrayList<>()).add(cable.id());
            setDirty();
        }

        void remove(long id) {
            Cable cable = cables.remove(id);
            if (cable == null) {
                return;
            }
            for (BlockPos tray : cable.path()) {
                List<Long> ids = byTray.get(tray.asLong());
                if (ids != null) {
                    ids.remove(id);
                }
            }
            for (BlockPos end : List.of(cable.from(), cable.to())) {
                List<Long> ids = byEnd.get(end.asLong());
                if (ids != null) {
                    ids.remove(id);
                }
            }
            setDirty();
        }
    }

    private final Map<String, Dimension> dimensions = new LinkedHashMap<>();

    public CableData() {}

    private CableData(List<DimensionRecord> saved) {
        for (DimensionRecord record : saved) {
            Dimension dimension = new Dimension();
            dimension.nextId = record.nextId();
            record.cables().forEach(dimension::put);
            dimensions.put(record.dimension(), dimension);
        }
    }

    private List<DimensionRecord> save() {
        List<DimensionRecord> out = new ArrayList<>();
        dimensions.forEach((name, dimension) -> out.add(new DimensionRecord(name, dimension.nextId, List.copyOf(dimension.cables.values()))));
        return out;
    }

    // A level's cables in this data (as it was loaded), or null if it has none.
    public @Nullable Dimension dimension(ServerLevel level) {
        return dimensions.get(level.dimension().identifier().toString());
    }

    public static Dimension of(ServerLevel level) {
        CableData data = level.getServer().getDataStorage().computeIfAbsent(TYPE);
        return data.dimensions.computeIfAbsent(level.dimension().identifier().toString(), k -> data.new Dimension());
    }
}
