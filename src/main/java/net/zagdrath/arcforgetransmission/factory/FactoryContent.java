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
// and a switchgear lineup's sections (and their other blocks).
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
        add(Tab.YARD, ITEMS.registerItem(block.getId().getPath(), p -> new SectionItem(block.get(), p)));
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
