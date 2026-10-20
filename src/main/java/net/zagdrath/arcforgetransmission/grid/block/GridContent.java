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
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.grid.GridEdge;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.item.LinkTarget;
import net.zagdrath.arcforgetransmission.grid.item.ClampMeterItem;
import net.zagdrath.arcforgetransmission.grid.item.ControlCableItem;
import net.zagdrath.arcforgetransmission.grid.item.FittingItem;
import net.zagdrath.arcforgetransmission.grid.item.FuseLinkItem;
import net.zagdrath.arcforgetransmission.grid.item.GuyWireItem;
import net.zagdrath.arcforgetransmission.grid.item.WireCoilItem;
import net.zagdrath.arcforgetransmission.grid.item.WireCuttersItem;

// Everything the grid registers but the structures (TowerContent): per tier a Pin Insulator, a Wire Coil and Underground
// Cable (and LV and MV insulated coils); Shield Wire; the Pole-Mount and MV Distribution Transformers; the Wall Bushing,
// Surge Arrester, Station Fence and Ground Grid; the CT, PT, Control Cable, Fuse Link, Clamp Meter, Guy Wire and Wire
// Cutters; Burnt Wire; their block entity types; and the coil's loose-end component. Call register(modEventBus) from the
// mod's constructor.
public final class GridContent {
    private static final String MODID = ArcforgeTransmission.MODID;

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
    public static final DeferredRegister.DataComponents DATA_COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MODID);

    public static final Map<VoltageTier, DeferredBlock<PinInsulatorBlock>> PIN_INSULATORS = new EnumMap<>(VoltageTier.class);
    public static final Map<VoltageTier, DeferredItem<WireCoilItem>> COILS = new EnumMap<>(VoltageTier.class);
    // Rubber-insulated wire (Arcforge's rubber): LV and MV only, safe to touch. Underground cable: every tier.
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
            }
            UNDERGROUND_CABLES.put(tier, ITEMS.registerItem(tier.id() + "_underground_cable", p -> new WireCoilItem(tier, GridEdge.Kind.BURIED, p),
                    p -> p.stacksTo(64)));
            TAB_ITEMS.add(UNDERGROUND_CABLES.get(tier));
        }
        SHIELD_WIRE_COIL = ITEMS.registerItem("shield_wire_coil", p -> new WireCoilItem(null, GridEdge.Kind.SHIELD, p), p -> p.stacksTo(64));
        TAB_ITEMS.add(SHIELD_WIRE_COIL);
    }

    public static final DeferredBlock<PoleTransformerBlock> POLE_TRANSFORMER = BLOCKS.registerBlock("pole_transformer",
            p -> new PoleTransformerBlock(VoltageTier.LV, p),
            p -> p.mapColor(MapColor.METAL).strength(3.0F, 6.0F).sound(SoundType.METAL).noOcclusion().pushReaction(PushReaction.POPPED));
    public static final DeferredItem<BlockItem> POLE_TRANSFORMER_ITEM = ITEMS.registerSimpleBlockItem(POLE_TRANSFORMER);
    // The same can with an MV primary: an MV feeder serving houses (split-phase LV) directly.
    public static final DeferredBlock<PoleTransformerBlock> MV_DISTRIBUTION_TRANSFORMER = BLOCKS.registerBlock("mv_distribution_transformer",
            p -> new PoleTransformerBlock(VoltageTier.MV, p),
            p -> p.mapColor(MapColor.METAL).strength(3.0F, 6.0F).sound(SoundType.METAL).noOcclusion().pushReaction(PushReaction.POPPED));
    public static final DeferredItem<BlockItem> MV_DISTRIBUTION_TRANSFORMER_ITEM = ITEMS.registerSimpleBlockItem(MV_DISTRIBUTION_TRANSFORMER);
    // A substation yard: its fence (a fault inside it gives step-potential shocks...) and its ground grid (...unless
    // this lies under it).
    public static final DeferredBlock<StationFenceBlock> STATION_FENCE = BLOCKS.registerBlock("station_fence", StationFenceBlock::new,
            p -> p.mapColor(MapColor.METAL).strength(4.0F, 6.0F).sound(SoundType.CHAIN).requiresCorrectToolForDrops().noOcclusion());
    public static final DeferredItem<BlockItem> STATION_FENCE_ITEM = ITEMS.registerSimpleBlockItem(STATION_FENCE);
    // Barbed wire on top of the fence: nothing jumps it, and touching it pricks.
    public static final DeferredBlock<StationFenceBarbedWireBlock> STATION_FENCE_BARBED_WIRE = BLOCKS.registerBlock("station_fence_barbed_wire",
            StationFenceBarbedWireBlock::new,
            p -> p.mapColor(MapColor.METAL).strength(2.0F, 6.0F).sound(SoundType.CHAIN).requiresCorrectToolForDrops().noOcclusion()
                    .pushReaction(PushReaction.POPPED));
    public static final DeferredItem<BlockItem> STATION_FENCE_BARBED_WIRE_ITEM = ITEMS.registerSimpleBlockItem(STATION_FENCE_BARBED_WIRE);
    public static final DeferredBlock<GroundGridBlock> GROUND_GRID = BLOCKS.registerBlock("ground_grid", GroundGridBlock::new,
            p -> p.mapColor(MapColor.COLOR_ORANGE).strength(1.0F, 6.0F).sound(SoundType.COPPER).noOcclusion());
    public static final DeferredItem<BlockItem> GROUND_GRID_ITEM = ITEMS.registerSimpleBlockItem(GROUND_GRID);
    // Instrument transformers fitted to breakers, transformers and banks (what a Relay Panel reads), and the cable that
    // links a panel to what it protects or a disconnect to its breaker.
    public static final DeferredItem<FittingItem> CURRENT_TRANSFORMER = ITEMS.registerItem("current_transformer", p -> new FittingItem(true, p),
            p -> p.stacksTo(16));
    public static final DeferredItem<FittingItem> POTENTIAL_TRANSFORMER = ITEMS.registerItem("potential_transformer", p -> new FittingItem(false, p),
            p -> p.stacksTo(16));
    public static final DeferredItem<ControlCableItem> CONTROL_CABLE = ITEMS.registerItem("control_cable", ControlCableItem::new, p -> p.stacksTo(64));
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
        TAB_ITEMS.add(MV_DISTRIBUTION_TRANSFORMER_ITEM);
        TAB_ITEMS.add(STATION_FENCE_ITEM);
        TAB_ITEMS.add(STATION_FENCE_BARBED_WIRE_ITEM);
        TAB_ITEMS.add(GROUND_GRID_ITEM);
        TAB_ITEMS.add(CURRENT_TRANSFORMER);
        TAB_ITEMS.add(POTENTIAL_TRANSFORMER);
        TAB_ITEMS.add(CONTROL_CABLE);
        TAB_ITEMS.add(BURNT_WIRE);
    }

    public static final Supplier<BlockEntityType<PinInsulatorBlockEntity>> PIN_INSULATOR_ENTITY = BLOCK_ENTITY_TYPES.register("pin_insulator",
            () -> new BlockEntityType<>(PinInsulatorBlockEntity::new, PIN_INSULATORS.values().stream().map(DeferredBlock::get).toArray(PinInsulatorBlock[]::new)));
    public static final Supplier<BlockEntityType<PoleTransformerBlockEntity>> POLE_TRANSFORMER_ENTITY = BLOCK_ENTITY_TYPES.register("pole_transformer",
            () -> new BlockEntityType<>(PoleTransformerBlockEntity::new, POLE_TRANSFORMER.get(), MV_DISTRIBUTION_TRANSFORMER.get()));
    public static final Supplier<BlockEntityType<WallBushingBlockEntity>> WALL_BUSHING_ENTITY = BLOCK_ENTITY_TYPES.register("wall_bushing",
            () -> new BlockEntityType<>(WallBushingBlockEntity::new, WALL_BUSHING.get()));

    // The loose end of a wire coil.
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LinkTarget>> LINK = DATA_COMPONENTS.registerComponentType("wire_link",
            b -> b.persistent(LinkTarget.CODEC).networkSynchronized(LinkTarget.STREAM_CODEC));

    private GridContent() {}

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
            // A tray cable is dropped whole by its record (FactoryCables), busway is its own blocks; a jumper is nothing.
            case JUMPER, CABLE, BUS -> ItemStack.EMPTY;
        };
    }

    // The grid's items, for the creative tabs (TowerContent sorts them into theirs by id).
    public static List<DeferredItem<? extends Item>> tabItems() {
        return TAB_ITEMS;
    }

    public static void register(IEventBus modEventBus) {
        // EV was renamed EHV: worlds and inventories from before keep their blocks and coils.
        for (String id : List.of("pin_insulator", "wire_coil")) {
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
    }
}
