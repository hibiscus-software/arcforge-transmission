/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.factory;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.block.GridInteractions;
import net.zagdrath.arcforgetransmission.grid.world.GridManager;
import net.zagdrath.arcforgetransmission.tower.model.FactoryMesh.Section;

// A switchgear lineup's section (metal-enclosed at LV and MV, a gas-insulated bay at HV and EHV): its main block, at the
// model's origin; the rest of its cells are SectionPartBlocks, placed with it (SectionItem) and gone with it. FACING is
// the way its front faces. Its handles work by hand (an empty hand on the one nearest the click), its breakers by
// redstone into the block each is in, and each gives a comparator its load. See SectionBlockEntity.
public class SectionBlock extends BaseEntityBlock {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

    private final VoltageTier tier;
    private final Section section;

    public SectionBlock(VoltageTier tier, Section section, BlockBehaviour.Properties properties) {
        super(properties);
        this.tier = tier;
        this.section = section;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    public VoltageTier tier() {
        return tier;
    }

    public Section section() {
        return section;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    // Its front toward the player.
    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SectionGeometry.shape(section, tier.ordinal(), state.getValue(FACING), new int[] { 0, 0, 0 });
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    // Its parts go with it.
    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
        for (BlockPos cell : SectionGeometry.worldCells(section, tier.ordinal(), pos, state.getValue(FACING))) {
            if (!cell.equals(pos) && level.getBlockState(cell).getBlock() instanceof SectionPartBlock) {
                level.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    // A neighbour changed: the lineup may have, and a redstone signal may have.
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, @Nullable Orientation orientation, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, block, orientation, movedByPiston);
        if (level instanceof ServerLevel server && level.getBlockEntity(pos) instanceof SectionBlockEntity section) {
            section.neighbourChanged(server);
        }
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, Direction direction) {
        return level.getBlockEntity(pos) instanceof SectionBlockEntity section ? section.comparator(pos) : 0;
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
            BlockHitResult hit) {
        return use(stack, level, pos, player, hit);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        return use(ItemStack.EMPTY, level, pos, player, hit);
    }

    // The Wrench (GridInteractions, and the section's own settings) or an empty hand on its handles, from any of its
    // blocks.
    static InteractionResult use(ItemStack stack, Level level, BlockPos main, Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(main) instanceof SectionBlockEntity section)) {
            return InteractionResult.PASS;
        }
        if (GridInteractions.isWrench(stack)) {
            InteractionResult result = section.wrench(stack, level, player, hit.getLocation());
            if (result != null) {
                return result;
            }
            InteractionResult grid = GridInteractions.wrench(stack, level, section, player, hit.getLocation(), false);
            return grid != null ? grid : InteractionResult.PASS;
        }
        // Anything else in hand (a Cable Termination to mount on it, a cable, a block) is the item's to use.
        if (!stack.isEmpty()) {
            return InteractionResult.PASS;
        }
        if (!GridInteractions.mayChange(player, level, main)) {
            return InteractionResult.FAIL;
        }
        if (level instanceof ServerLevel server) {
            player.sendOverlayMessage(section.operate(server, hit.getLocation()));
        }
        return InteractionResult.SUCCESS;
    }

    // Broken by a creative player: its wires don't drop.
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        GridManager.breaking(level, pos, player);
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SectionBlockEntity(pos, state);
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state) {
        return true;
    }

    // Drawn by its block entity: it dims no light and casts no ambient-occlusion shade on its neighbours, however much of
    // its block its shape fills.
    @Override
    protected int getLightDampening(BlockState state) {
        return 0;
    }

    @Override
    protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
        return 1.0F;
    }
}
