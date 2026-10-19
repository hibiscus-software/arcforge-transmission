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
import net.minecraft.world.item.CreativeModeTab;
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
    public static final DeferredBlock<TowerBlock> EV_LATTICE_TOWER = tower(TowerTier.EV, true);
    // The invisible blocks round a tower's main block, shaped like the tower in them. No item, no drops.
    public static final DeferredBlock<TowerPartBlock> TOWER_PART = BLOCKS.registerBlock("transmission_tower_part", TowerPartBlock::new,
            p -> p.mapColor(MapColor.COLOR_BROWN).strength(2.5F, 6.0F).sound(SoundType.WOOD).noOcclusion().noLootTable()
                    .pushReaction(PushReaction.IMMOVEABLE)
                    // Its shape depends on where its main block is, so it can't be cached per state.
                    .dynamicShape());

    public static final DeferredItem<TowerItem> LV_DISTRIBUTION_POLE_ITEM = item(LV_DISTRIBUTION_POLE, TowerTier.LV);
    public static final DeferredItem<TowerItem> MV_SUB_TRANSMISSION_POLE_ITEM = item(MV_SUB_TRANSMISSION_POLE, TowerTier.MV);
    public static final DeferredItem<TowerItem> HV_H_FRAME_TOWER_ITEM = item(HV_H_FRAME_TOWER, TowerTier.HV);
    public static final DeferredItem<TowerItem> EV_LATTICE_TOWER_ITEM = item(EV_LATTICE_TOWER, TowerTier.EV);

    public static final Supplier<BlockEntityType<TowerBlockEntity>> TOWER_ENTITY = BLOCK_ENTITY_TYPES.register("transmission_tower",
            () -> new BlockEntityType<>(TowerBlockEntity::new, LV_DISTRIBUTION_POLE.get(), MV_SUB_TRANSMISSION_POLE.get(), HV_H_FRAME_TOWER.get(),
                    EV_LATTICE_TOWER.get()));
    public static final Supplier<BlockEntityType<TowerPartBlockEntity>> TOWER_PART_ENTITY = BLOCK_ENTITY_TYPES.register("transmission_tower_part",
            () -> new BlockEntityType<>(TowerPartBlockEntity::new, TOWER_PART.get()));

    // The addon's creative tab.
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = CREATIVE_MODE_TABS.register("transmission", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup." + MODID + ".transmission"))
            .icon(() -> EV_LATTICE_TOWER_ITEM.get().getDefaultInstance())
            .displayItems((parameters, output) -> {
                towerItems().forEach(item -> output.accept(item.get()));
                GridContent.tabItems().forEach(item -> output.accept(item.get()));
            })
            .build());

    private TowerContent() {}

    public static List<DeferredItem<TowerItem>> towerItems() {
        return List.of(LV_DISTRIBUTION_POLE_ITEM, MV_SUB_TRANSMISSION_POLE_ITEM, HV_H_FRAME_TOWER_ITEM, EV_LATTICE_TOWER_ITEM);
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
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITY_TYPES.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
    }
}
