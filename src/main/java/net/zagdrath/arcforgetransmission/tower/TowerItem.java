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
        // Facing the way the player looks, to the nearest eighth of a turn (as a sign); square on for what serves a block.
        float yaw = context.getPlayer() != null ? context.getPlayer().getYRot() : context.getRotation();
        int eighths = Math.floorMod(Math.round((yaw - 180.0F) / 45.0F), 8);
        if (tier.servesBlock()) {
            eighths = TowerPlacement.turns(context.getHorizontalDirection()) * 2;
        }
        // A fuse cutout hangs on what it was put against: facing it, square on.
        if (tier.kind() == TowerTier.Kind.FUSE && context.getClickedFace().getAxis().isHorizontal()) {
            eighths = TowerPlacement.turns(context.getClickedFace().getOpposite()) * 2;
        }
        List<BlockPos> positions = TowerPlacement.positions(tier, eighths, centre);
        for (BlockPos pos : positions) {
            if (!level.getBlockState(pos).canBeReplaced(context) || !level.getWorldBorder().isWithinBounds(pos) || level.isOutsideBuildHeight(pos)) {
                Player player = context.getPlayer();
                if (player != null && !level.isClientSide()) {
                    player.sendOverlayMessage(Component.translatable("message.arcforgetransmission.tower.no_room", getBlock().getName(),
                            pos.toShortString()));
                }
                return InteractionResult.FAIL;
            }
        }
        if (!level.isClientSide()) {
            BlockState state = build(level, getBlock(), tier, centre, eighths);
            level.playSound(null, centre, state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 0.8F);
        }
        context.getItemInHand().consume(1, context.getPlayer());
        return InteractionResult.SUCCESS;
    }

    // Puts a structure up standing on centre, oriented eighths of a turn from north (its room already checked): the main
    // block and its parts. Returns the main block's state. (The game tests build with it too.)
    public static BlockState build(Level level, Block block, TowerTier tier, BlockPos centre, int eighths) {
        List<BlockPos> positions = TowerPlacement.positions(tier, eighths, centre);
        BlockPos main = positions.get(0);
        BlockState state = TowerPlacement.oriented(block.defaultBlockState(), eighths);
        level.setBlock(main, state, Block.UPDATE_ALL);
        BlockState part = TowerContent.TOWER_PART.get().defaultBlockState().setValue(TowerPartBlock.STEEL, tier.steel());
        for (BlockPos pos : positions) {
            if (pos.equals(main)) {
                continue;
            }
            level.setBlock(pos, part, Block.UPDATE_ALL | Block.UPDATE_KNOWN_SHAPE);
            if (level.getBlockEntity(pos) instanceof TowerPartBlockEntity bounding) {
                bounding.setMain(main);
            }
        }
        return state;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
        if (tier.kind() == TowerTier.Kind.SUBSTATION) {
            builder.accept(Component.translatable("tooltip.arcforgetransmission.tower.substation", tier.secondary().name(),
                    String.format("%,d", tier.secondary().volts()), tier.label(), String.format("%,d", tier.volts())).withStyle(ChatFormatting.GRAY));
        } else if (tier.kind() != TowerTier.Kind.RELAY) {
            builder.accept(Component.translatable("tooltip.arcforgetransmission.tower." + (tier.kind() == TowerTier.Kind.UNIT ? "unit" : "tier"),
                    tier.label(), String.format("%,d", tier.volts())).withStyle(ChatFormatting.GRAY));
        }
        builder.accept(Component.translatable("tooltip.arcforgetransmission.tower.height", tier.height()).withStyle(ChatFormatting.GRAY));
        super.appendHoverText(stack, context, display, builder, flag);
    }
}
