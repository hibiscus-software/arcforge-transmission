/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.block;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.item.LinkTarget;
import net.zagdrath.arcforgetransmission.grid.item.WireCoilItem;

// Everything the grid registers: per tier an Inductor, a Wire Coil and a Creative Source and Sink; the Pole-Mount
// Transformer; Burnt Wire; their block entity types; the coil's loose-end component; and the creative blocks' FE
// capability. Call register(modEventBus) from the mod's constructor.
public final class GridContent {
    private static final String MODID = ArcforgeTransmission.MODID;

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
    public static final DeferredRegister.DataComponents DATA_COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MODID);

    public static final Map<VoltageTier, DeferredBlock<InductorBlock>> INDUCTORS = new EnumMap<>(VoltageTier.class);
    public static final Map<VoltageTier, DeferredBlock<CreativeEnergyBlock>> CREATIVE_SOURCES = new EnumMap<>(VoltageTier.class);
    public static final Map<VoltageTier, DeferredBlock<CreativeEnergyBlock>> CREATIVE_SINKS = new EnumMap<>(VoltageTier.class);
    public static final Map<VoltageTier, DeferredItem<WireCoilItem>> COILS = new EnumMap<>(VoltageTier.class);
    private static final List<DeferredItem<? extends Item>> TAB_ITEMS = new ArrayList<>();

    static {
        for (VoltageTier tier : VoltageTier.values()) {
            // Porcelain (LV, MV) or glass (HV, EV) on galvanized steel.
            boolean glass = tier == VoltageTier.HV || tier == VoltageTier.EV;
            DeferredBlock<InductorBlock> inductor = BLOCKS.registerBlock(tier.id() + "_inductor", p -> new InductorBlock(tier, p),
                    p -> p.mapColor(glass ? MapColor.COLOR_LIGHT_GRAY : MapColor.TERRACOTTA_BROWN).strength(1.5F, 6.0F)
                            .sound(glass ? SoundType.GLASS : SoundType.STONE).noOcclusion().pushReaction(PushReaction.DESTROY));
            INDUCTORS.put(tier, inductor);
            TAB_ITEMS.add(ITEMS.registerSimpleBlockItem(inductor));
        }
        for (VoltageTier tier : VoltageTier.values()) {
            COILS.put(tier, ITEMS.registerItem(tier.id() + "_wire_coil", p -> new WireCoilItem(tier, p), p -> p.stacksTo(64)));
            TAB_ITEMS.add(COILS.get(tier));
        }
    }

    public static final DeferredBlock<PoleTransformerBlock> POLE_TRANSFORMER = BLOCKS.registerBlock("pole_transformer", PoleTransformerBlock::new,
            p -> p.mapColor(MapColor.METAL).strength(3.0F, 6.0F).sound(SoundType.METAL).noOcclusion().pushReaction(PushReaction.DESTROY));
    public static final DeferredItem<BlockItem> POLE_TRANSFORMER_ITEM = ITEMS.registerSimpleBlockItem(POLE_TRANSFORMER);
    public static final DeferredItem<Item> BURNT_WIRE = ITEMS.registerSimpleItem("burnt_wire");

    static {
        TAB_ITEMS.add(POLE_TRANSFORMER_ITEM);
        TAB_ITEMS.add(BURNT_WIRE);
        // Creative only: unbreakable outside creative, no recipe.
        for (boolean source : new boolean[] { true, false }) {
            for (VoltageTier tier : VoltageTier.values()) {
                DeferredBlock<CreativeEnergyBlock> block = BLOCKS.registerBlock(tier.id() + (source ? "_creative_source" : "_creative_sink"),
                        p -> new CreativeEnergyBlock(tier, source, p),
                        p -> p.mapColor(MapColor.COLOR_MAGENTA).strength(-1.0F, 3_600_000.0F).sound(SoundType.METAL).noLootTable());
                (source ? CREATIVE_SOURCES : CREATIVE_SINKS).put(tier, block);
                TAB_ITEMS.add(ITEMS.registerSimpleBlockItem(block));
            }
        }
    }

    public static final Supplier<BlockEntityType<InductorBlockEntity>> INDUCTOR_ENTITY = BLOCK_ENTITY_TYPES.register("inductor",
            () -> new BlockEntityType<>(InductorBlockEntity::new, INDUCTORS.values().stream().map(DeferredBlock::get).toArray(InductorBlock[]::new)));
    public static final Supplier<BlockEntityType<PoleTransformerBlockEntity>> POLE_TRANSFORMER_ENTITY = BLOCK_ENTITY_TYPES.register("pole_transformer",
            () -> new BlockEntityType<>(PoleTransformerBlockEntity::new, POLE_TRANSFORMER.get()));
    public static final Supplier<BlockEntityType<CreativeEnergyBlockEntity>> CREATIVE_ENERGY_ENTITY = BLOCK_ENTITY_TYPES.register("creative_energy",
            () -> new BlockEntityType<>(CreativeEnergyBlockEntity::new, creativeBlocks()));

    // The loose end of a wire coil.
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LinkTarget>> LINK = DATA_COMPONENTS.registerComponentType("wire_link",
            b -> b.persistent(LinkTarget.CODEC).networkSynchronized(LinkTarget.STREAM_CODEC));

    private GridContent() {}

    private static CreativeEnergyBlock[] creativeBlocks() {
        List<CreativeEnergyBlock> blocks = new ArrayList<>();
        CREATIVE_SOURCES.values().forEach(b -> blocks.add(b.get()));
        CREATIVE_SINKS.values().forEach(b -> blocks.add(b.get()));
        return blocks.toArray(CreativeEnergyBlock[]::new);
    }

    public static Item coil(VoltageTier tier) {
        return COILS.get(tier).get();
    }

    // Everything for the addon's creative tab, after the towers.
    public static List<DeferredItem<? extends Item>> tabItems() {
        return TAB_ITEMS;
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITY_TYPES.register(modEventBus);
        DATA_COMPONENTS.register(modEventBus);
        modEventBus.addListener(GridContent::registerCapabilities);
    }

    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.Energy.BLOCK, CREATIVE_ENERGY_ENTITY.get(), CreativeEnergyBlockEntity::energy);
    }
}
