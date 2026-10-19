/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.block;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.zagdrath.arcforge.item.tool.WrenchItem;
import net.zagdrath.arcforge.item.tool.WrenchMode;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;

// A Creative Source or Sink of one tier (creative tab only): a plain FE block to mount inductors on. The Wrench on
// Configure doubles its setpoint (sneaking: halves it); empty-handed, it says its setpoint and what it has moved.
public class CreativeEnergyBlock extends BaseEntityBlock {
    private final VoltageTier tier;
    private final boolean source;

    public CreativeEnergyBlock(VoltageTier tier, boolean source, BlockBehaviour.Properties properties) {
        super(properties);
        this.tier = tier;
        this.source = source;
    }

    public VoltageTier tier() {
        return tier;
    }

    public boolean isSource() {
        return source;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
            BlockHitResult hit) {
        if (!(stack.getItem() instanceof WrenchItem) || WrenchItem.mode(stack) != WrenchMode.CONFIGURE) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof CreativeEnergyBlockEntity creative) {
            int setpoint = creative.step(!player.isSecondaryUseActive());
            player.sendOverlayMessage(Component.translatable("message." + ArcforgeTransmission.MODID + ".creative.setpoint", setpoint));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof CreativeEnergyBlockEntity creative) {
            player.sendOverlayMessage(Component.translatable("message." + ArcforgeTransmission.MODID + ".creative.status", creative.setpoint(),
                    creative.total()));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CreativeEnergyBlockEntity(pos, state);
    }
}
