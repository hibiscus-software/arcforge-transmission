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
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
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
import net.zagdrath.arcforgetransmission.grid.GridEdge;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.item.LinkTarget;
import net.zagdrath.arcforgetransmission.grid.item.ClampMeterItem;
import net.zagdrath.arcforgetransmission.grid.item.FuseLinkItem;
import net.zagdrath.arcforgetransmission.grid.item.GuyWireItem;
import net.zagdrath.arcforgetransmission.grid.item.WireCoilItem;
import net.zagdrath.arcforgetransmission.grid.item.WireCuttersItem;

// Everything the grid registers: per tier a Pin Insulator, a Wire Coil and a Creative Source and Sink; Wire Cutters; the Pole-Mount
// Transformer; Burnt Wire; their block entity types; the coil's loose-end component; and the creative blocks' FE
// capability. Call register(modEventBus) from the mod's constructor.
public final class GridContent {
    private static final String MODID = ArcforgeTransmission.MODID;

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
    public static final DeferredRegister.DataComponents DATA_COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MODID);

    public static final Map<VoltageTier, DeferredBlock<PinInsulatorBlock>> PIN_INSULATORS = new EnumMap<>(VoltageTier.class);
    public static final Map<VoltageTier, DeferredBlock<CreativeEnergyBlock>> CREATIVE_SOURCES = new EnumMap<>(VoltageTier.class);
    public static final Map<VoltageTier, DeferredBlock<CreativeEnergyBlock>> CREATIVE_SINKS = new EnumMap<>(VoltageTier.class);
    public static final Map<VoltageTier, DeferredItem<WireCoilItem>> COILS = new EnumMap<>(VoltageTier.class);
    // Rubber-insulated wire (Arcforge's rubber) and underground cable: LV and MV only, safe to touch.
    public static final Map<VoltageTier, DeferredItem<WireCoilItem>> INSULATED_COILS = new EnumMap<>(VoltageTier.class);
    public static final Map<VoltageTier, DeferredItem<WireCoilItem>> UNDERGROUND_CABLES = new EnumMap<>(VoltageTier.class);
    // Grounded steel strung between tower peaks: catches lightning.
    public static final DeferredItem<WireCoilItem> SHIELD_WIRE_COIL;
    private static final List<DeferredItem<? extends Item>> TAB_ITEMS = new ArrayList<>();

    static {
        for (VoltageTier tier : VoltageTier.values()) {
            // Porcelain (LV, MV) or glass (HV, EHV) on galvanized steel.
            boolean glass = tier == VoltageTier.HV || tier == VoltageTier.EHV;
            DeferredBlock<PinInsulatorBlock> pinInsulator = BLOCKS.registerBlock(tier.id() + "_pin_insulator", p -> new PinInsulatorBlock(tier, p),
                    p -> p.mapColor(glass ? MapColor.COLOR_LIGHT_GRAY : MapColor.TERRACOTTA_BROWN).strength(1.5F, 6.0F)
                            .sound(glass ? SoundType.GLASS : SoundType.STONE).noOcclusion().pushReaction(PushReaction.POPPED));
            PIN_INSULATORS.put(tier, pinInsulator);
            TAB_ITEMS.add(ITEMS.registerSimpleBlockItem(pinInsulator));
        }
        for (VoltageTier tier : VoltageTier.values()) {
            COILS.put(tier, ITEMS.registerItem(tier.id() + "_wire_coil", p -> new WireCoilItem(tier, GridEdge.Kind.BARE, p), p -> p.stacksTo(64)));
            TAB_ITEMS.add(COILS.get(tier));
            if (tier.insulatable()) {
                INSULATED_COILS.put(tier, ITEMS.registerItem(tier.id() + "_insulated_wire_coil", p -> new WireCoilItem(tier, GridEdge.Kind.INSULATED, p),
                        p -> p.stacksTo(64)));
                TAB_ITEMS.add(INSULATED_COILS.get(tier));
                UNDERGROUND_CABLES.put(tier, ITEMS.registerItem(tier.id() + "_underground_cable", p -> new WireCoilItem(tier, GridEdge.Kind.BURIED, p),
                        p -> p.stacksTo(64)));
                TAB_ITEMS.add(UNDERGROUND_CABLES.get(tier));
            }
        }
        SHIELD_WIRE_COIL = ITEMS.registerItem("shield_wire_coil", p -> new WireCoilItem(null, GridEdge.Kind.SHIELD, p), p -> p.stacksTo(64));
        TAB_ITEMS.add(SHIELD_WIRE_COIL);
    }

    public static final DeferredBlock<PoleTransformerBlock> POLE_TRANSFORMER = BLOCKS.registerBlock("pole_transformer", PoleTransformerBlock::new,
            p -> p.mapColor(MapColor.METAL).strength(3.0F, 6.0F).sound(SoundType.METAL).noOcclusion().pushReaction(PushReaction.POPPED));
    public static final DeferredItem<BlockItem> POLE_TRANSFORMER_ITEM = ITEMS.registerSimpleBlockItem(POLE_TRANSFORMER);
    public static final DeferredItem<Item> BURNT_WIRE = ITEMS.registerSimpleItem("burnt_wire");
    // Lightning protection for the transformers near it.
    public static final DeferredBlock<SurgeArresterBlock> SURGE_ARRESTER = BLOCKS.registerBlock("surge_arrester", SurgeArresterBlock::new,
            p -> p.mapColor(MapColor.TERRACOTTA_BROWN).strength(1.5F, 6.0F).sound(SoundType.STONE).noOcclusion().pushReaction(PushReaction.POPPED));
    public static final DeferredItem<BlockItem> SURGE_ARRESTER_ITEM = ITEMS.registerSimpleBlockItem(SURGE_ARRESTER);
    // The tools and fittings of protection and line work.
    public static final DeferredItem<FuseLinkItem> FUSE_LINK = ITEMS.registerItem("fuse_link", FuseLinkItem::new, p -> p.stacksTo(64));
    public static final DeferredItem<ClampMeterItem> CLAMP_METER = ITEMS.registerItem("clamp_meter", ClampMeterItem::new, p -> p.stacksTo(1));
    public static final DeferredItem<GuyWireItem> GUY_WIRE = ITEMS.registerItem("guy_wire", GuyWireItem::new, p -> p.stacksTo(64));
    // Passes wires through a wall: Pin Insulators on its faces, joined through it by label.
    public static final DeferredBlock<WallBushingBlock> WALL_BUSHING = BLOCKS.registerBlock("wall_bushing", WallBushingBlock::new,
            p -> p.mapColor(MapColor.METAL).strength(4.0F, 6.0F).sound(SoundType.METAL).requiresCorrectToolForDrops());
    public static final DeferredItem<BlockItem> WALL_BUSHING_ITEM = ITEMS.registerSimpleBlockItem(WALL_BUSHING);
    public static final DeferredItem<WireCuttersItem> WIRE_CUTTERS = ITEMS.registerItem("wire_cutters", WireCuttersItem::new, p -> p.durability(238));

    static {
        TAB_ITEMS.add(WIRE_CUTTERS);
        TAB_ITEMS.add(WALL_BUSHING_ITEM);
        TAB_ITEMS.add(SURGE_ARRESTER_ITEM);
        TAB_ITEMS.add(FUSE_LINK);
        TAB_ITEMS.add(CLAMP_METER);
        TAB_ITEMS.add(GUY_WIRE);
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

    public static final Supplier<BlockEntityType<PinInsulatorBlockEntity>> PIN_INSULATOR_ENTITY = BLOCK_ENTITY_TYPES.register("pin_insulator",
            () -> new BlockEntityType<>(PinInsulatorBlockEntity::new, PIN_INSULATORS.values().stream().map(DeferredBlock::get).toArray(PinInsulatorBlock[]::new)));
    public static final Supplier<BlockEntityType<PoleTransformerBlockEntity>> POLE_TRANSFORMER_ENTITY = BLOCK_ENTITY_TYPES.register("pole_transformer",
            () -> new BlockEntityType<>(PoleTransformerBlockEntity::new, POLE_TRANSFORMER.get()));
    public static final Supplier<BlockEntityType<WallBushingBlockEntity>> WALL_BUSHING_ENTITY = BLOCK_ENTITY_TYPES.register("wall_bushing",
            () -> new BlockEntityType<>(WallBushingBlockEntity::new, WALL_BUSHING.get()));
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

    // What a wire gives back when it comes down whole (cut, or an end broken): its coil, bare or insulated; a Wall
    // Bushing's jumper gives nothing.
    public static ItemStack coilFor(GridEdge edge) {
        return switch (edge.kind()) {
            case BARE -> new ItemStack(coil(edge.tier()));
            case INSULATED -> new ItemStack(INSULATED_COILS.containsKey(edge.tier()) ? INSULATED_COILS.get(edge.tier()).get() : coil(edge.tier()));
            case BURIED -> new ItemStack(UNDERGROUND_CABLES.containsKey(edge.tier()) ? UNDERGROUND_CABLES.get(edge.tier()).get() : coil(edge.tier()));
            case SHIELD -> new ItemStack(SHIELD_WIRE_COIL.get());
            case JUMPER -> ItemStack.EMPTY;
        };
    }

    // Everything for the addon's creative tab, after the towers.
    public static List<DeferredItem<? extends Item>> tabItems() {
        return TAB_ITEMS;
    }

    public static void register(IEventBus modEventBus) {
        // EV was renamed EHV: worlds and inventories from before keep their blocks and coils.
        for (String id : List.of("pin_insulator", "creative_source", "creative_sink", "wire_coil")) {
            Identifier from = Identifier.fromNamespaceAndPath(MODID, "ev_" + id), to = Identifier.fromNamespaceAndPath(MODID, "ehv_" + id);
            if (!id.equals("wire_coil")) {
                BLOCKS.addAlias(from, to);
            }
            ITEMS.addAlias(from, to);
        }
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
