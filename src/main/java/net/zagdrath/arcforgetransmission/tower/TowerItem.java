/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.tower;

import java.util.List;
import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

// Places a whole transmission tower from its base: standing on the block where it's placed, facing the way the player
// faces (the line's direction), every block it reaches into replaceable (and inside the world border and build height)
// or nothing is placed and the first block in the way is named. The main block goes at a pole's or leg's foot, the rest
// are its invisible parts.
public class TowerItem extends BlockItem {
    private final TowerTier tier;

    public TowerItem(Block block, TowerTier tier, Item.Properties properties) {
        super(block, properties);
        this.tier = tier;
    }

    @Override
    public InteractionResult place(BlockPlaceContext context) {
        if (!context.canPlace()) {
            return InteractionResult.FAIL;
        }
        Level level = context.getLevel();
        BlockPos centre = context.getClickedPos();
        Direction facing = context.getHorizontalDirection();
        List<BlockPos> positions = TowerPlacement.positions(tier, facing, centre);
        for (BlockPos pos : positions) {
            if (!level.getBlockState(pos).canBeReplaced(context) || !level.getWorldBorder().isWithinBounds(pos) || level.isOutsideBuildHeight(pos)) {
                Player player = context.getPlayer();
                if (player != null && !level.isClientSide()) {
                    player.sendOverlayMessage(Component.translatable("message.arcforgetransmission.tower.no_room").append(" ")
                            .append(Component.translatable("message.arcforgetransmission.tower.no_room_at", pos.toShortString())));
                }
                return InteractionResult.FAIL;
            }
        }
        if (!level.isClientSide()) {
            BlockPos main = positions.get(0);
            BlockState state = getBlock().defaultBlockState().setValue(TowerBlock.FACING, facing);
            level.setBlock(main, state, Block.UPDATE_ALL);
            BlockState part = TowerContent.TOWER_PART.get().defaultBlockState()
                    .setValue(TowerPartBlock.STEEL, tier == TowerTier.EV);
            for (BlockPos pos : positions) {
                if (pos.equals(main)) {
                    continue;
                }
                level.setBlock(pos, part, Block.UPDATE_ALL | Block.UPDATE_KNOWN_SHAPE);
                if (level.getBlockEntity(pos) instanceof TowerPartBlockEntity bounding) {
                    bounding.setMain(main);
                }
            }
            level.playSound(null, centre, state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 0.8F);
        }
        context.getItemInHand().consume(1, context.getPlayer());
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
        builder.accept(Component.translatable("tooltip.arcforgetransmission.tower.tier", tier.label(), String.format("%,d", tier.volts()))
                .withStyle(ChatFormatting.GRAY));
        builder.accept(Component.translatable("tooltip.arcforgetransmission.tower.height", tier.height()).withStyle(ChatFormatting.GRAY));
        super.appendHoverText(stack, context, display, builder, flag);
    }
}
