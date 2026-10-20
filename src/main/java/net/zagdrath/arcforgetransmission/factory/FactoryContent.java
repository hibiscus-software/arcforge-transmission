/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.factory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.tower.model.FactoryMesh.Section;

// Factory distribution's content: per tier a Cable Termination (and the invisible cells the tall ones stand up into)
// and a switchgear lineup's sections (and their other blocks); busway (runs, feed-ins, tap-offs, reducers); cable tray
// and insulated cable.
// Call register(modEventBus) from the mod's constructor.
public final class FactoryContent {
    private static final String MODID = ArcforgeTransmission.MODID;

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);

    // The items, in the order the creative tabs list them, each with the tab it goes in.
    public enum Tab {
        WIRING, YARD
    }

    private static final Map<Tab, List<DeferredItem<? extends Item>>> TAB_ITEMS = new EnumMap<>(Tab.class);

    public static final Map<VoltageTier, DeferredBlock<CableTerminationBlock>> TERMINATIONS = new EnumMap<>(VoltageTier.class);

    static {
        for (VoltageTier tier : VoltageTier.values()) {
            DeferredBlock<CableTerminationBlock> termination = BLOCKS.registerBlock(tier.id() + "_cable_termination", p -> new CableTerminationBlock(tier, p),
                    p -> p.mapColor(MapColor.METAL).strength(2.0F, 6.0F).sound(tier.ordinal() >= 2 ? SoundType.GLASS : SoundType.METAL).noOcclusion()
                            .requiresCorrectToolForDrops().pushReaction(PushReaction.IMMOVEABLE));
            TERMINATIONS.put(tier, termination);
            add(Tab.WIRING, ITEMS.registerSimpleBlockItem(termination));
        }
    }

    // What an HV or EHV termination stands up into: drops nothing of its own.
    public static final DeferredBlock<CableTerminationPartBlock> TERMINATION_PART = BLOCKS.registerBlock("cable_termination_part",
            CableTerminationPartBlock::new, p -> p.mapColor(MapColor.METAL).strength(2.0F, 6.0F).sound(SoundType.METAL).noOcclusion().noLootTable()
                    .pushReaction(PushReaction.IMMOVEABLE));

    // The lineups' sections, per tier: incoming, transformer (MV, HV, EHV: one tier down), main, feeder and bus tie.
    public static final Map<VoltageTier, Map<Section, DeferredBlock<SectionBlock>>> SECTIONS = new EnumMap<>(VoltageTier.class);

    static {
        for (VoltageTier tier : VoltageTier.values()) {
            Map<Section, DeferredBlock<SectionBlock>> sections = new EnumMap<>(Section.class);
            SECTIONS.put(tier, sections);
            sections.put(Section.INCOMING, section(BLOCKS.registerBlock(tier.id() + "_incoming_section", p -> new SectionBlock(tier, Section.INCOMING, p),
                    p -> sectionProperties(p, tier))));
            if (tier != VoltageTier.LV) {
                sections.put(Section.TRANSFORMER, section(BLOCKS.registerBlock(tier.id() + "_transformer_section",
                        p -> new SectionBlock(tier, Section.TRANSFORMER, p), p -> sectionProperties(p, tier))));
            }
            sections.put(Section.MAIN, section(BLOCKS.registerBlock(tier.id() + "_main_section", p -> new SectionBlock(tier, Section.MAIN, p),
                    p -> sectionProperties(p, tier))));
            sections.put(Section.FEEDER, section(BLOCKS.registerBlock(tier.id() + "_feeder_section", p -> new SectionBlock(tier, Section.FEEDER, p),
                    p -> sectionProperties(p, tier))));
            sections.put(Section.TIE, section(BLOCKS.registerBlock(tier.id() + "_tie_section", p -> new SectionBlock(tier, Section.TIE, p),
                    p -> sectionProperties(p, tier))));
        }
    }

    private static net.minecraft.world.level.block.state.BlockBehaviour.Properties sectionProperties(
            net.minecraft.world.level.block.state.BlockBehaviour.Properties p, VoltageTier tier) {
        return p.mapColor(MapColor.METAL).strength(5.0F, 9.0F).sound(SoundType.METAL).noOcclusion().requiresCorrectToolForDrops()
                .pushReaction(PushReaction.IMMOVEABLE);
    }

    private static DeferredBlock<SectionBlock> section(DeferredBlock<SectionBlock> block) {
        add(Tab.YARD, ITEMS.registerItem(block.getId().getPath(), p -> new SectionItem(block.get(), p), p -> p.useBlockDescriptionPrefix()));
        return block;
    }

    // A section's other blocks: drop nothing of their own.
    public static final DeferredBlock<SectionPartBlock> SECTION_PART = BLOCKS.registerBlock("switchgear_section_part", SectionPartBlock::new,
            p -> p.mapColor(MapColor.METAL).strength(5.0F, 9.0F).sound(SoundType.METAL).noOcclusion().noLootTable().pushReaction(PushReaction.IMMOVEABLE));

    public static final Supplier<BlockEntityType<SectionBlockEntity>> SECTION_ENTITY = BLOCK_ENTITY_TYPES.register("switchgear_section",
            () -> new BlockEntityType<>(SectionBlockEntity::new, SECTIONS.values().stream().flatMap(m -> m.values().stream()).map(DeferredBlock::get)
                    .toArray(SectionBlock[]::new)));
    public static final Supplier<BlockEntityType<SectionPartBlockEntity>> SECTION_PART_ENTITY = BLOCK_ENTITY_TYPES.register("switchgear_section_part",
            () -> new BlockEntityType<>(SectionPartBlockEntity::new, SECTION_PART.get()));

    // Busway, per tier: runs in three sizes (2W, 4W, 8W), the feed-in, the tap-off and the reducers between sizes.
    public static final Map<VoltageTier, Map<Integer, DeferredBlock<BuswayBlock>>> BUSWAYS = new EnumMap<>(VoltageTier.class);
    public static final Map<VoltageTier, DeferredBlock<BuswayFeedInBlock>> FEED_INS = new EnumMap<>(VoltageTier.class);
    public static final Map<VoltageTier, DeferredBlock<BuswayTapOffBlock>> TAP_OFFS = new EnumMap<>(VoltageTier.class);
    public static final Map<VoltageTier, List<DeferredBlock<BuswayReducerBlock>>> REDUCERS = new EnumMap<>(VoltageTier.class);

    static {
        for (VoltageTier tier : VoltageTier.values()) {
            Map<Integer, DeferredBlock<BuswayBlock>> runs = new java.util.TreeMap<>();
            runs.put(2, bus(BLOCKS.registerBlock(tier.id() + "_busway_2w", p -> new BuswayBlock(tier, 2, p), p -> busProperties(p, tier))));
            runs.put(4, bus(BLOCKS.registerBlock(tier.id() + "_busway_4w", p -> new BuswayBlock(tier, 4, p), p -> busProperties(p, tier))));
            runs.put(8, bus(BLOCKS.registerBlock(tier.id() + "_busway_8w", p -> new BuswayBlock(tier, 8, p), p -> busProperties(p, tier))));
            BUSWAYS.put(tier, runs);
            FEED_INS.put(tier, bus(BLOCKS.registerBlock(tier.id() + "_busway_feed_in", p -> new BuswayFeedInBlock(tier, p), p -> busProperties(p, tier))));
            TAP_OFFS.put(tier, bus(BLOCKS.registerBlock(tier.id() + "_busway_tap_off", p -> new BuswayTapOffBlock(tier, p), p -> busProperties(p, tier))));
            REDUCERS.put(tier, List.of(
                    bus(BLOCKS.registerBlock(tier.id() + "_busway_reducer_8w_4w", p -> new BuswayReducerBlock(tier, 8, 4, p), p -> busProperties(p, tier))),
                    bus(BLOCKS.registerBlock(tier.id() + "_busway_reducer_4w_2w", p -> new BuswayReducerBlock(tier, 4, 2, p), p -> busProperties(p, tier)))));
        }
    }

    private static net.minecraft.world.level.block.state.BlockBehaviour.Properties busProperties(
            net.minecraft.world.level.block.state.BlockBehaviour.Properties p, VoltageTier tier) {
        return p.mapColor(MapColor.METAL).strength(3.0F, 6.0F).sound(SoundType.METAL).noOcclusion().requiresCorrectToolForDrops();
    }

    private static <B extends net.minecraft.world.level.block.Block> DeferredBlock<B> bus(DeferredBlock<B> block) {
        add(Tab.WIRING, ITEMS.registerSimpleBlockItem(block));
        return block;
    }

    public static final Supplier<BlockEntityType<BuswayBlockEntity>> BUSWAY_ENTITY = BLOCK_ENTITY_TYPES.register("busway",
            () -> new BlockEntityType<>(BuswayBlockEntity::new, BUSWAYS.values().stream().flatMap(m -> m.values().stream()).map(DeferredBlock::get)
                    .toArray(BuswayBlock[]::new)));
    public static final Supplier<BlockEntityType<BuswayFeedInBlockEntity>> FEED_IN_ENTITY = BLOCK_ENTITY_TYPES.register("busway_feed_in",
            () -> new BlockEntityType<>(BuswayFeedInBlockEntity::new, FEED_INS.values().stream().map(DeferredBlock::get).toArray(BuswayFeedInBlock[]::new)));
    public static final Supplier<BlockEntityType<BuswayTapOffBlockEntity>> TAP_OFF_ENTITY = BLOCK_ENTITY_TYPES.register("busway_tap_off",
            () -> new BlockEntityType<>(BuswayTapOffBlockEntity::new, TAP_OFFS.values().stream().map(DeferredBlock::get).toArray(BuswayTapOffBlock[]::new)));
    public static final Supplier<BlockEntityType<BuswayReducerBlockEntity>> REDUCER_ENTITY = BLOCK_ENTITY_TYPES.register("busway_reducer",
            () -> new BlockEntityType<>(BuswayReducerBlockEntity::new, REDUCERS.values().stream().flatMap(List::stream).map(DeferredBlock::get)
                    .toArray(BuswayReducerBlock[]::new)));

    // Cable tray (standard, wide, heavy-duty) and the insulated cable laid in it, per tier.
    public static final Map<CableTrayBlock.Kind, DeferredBlock<CableTrayBlock>> TRAYS = new EnumMap<>(CableTrayBlock.Kind.class);
    public static final Map<VoltageTier, DeferredItem<InsulatedCableItem>> CABLES = new EnumMap<>(VoltageTier.class);

    static {
        TRAYS.put(CableTrayBlock.Kind.STANDARD, tray(BLOCKS.registerBlock("cable_tray", p -> new CableTrayBlock(CableTrayBlock.Kind.STANDARD, p),
                FactoryContent::trayProperties)));
        TRAYS.put(CableTrayBlock.Kind.WIDE, tray(BLOCKS.registerBlock("wide_cable_tray", p -> new CableTrayBlock(CableTrayBlock.Kind.WIDE, p),
                FactoryContent::trayProperties)));
        TRAYS.put(CableTrayBlock.Kind.HEAVY, tray(BLOCKS.registerBlock("heavy_duty_cable_tray", p -> new CableTrayBlock(CableTrayBlock.Kind.HEAVY, p),
                FactoryContent::trayProperties)));
        for (VoltageTier tier : VoltageTier.values()) {
            DeferredItem<InsulatedCableItem> cable = ITEMS.registerItem(tier.id() + "_insulated_cable", p -> new InsulatedCableItem(tier, p),
                    p -> p.stacksTo(64));
            CABLES.put(tier, cable);
            add(Tab.WIRING, cable);
        }
    }

    private static net.minecraft.world.level.block.state.BlockBehaviour.Properties trayProperties(
            net.minecraft.world.level.block.state.BlockBehaviour.Properties p) {
        return p.mapColor(MapColor.METAL).strength(2.0F, 6.0F).sound(SoundType.CHAIN).noOcclusion().requiresCorrectToolForDrops();
    }

    private static DeferredBlock<CableTrayBlock> tray(DeferredBlock<CableTrayBlock> block) {
        add(Tab.WIRING, ITEMS.registerSimpleBlockItem(block));
        return block;
    }

    public static final Supplier<BlockEntityType<CableTrayBlockEntity>> TRAY_ENTITY = BLOCK_ENTITY_TYPES.register("cable_tray",
            () -> new BlockEntityType<>(CableTrayBlockEntity::new, TRAYS.values().stream().map(DeferredBlock::get).toArray(CableTrayBlock[]::new)));

    public static final Supplier<BlockEntityType<CableTerminationBlockEntity>> TERMINATION_ENTITY = BLOCK_ENTITY_TYPES.register("cable_termination",
            () -> new BlockEntityType<>(CableTerminationBlockEntity::new,
                    TERMINATIONS.values().stream().map(DeferredBlock::get).toArray(CableTerminationBlock[]::new)));

    private FactoryContent() {}

    private static void add(Tab tab, DeferredItem<? extends Item> item) {
        TAB_ITEMS.computeIfAbsent(tab, t -> new ArrayList<>()).add(item);
    }

    // The items a creative tab holds.
    public static List<DeferredItem<? extends Item>> tabItems(Tab tab) {
        return TAB_ITEMS.getOrDefault(tab, List.of());
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITY_TYPES.register(modEventBus);
    }
}
