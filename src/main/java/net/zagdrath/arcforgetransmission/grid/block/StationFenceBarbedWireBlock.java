/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.block;

import java.util.Locale;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.grid.world.GridConfig;

// Station Fence Barbed Wire: a slim galvanized outrigger on top of a Station Fence, leaning outward (FACING, the side away
// from the yard; set as you place it by the way you look, and turned with the Wrench on Rotate) with three strands of
// barbed wire. It stands only on a Station Fence and pops off when the fence goes. Its strands join the barbed wire either
// side along a run, and wrap a corner where the fence turns: each side is NONE, RUN (along its run, or straight on), or on
// the in or out side LEFT / RIGHT (the neighbour there faces that way of this one; the model wraps round to its strands).
// Its collision fills the block above the fence (less 1 px each side, as a cactus's: touching it is being inside it), so
// nothing jumps the fence, and it pricks what touches it (GridConfig.BARBED_WIRE_DAMAGE); its outline is the slim wire.
// The models come from tools/grid_block_model_gen.py.
public class StationFenceBarbedWireBlock extends Block {
    public enum Side implements StringRepresentable {
        NONE, RUN, LEFT, RIGHT;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
    public static final EnumProperty<Side> NORTH = EnumProperty.create("north", Side.class);
    public static final EnumProperty<Side> EAST = EnumProperty.create("east", Side.class);
    public static final EnumProperty<Side> SOUTH = EnumProperty.create("south", Side.class);
    public static final EnumProperty<Side> WEST = EnumProperty.create("west", Side.class);
    private static final Map<Direction, EnumProperty<Side>> SIDES = Map.of(Direction.NORTH, NORTH, Direction.EAST, EAST, Direction.SOUTH, SOUTH,
            Direction.WEST, WEST);
    public static final ResourceKey<DamageType> BARBED_WIRE = ResourceKey.create(Registries.DAMAGE_TYPE,
            Identifier.fromNamespaceAndPath(ArcforgeTransmission.MODID, "barbed_wire"));

    private static final VoxelShape COLLISION = Block.box(1, 0, 1, 15, 16, 15);
    // The outline's pieces facing north (px, as the generator draws them): the outrigger and its hubs, the strands one
    // way for each kind of side, and a lone block's short ends.
    private static final double[] POST = { 6.5, 0, 1, 9.5, 7, 9.5 }, LONE = { 6, 1, 1, 10, 7, 7 };
    private static final Map<String, double[]> PIECES = Map.of("left", new double[] { 0, 1, 0.5, 8, 7, 7.5 }, "right",
            new double[] { 8, 1, 0.5, 16, 7, 7.5 }, "in_run", new double[] { 6.5, 1, 0.5, 9.5, 7, 16 }, "out_run",
            new double[] { 6.5, 1, 0, 9.5, 7, 7.5 }, "in_left", new double[] { 0.5, 1, 1, 8, 7, 16 }, "in_right",
            new double[] { 8, 1, 1, 15.5, 7, 16 }, "out_left", new double[] { 0.5, 1, 0, 8, 7, 7.5 }, "out_right",
            new double[] { 8, 1, 0, 15.5, 7, 7.5 });
    private final Map<BlockState, VoxelShape> outlines = new java.util.concurrent.ConcurrentHashMap<>();

    public StationFenceBarbedWireBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(NORTH, Side.NONE).setValue(EAST, Side.NONE)
                .setValue(SOUTH, Side.NONE).setValue(WEST, Side.NONE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, NORTH, EAST, SOUTH, WEST);
    }

    // What lies on `side` of a block facing `facing`, given the block there.
    public static Side sideFor(Direction facing, Direction side, BlockState neighbour) {
        if (!(neighbour.getBlock() instanceof StationFenceBarbedWireBlock)) {
            return Side.NONE;
        }
        if (side.getAxis() != facing.getAxis()) {
            return Side.RUN;
        }
        Direction other = neighbour.getValue(FACING);
        if (other.getAxis() == facing.getAxis()) {
            return Side.RUN;
        }
        return other == facing.getClockWise() ? Side.RIGHT : Side.LEFT;
    }

    // The state with every side worked out from its neighbours.
    public static BlockState connected(BlockState state, BlockGetter level, BlockPos pos) {
        Direction facing = state.getValue(FACING);
        for (Map.Entry<Direction, EnumProperty<Side>> side : SIDES.entrySet()) {
            state = state.setValue(side.getValue(), sideFor(facing, side.getKey(), level.getBlockState(pos.relative(side.getKey()))));
        }
        return state;
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = defaultBlockState().setValue(FACING, context.getHorizontalDirection());
        return state.canSurvive(context.getLevel(), context.getClickedPos()) ? connected(state, context.getLevel(), context.getClickedPos()) : null;
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return level.getBlockState(pos.below()).getBlock() instanceof StationFenceBlock;
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction direction,
            BlockPos neighborPos, BlockState neighborState, RandomSource random) {
        if (direction == Direction.DOWN && !canSurvive(state, level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        EnumProperty<Side> side = SIDES.get(direction);
        return side == null ? state : state.setValue(side, sideFor(state.getValue(FACING), direction, neighborState));
    }

    // Turned in place (the Wrench sets FACING alone): its sides follow its new facing.
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        BlockState fixed = connected(state, level, pos);
        if (fixed != state) {
            level.setBlock(pos, fixed, Block.UPDATE_ALL);
        }
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        BlockState turned = state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
        for (Map.Entry<Direction, EnumProperty<Side>> side : SIDES.entrySet()) {
            turned = turned.setValue(SIDES.get(rotation.rotate(side.getKey())), state.getValue(side.getValue()));
        }
        return turned;
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return rotate(state, mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return COLLISION;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return outlines.computeIfAbsent(state, StationFenceBarbedWireBlock::outline);
    }

    // The slim outline: the outrigger, and the strands each side has, turned to its facing.
    private static VoxelShape outline(BlockState state) {
        Direction facing = state.getValue(FACING);
        VoxelShape shape = box(POST, facing);
        boolean any = false;
        String[] relative = { "out", "right", "in", "left" };
        for (int r = 0; r < 4; r++) {
            Direction side = Direction.from2DDataValue((facing.get2DDataValue() + r) % 4);
            Side value = state.getValue(SIDES.get(side));
            if (value == Side.NONE) {
                continue;
            }
            any = true;
            String rel = relative[r];
            String kind = rel.equals("left") || rel.equals("right") ? rel : rel + "_" + value.getSerializedName();
            shape = Shapes.or(shape, box(PIECES.get(kind), facing));
        }
        return any ? shape : Shapes.or(shape, box(LONE, facing));
    }

    // A box drawn facing north, turned clockwise to `facing` about the block's middle.
    private static VoxelShape box(double[] b, Direction facing) {
        double x0 = b[0], z0 = b[2], x1 = b[3], z1 = b[5];
        for (int i = 0; i < (facing.get2DDataValue() + 2) % 4; i++) {
            double nx0 = 16 - z1, nx1 = 16 - z0, nz0 = x0, nz1 = x1;
            x0 = nx0;
            x1 = nx1;
            z0 = nz0;
            z1 = nz1;
        }
        return Block.box(x0, b[1], z0, x1, b[4], z1);
    }

    // Touching it pricks (as a sweet berry bush does), unless turned off.
    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity, InsideBlockEffectApplier effectApplier, boolean isPrecise) {
        double damage = GridConfig.BARBED_WIRE_DAMAGE.getAsDouble();
        if (damage > 0 && level instanceof ServerLevel server && entity instanceof LivingEntity) {
            entity.hurtServer(server, new DamageSource(server.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(BARBED_WIRE)), (float) damage);
        }
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }
}
