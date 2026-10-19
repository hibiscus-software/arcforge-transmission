/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.material;

import java.util.List;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;

// The addon's own material: aluminium (MV's conductor), from bauxite. Bauxite Ore (stone and deepslate) drops Raw
// Bauxite, which smelts to an Aluminium Ingot or crushes to Aluminium Dust in Arcforge's Arc Crusher, like Arcforge's
// own ores; ingots and raw ore pack into blocks. Worldgen is data (worldgen/ and neoforge/biome_modifier/ore_bauxite).
// Textures: tools/material_texture_gen.py.
public final class MaterialContent {
    private static final String MODID = ArcforgeTransmission.MODID;

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);

    public static final DeferredBlock<Block> BAUXITE_ORE = BLOCKS.registerSimpleBlock("bauxite_ore",
            p -> p.mapColor(MapColor.STONE).instrument(NoteBlockInstrument.BASEDRUM).requiresCorrectToolForDrops().strength(3.0F, 3.0F));
    public static final DeferredBlock<Block> DEEPSLATE_BAUXITE_ORE = BLOCKS.registerSimpleBlock("deepslate_bauxite_ore",
            p -> p.mapColor(MapColor.DEEPSLATE).instrument(NoteBlockInstrument.BASEDRUM).requiresCorrectToolForDrops().strength(4.5F, 3.0F)
                    .sound(SoundType.DEEPSLATE));
    public static final DeferredBlock<Block> RAW_BAUXITE_BLOCK = BLOCKS.registerSimpleBlock("raw_bauxite_block",
            p -> p.mapColor(MapColor.COLOR_ORANGE).instrument(NoteBlockInstrument.BASEDRUM).requiresCorrectToolForDrops().strength(5.0F, 6.0F));
    public static final DeferredBlock<Block> ALUMINIUM_BLOCK = BLOCKS.registerSimpleBlock("aluminium_block",
            p -> p.mapColor(MapColor.METAL).requiresCorrectToolForDrops().strength(5.0F, 6.0F).sound(SoundType.METAL));

    public static final DeferredItem<BlockItem> BAUXITE_ORE_ITEM = ITEMS.registerSimpleBlockItem(BAUXITE_ORE);
    public static final DeferredItem<BlockItem> DEEPSLATE_BAUXITE_ORE_ITEM = ITEMS.registerSimpleBlockItem(DEEPSLATE_BAUXITE_ORE);
    public static final DeferredItem<BlockItem> RAW_BAUXITE_BLOCK_ITEM = ITEMS.registerSimpleBlockItem(RAW_BAUXITE_BLOCK);
    public static final DeferredItem<BlockItem> ALUMINIUM_BLOCK_ITEM = ITEMS.registerSimpleBlockItem(ALUMINIUM_BLOCK);
    public static final DeferredItem<Item> RAW_BAUXITE = ITEMS.registerSimpleItem("raw_bauxite");
    public static final DeferredItem<Item> ALUMINIUM_DUST = ITEMS.registerSimpleItem("aluminium_dust");
    public static final DeferredItem<Item> ALUMINIUM_INGOT = ITEMS.registerSimpleItem("aluminium_ingot");

    private MaterialContent() {}

    // For the Materials creative tab, ore to ingot.
    public static List<DeferredItem<? extends Item>> tabItems() {
        return List.of(BAUXITE_ORE_ITEM, DEEPSLATE_BAUXITE_ORE_ITEM, RAW_BAUXITE, RAW_BAUXITE_BLOCK_ITEM, ALUMINIUM_DUST, ALUMINIUM_INGOT,
                ALUMINIUM_BLOCK_ITEM);
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
    }
}
