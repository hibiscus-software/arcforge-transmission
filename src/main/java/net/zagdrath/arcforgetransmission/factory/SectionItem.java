/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.factory;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

// A lineup section's item: places the whole section (its main block and its parts) with its front toward the player,
// if every block it takes is free.
public class SectionItem extends BlockItem {
    public SectionItem(SectionBlock block, Properties properties) {
        super(block, properties);
    }

    @Override
    protected boolean placeBlock(BlockPlaceContext context, BlockState state) {
        SectionBlock block = (SectionBlock) getBlock();
        Level level = context.getLevel();
        Direction facing = state.getValue(SectionBlock.FACING);
        List<BlockPos> cells = SectionGeometry.worldCells(block.section(), block.tier().ordinal(), context.getClickedPos(), facing);
        for (BlockPos cell : cells) {
            if (!cell.equals(context.getClickedPos()) && !level.getBlockState(cell).canBeReplaced(context)) {
                return false;
            }
        }
        if (!super.placeBlock(context, state)) {
            return false;
        }
        place(level, context.getClickedPos(), block, facing);
        return true;
    }

    // Its parts round a placed main block.
    public static void place(Level level, BlockPos main, SectionBlock block, Direction facing) {
        for (BlockPos cell : SectionGeometry.worldCells(block.section(), block.tier().ordinal(), main, facing)) {
            if (cell.equals(main)) {
                continue;
            }
            level.setBlock(cell, FactoryContent.SECTION_PART.get().defaultBlockState(), Block.UPDATE_ALL);
            if (level.getBlockEntity(cell) instanceof SectionPartBlockEntity part) {
                part.setMain(main);
            }
        }
    }
}
