/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.tower;

import java.util.List;
import java.util.function.Supplier;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.grid.block.GridContent;
import net.zagdrath.arcforgetransmission.material.MaterialContent;

// Everything the transmission towers register: the four tower blocks and their items, the invisible part block, the two
// block entity types and a creative tab (which also shows the grid's items, GridContent). Call register(modEventBus) from
// the mod's constructor. The towers carry wires through their insulators (TowerBlockEntity); they have no recipes yet.
public final class TowerContent {
    // The addon's mod id.
    public static final String MODID = ArcforgeTransmission.MODID;

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    // The towers: three of treated wood (an axe takes them down), the lattice of galvanized steel (a pickaxe).
    public static final DeferredBlock<TowerBlock> LV_DISTRIBUTION_POLE = tower(TowerTier.LV, false);
    public static final DeferredBlock<TowerBlock> MV_SUB_TRANSMISSION_POLE = tower(TowerTier.MV, false);
    public static final DeferredBlock<TowerBlock> HV_H_FRAME_TOWER = tower(TowerTier.HV, false);
    public static final DeferredBlock<TowerBlock> EHV_LATTICE_TOWER = tower(TowerTier.EHV, true);
    // The transformers: three substations (LV/MV, MV/HV, HV/EHV) and a unit transformer per tier, galvanized steel.
    public static final DeferredBlock<TowerBlock> MV_SUBSTATION_TRANSFORMER = tower(TowerTier.MV_SUBSTATION, true);
    public static final DeferredBlock<TowerBlock> HV_SUBSTATION_TRANSFORMER = tower(TowerTier.HV_SUBSTATION, true);
    public static final DeferredBlock<TowerBlock> EHV_SUBSTATION_TRANSFORMER = tower(TowerTier.EHV_SUBSTATION, true);
    public static final DeferredBlock<TowerBlock> LV_UNIT_TRANSFORMER = tower(TowerTier.LV_UNIT, true);
    public static final DeferredBlock<TowerBlock> MV_UNIT_TRANSFORMER = tower(TowerTier.MV_UNIT, true);
    public static final DeferredBlock<TowerBlock> HV_UNIT_TRANSFORMER = tower(TowerTier.HV_UNIT, true);
    public static final DeferredBlock<TowerBlock> EHV_UNIT_TRANSFORMER = tower(TowerTier.EHV_UNIT, true);
    // Everything else placed whole: protection, line hardware and the service meter (by TowerTier, in its order).
    public static final java.util.Map<TowerTier, DeferredBlock<TowerBlock>> MORE = new java.util.EnumMap<>(TowerTier.class);
    public static final java.util.Map<TowerTier, DeferredItem<TowerItem>> MORE_ITEMS = new java.util.EnumMap<>(TowerTier.class);

    static {
        for (TowerTier tier : TowerTier.values()) {
            if (tier.ordinal() >= TowerTier.LV_FUSE.ordinal()) {
                DeferredBlock<TowerBlock> block = tower(tier, tier.steel());
                MORE.put(tier, block);
                MORE_ITEMS.put(tier, item(block, tier));
            }
        }
    }
    // The invisible blocks round a tower's main block, shaped like the tower in them. No item, no drops.
    public static final DeferredBlock<TowerPartBlock> TOWER_PART = BLOCKS.registerBlock("transmission_tower_part", TowerPartBlock::new,
            p -> p.mapColor(MapColor.COLOR_BROWN).strength(2.5F, 6.0F).sound(SoundType.WOOD).noOcclusion().noLootTable()
                    .pushReaction(PushReaction.IMMOVEABLE)
                    // Its shape depends on where its main block is, so it can't be cached per state.
                    .dynamicShape());

    public static final DeferredItem<TowerItem> LV_DISTRIBUTION_POLE_ITEM = item(LV_DISTRIBUTION_POLE, TowerTier.LV);
    public static final DeferredItem<TowerItem> MV_SUB_TRANSMISSION_POLE_ITEM = item(MV_SUB_TRANSMISSION_POLE, TowerTier.MV);
    public static final DeferredItem<TowerItem> HV_H_FRAME_TOWER_ITEM = item(HV_H_FRAME_TOWER, TowerTier.HV);
    public static final DeferredItem<TowerItem> EHV_LATTICE_TOWER_ITEM = item(EHV_LATTICE_TOWER, TowerTier.EHV);
    public static final DeferredItem<TowerItem> MV_SUBSTATION_TRANSFORMER_ITEM = item(MV_SUBSTATION_TRANSFORMER, TowerTier.MV_SUBSTATION);
    public static final DeferredItem<TowerItem> HV_SUBSTATION_TRANSFORMER_ITEM = item(HV_SUBSTATION_TRANSFORMER, TowerTier.HV_SUBSTATION);
    public static final DeferredItem<TowerItem> EHV_SUBSTATION_TRANSFORMER_ITEM = item(EHV_SUBSTATION_TRANSFORMER, TowerTier.EHV_SUBSTATION);
    public static final DeferredItem<TowerItem> LV_UNIT_TRANSFORMER_ITEM = item(LV_UNIT_TRANSFORMER, TowerTier.LV_UNIT);
    public static final DeferredItem<TowerItem> MV_UNIT_TRANSFORMER_ITEM = item(MV_UNIT_TRANSFORMER, TowerTier.MV_UNIT);
    public static final DeferredItem<TowerItem> HV_UNIT_TRANSFORMER_ITEM = item(HV_UNIT_TRANSFORMER, TowerTier.HV_UNIT);
    public static final DeferredItem<TowerItem> EHV_UNIT_TRANSFORMER_ITEM = item(EHV_UNIT_TRANSFORMER, TowerTier.EHV_UNIT);

    public static final Supplier<BlockEntityType<TowerBlockEntity>> TOWER_ENTITY = BLOCK_ENTITY_TYPES.register("transmission_tower",
            () -> new BlockEntityType<>(TowerBlockEntity::new, allBlocks().toArray(TowerBlock[]::new)));
    public static final Supplier<BlockEntityType<TowerPartBlockEntity>> TOWER_PART_ENTITY = BLOCK_ENTITY_TYPES.register("transmission_tower_part",
            () -> new BlockEntityType<>(TowerPartBlockEntity::new, TOWER_PART.get()));

    // The addon's creative tabs, one a kind of thing: towers and lines, transformers, protection and switching, the
    // substation yard, wiring and tools, and materials (in that order, each after the last).
    public enum Tab {
        TOWERS("towers"), TRANSFORMERS("transformers"), PROTECTION("protection"), YARD("yard"), WIRING("wiring"), MATERIALS("materials");

        final String id;

        Tab(String id) {
            this.id = id;
        }
    }

    private static final java.util.Map<Tab, DeferredHolder<CreativeModeTab, CreativeModeTab>> TABS = new java.util.EnumMap<>(Tab.class);

    static {
        Tab previous = null;
        for (Tab tab : Tab.values()) {
            Tab before = previous;
            TABS.put(tab, CREATIVE_MODE_TABS.register(tab.id, () -> {
                CreativeModeTab.Builder builder = CreativeModeTab.builder()
                        .title(Component.translatable("itemGroup." + MODID + "." + tab.id))
                        .icon(() -> icon(tab).getDefaultInstance())
                        .displayItems((parameters, output) -> itemsIn(tab).forEach(item -> output.accept(item)));
                if (before != null) {
                    builder.withTabsBefore(Identifier.fromNamespaceAndPath(MODID, before.id));
                }
                return builder.build();
            }));
            previous = tab;
        }
    }

    private static Item icon(Tab tab) {
        return switch (tab) {
            case TOWERS -> EHV_LATTICE_TOWER_ITEM.get();
            case TRANSFORMERS -> HV_SUBSTATION_TRANSFORMER_ITEM.get();
            case PROTECTION -> MORE_ITEMS.get(TowerTier.HV_BREAKER).get();
            case YARD -> MORE_ITEMS.get(TowerTier.HV_BUS).get();
            case WIRING -> GridContent.coil(net.zagdrath.arcforgetransmission.grid.VoltageTier.HV);
            case MATERIALS -> MaterialContent.ALUMINIUM_INGOT.get();
        };
    }

    // Which tab a structure goes in, by what it is.
    private static Tab tabOf(TowerTier tier) {
        return switch (tier.kind()) {
            case TOWER, DEAD_END -> Tab.TOWERS;
            case SUBSTATION, UNIT, SERVICE -> Tab.TRANSFORMERS;
            case FUSE, BREAKER, DISCONNECT, SECTIONALIZER, GROUND, ATS, RELAY -> Tab.PROTECTION;
            case BUS, REGULATOR, SHUNT -> Tab.YARD;
        };
    }

    // Which tab one of the grid's own items goes in, by its id.
    private static Tab tabOf(String id) {
        return switch (id) {
            case "surge_arrester", "fuse_link", "current_transformer", "potential_transformer", "control_cable" -> Tab.PROTECTION;
            case "pole_transformer", "mv_distribution_transformer" -> Tab.TRANSFORMERS;
            case "station_fence", "ground_grid" -> Tab.YARD;
            default -> Tab.WIRING;
        };
    }

    private static List<Item> itemsIn(Tab tab) {
        List<Item> out = new java.util.ArrayList<>();
        if (tab == Tab.MATERIALS) {
            MaterialContent.tabItems().forEach(item -> out.add(item.get()));
            return out;
        }
        for (DeferredItem<TowerItem> item : allItems()) {
            if (tabOf(item.get().tier()) == tab) {
                out.add(item.get());
            }
        }
        for (DeferredItem<? extends Item> item : GridContent.tabItems()) {
            if (tabOf(item.getId().getPath()) == tab) {
                out.add(item.get());
            }
        }
        return out;
    }

    private TowerContent() {}

    public static List<DeferredItem<TowerItem>> towerItems() {
        return List.of(LV_DISTRIBUTION_POLE_ITEM, MV_SUB_TRANSMISSION_POLE_ITEM, HV_H_FRAME_TOWER_ITEM, EHV_LATTICE_TOWER_ITEM,
                MV_SUBSTATION_TRANSFORMER_ITEM, HV_SUBSTATION_TRANSFORMER_ITEM, EHV_SUBSTATION_TRANSFORMER_ITEM, LV_UNIT_TRANSFORMER_ITEM,
                MV_UNIT_TRANSFORMER_ITEM, HV_UNIT_TRANSFORMER_ITEM, EHV_UNIT_TRANSFORMER_ITEM);
    }

    // Every block placed whole (each draws and holds its structure).
    private static List<TowerBlock> allBlocks() {
        List<TowerBlock> out = new java.util.ArrayList<>(List.of(LV_DISTRIBUTION_POLE.get(), MV_SUB_TRANSMISSION_POLE.get(), HV_H_FRAME_TOWER.get(),
                EHV_LATTICE_TOWER.get(), MV_SUBSTATION_TRANSFORMER.get(), HV_SUBSTATION_TRANSFORMER.get(), EHV_SUBSTATION_TRANSFORMER.get(),
                LV_UNIT_TRANSFORMER.get(), MV_UNIT_TRANSFORMER.get(), HV_UNIT_TRANSFORMER.get(), EHV_UNIT_TRANSFORMER.get()));
        MORE.values().forEach(block -> out.add(block.get()));
        return out;
    }

    // Everything placed whole, in the tab's order: the towers and transformers, then the rest.
    public static List<DeferredItem<TowerItem>> allItems() {
        List<DeferredItem<TowerItem>> out = new java.util.ArrayList<>(towerItems());
        out.addAll(MORE_ITEMS.values());
        return out;
    }

    private static DeferredBlock<TowerBlock> tower(TowerTier tier, boolean steel) {
        return BLOCKS.registerBlock(tier.id(), p -> new TowerBlock(tier, p), p -> towerProperties(p, steel));
    }

    private static DeferredItem<TowerItem> item(DeferredBlock<TowerBlock> block, TowerTier tier) {
        return ITEMS.registerItem(tier.id(), p -> new TowerItem(block.get(), tier, p), p -> p.useBlockDescriptionPrefix());
    }

    // Treated wood like Arcforge's treated logs (a little tougher), or galvanized steel like a machine.
    private static BlockBehaviour.Properties towerProperties(BlockBehaviour.Properties p, boolean steel) {
        p = steel ? p.mapColor(MapColor.METAL).strength(5.0F, 6.0F).requiresCorrectToolForDrops().sound(SoundType.METAL)
                : p.mapColor(MapColor.COLOR_BROWN).instrument(NoteBlockInstrument.BASS).sound(SoundType.WOOD).strength(2.5F, 6.0F);
        return p.noOcclusion().pushReaction(PushReaction.IMMOVEABLE);
    }

    public static void register(IEventBus modEventBus) {
        // EV was renamed EHV: worlds and inventories from before keep their towers and transformers.
        for (String id : List.of("lattice_tower", "substation_transformer", "unit_transformer")) {
            Identifier from = Identifier.fromNamespaceAndPath(MODID, "ev_" + id), to = Identifier.fromNamespaceAndPath(MODID, "ehv_" + id);
            BLOCKS.addAlias(from, to);
            ITEMS.addAlias(from, to);
        }
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITY_TYPES.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
    }
}
