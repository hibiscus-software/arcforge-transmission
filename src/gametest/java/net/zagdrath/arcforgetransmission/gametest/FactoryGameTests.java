/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.gametest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.factory.CableTerminationBlock;
import net.zagdrath.arcforgetransmission.factory.CableTerminationBlockEntity;
import net.zagdrath.arcforgetransmission.factory.CableTerminationPartBlock;
import net.zagdrath.arcforgetransmission.factory.FactoryContent;
import net.zagdrath.arcforgetransmission.grid.ConnectionRules;
import net.zagdrath.arcforgetransmission.grid.FlowSolver;
import net.zagdrath.arcforgetransmission.grid.GridEdge;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.block.PinInsulatorMode;
import net.zagdrath.arcforgetransmission.grid.world.GridData;
import net.zagdrath.arcforgetransmission.grid.world.GridManager;

// Factory distribution in a running world (./gradlew runGameTestServer), built from the real blocks:
//  - Cable Terminations: an LV termination on a filled energy cell feeds one on an empty cell over a tray cable (three
//    conductors, A to A, B to B, C to C); a tall HV termination stands into its parts and takes them with it when it
//    goes; an MV termination switched on against an LV-only block overvoltages it.
// Each test stands on a stone floor in an empty structure, with room round it.
@EventBusSubscriber(modid = ArcforgeTransmission.MODID)
public final class FactoryGameTests {
    private static final String MODID = ArcforgeTransmission.MODID;
    private static final Map<String, Consumer<GameTestHelper>> TESTS = new LinkedHashMap<>();

    static {
        TESTS.put("cable_terminations_carry_a_circuit", FactoryGameTests::terminationsCarryACircuit);
        TESTS.put("tall_terminations_stand_into_their_parts", FactoryGameTests::tallTerminations);
        TESTS.put("termination_above_lv_overvoltages_a_foreign_block", FactoryGameTests::terminationOvervoltage);
        TESTS.put("every_item_has_a_name", FactoryGameTests::everyItemNamed);
        TESTS.put("terminations_place_on_every_face", FactoryGameTests::placeOnEveryFace);
    }

    private FactoryGameTests() {}

    @SubscribeEvent
    static void registerFunctions(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> TESTS.forEach((name, test) -> helper.register(id(name), test)));
    }

    @SubscribeEvent
    static void registerTests(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(id("factory"));
        TestData<Holder<TestEnvironmentDefinition<?>>> data = new TestData<>(environment, Level.OVERWORLD, Identifier.withDefaultNamespace("empty"), 1600, 0,
                true, Rotation.NONE, false, 1, 1, true, 48);
        TESTS.keySet().forEach(name -> event.registerTest(id(name), new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id(name)), data)));
    }

    static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MODID, path);
    }

    // --- Building ---------------------------------------------------------------------------------------------------

    // A stone floor under the test, x from -4 to size and z from -8 to 16, the space above it cleared (these tests build
    // past their structure's bounds, where an earlier test's blocks may still stand).
    static void floor(GameTestHelper helper, int size) {
        // The test only keeps the chunks round its (one-block) structure loaded: keep the whole floor's, or a machine out
        // at its far end is in an unloaded chunk (and a unit transformer there finds no FE block).
        BlockPos lo = helper.absolutePos(new BlockPos(-4, 0, -8)), hi = helper.absolutePos(new BlockPos(size, 0, 16));
        for (int cx = Math.min(lo.getX(), hi.getX()) >> 4; cx <= Math.max(lo.getX(), hi.getX()) >> 4; cx++) {
            for (int cz = Math.min(lo.getZ(), hi.getZ()) >> 4; cz <= Math.max(lo.getZ(), hi.getZ()) >> 4; cz++) {
                helper.getLevel().setChunkForced(cx, cz, true);
            }
        }
        for (int x = -4; x <= size; x++) {
            for (int z = -8; z <= 16; z++) {
                for (int y = 8; y >= 1; y--) {
                    BlockPos pos = helper.absolutePos(new BlockPos(x, y, z));
                    if (!helper.getLevel().getBlockState(pos).isAir()) {
                        helper.getLevel().setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
                    }
                }
                helper.getLevel().setBlock(helper.absolutePos(new BlockPos(x, 0, z)), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    // An Arcforge block at a test position.
    static BlockPos arcforge(GameTestHelper helper, String id, BlockPos at) {
        BlockPos pos = helper.absolutePos(at);
        helper.getLevel().setBlock(pos, BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("arcforge", id)).defaultBlockState(),
                Block.UPDATE_ALL);
        return pos;
    }

    static void fill(GameTestHelper helper, BlockPos pos) {
        EnergyHandler handler = helper.getLevel().getCapability(Capabilities.Energy.BLOCK, pos, null);
        for (int i = 0; handler != null && i < 64; i++) {
            try (var tx = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) {
                int got = handler.insert(Integer.MAX_VALUE, tx);
                tx.commit();
                if (got <= 0) {
                    break;
                }
            }
        }
    }

    static long stored(GameTestHelper helper, BlockPos pos) {
        EnergyHandler handler = helper.getLevel().getCapability(Capabilities.Energy.BLOCK, pos, null);
        return handler == null ? -1 : handler.getAmountAsLong();
    }

    // A Cable Termination of a tier mounted on a block (absolute), pointing `facing` away from it, placed as a player
    // would (its parts too); its block entity.
    static CableTerminationBlockEntity terminate(GameTestHelper helper, VoltageTier tier, BlockPos mount, Direction facing) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = mount.relative(facing);
        CableTerminationBlock block = FactoryContent.TERMINATIONS.get(tier).get();
        BlockState state = block.defaultBlockState().setValue(CableTerminationBlock.FACING, facing);
        level.setBlock(pos, state, Block.UPDATE_ALL);
        block.setPlacedBy(level, pos, state, null, new ItemStack(block));
        if (level.getBlockEntity(pos) instanceof CableTerminationBlockEntity termination) {
            return termination;
        }
        throw new IllegalStateException("No termination at " + pos);
    }

    // A tray cable (three conductors) `length` blocks between two terminations; fails the test if refused.
    static List<Long> cable(GameTestHelper helper, CableTerminationBlockEntity a, CableTerminationBlockEntity b, double length) {
        ServerLevel level = helper.getLevel();
        GridData.Dimension dimension = GridData.of(level);
        ConnectionRules.Refusal[] why = new ConnectionRules.Refusal[1];
        List<Long> edges = GridManager.connectCircuit(level, a.nodes(dimension), b.nodes(dimension), GridEdge.Kind.CABLE, length, 1, 1.0, why);
        if (edges.isEmpty()) {
            helper.fail("Cable refused: " + why[0]);
        }
        return edges;
    }

    // --- The tests ----------------------------------------------------------------------------------------------------

    // A player places a termination of each tier from its item on each face of a block: on top, on each side and under.
    private static void placeOnEveryFace(GameTestHelper helper) {
        floor(helper, 40);
        ServerLevel level = helper.getLevel();
        net.minecraft.world.entity.player.Player player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        List<String> failed = new java.util.ArrayList<>();
        int x = 2;
        for (VoltageTier tier : VoltageTier.values()) {
            for (Direction face : Direction.values()) {
                BlockPos mount = helper.absolutePos(new BlockPos(x, 4, 4));
                level.setBlock(mount, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                // The player stands just off the face (beside it, for the top and bottom), as one placing it would.
                BlockPos stand = mount.relative(face, 2);
                if (face.getAxis() == Direction.Axis.Y) {
                    stand = mount.north(2).below(face == Direction.UP ? 0 : 2);
                }
                player.setPos(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
                // Looking east: on top or under, it's turned (its row of conductors north to south).
                player.setYRot(-90.0F);
                ItemStack stack = new ItemStack(FactoryContent.TERMINATIONS.get(tier).get());
                player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack);
                net.minecraft.world.phys.Vec3 hit = net.minecraft.world.phys.Vec3.atCenterOf(mount).add(face.getStepX() * 0.5, face.getStepY() * 0.5,
                        face.getStepZ() * 0.5);
                var result = stack.useOn(new net.minecraft.world.item.context.UseOnContext(player, net.minecraft.world.InteractionHand.MAIN_HAND,
                        new net.minecraft.world.phys.BlockHitResult(hit, face, mount, false)));
                BlockState placed = level.getBlockState(mount.relative(face));
                if (!(placed.getBlock() instanceof CableTerminationBlock) || placed.getValue(CableTerminationBlock.FACING) != face
                        || placed.getValue(CableTerminationBlock.TURNED) != face.getAxis().isVertical()) {
                    BlockState would = FactoryContent.TERMINATIONS.get(tier).get().defaultBlockState().setValue(CableTerminationBlock.FACING, face);
                    failed.add(tier + " " + face + " (" + result + ", its collision " + would.getCollisionShape(level, mount.relative(face)).bounds() + ")");
                }
                x += 5;
                if (x > 38) {
                    x = 2;
                }
                level.setBlock(mount.relative(face), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        helper.assertTrue(failed.isEmpty(), "Not placed: " + failed);
        helper.succeed();
    }

    // Every item the addon registers has its name in the lang file under the key it really uses (a block's item may use
    // its block's key or its own, by how it was registered).
    private static void everyItemNamed(GameTestHelper helper) {
        net.minecraft.locale.Language language = net.minecraft.locale.Language.getInstance();
        helper.assertTrue(language.has("block." + ArcforgeTransmission.MODID + ".lv_cable_termination"), "The addon's lang file isn't loaded");
        List<String> missing = new java.util.ArrayList<>();
        for (var item : BuiltInRegistries.ITEM) {
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            if (id.getNamespace().equals(ArcforgeTransmission.MODID) && !language.has(item.getDescriptionId())) {
                missing.add(item.getDescriptionId());
            }
        }
        helper.assertTrue(missing.isEmpty(), "Items with no name: " + missing);
        helper.succeed();
    }

    // An LV termination on the north face of a filled energy cell (Input), a tray cable to one on top of an empty cell
    // (Output): the cable is three conductors, and the empty cell fills.
    private static void terminationsCarryACircuit(GameTestHelper helper) {
        floor(helper, 16);
        BlockPos full = arcforge(helper, "tempered_energy_cell", new BlockPos(2, 1, 4));
        fill(helper, full);
        BlockPos empty = arcforge(helper, "tempered_energy_cell", new BlockPos(10, 1, 4));
        CableTerminationBlockEntity from = terminate(helper, VoltageTier.LV, full, Direction.NORTH);
        CableTerminationBlockEntity to = terminate(helper, VoltageTier.LV, empty, Direction.UP);
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    from.setForPort(PinInsulatorMode.INPUT, 8);
                    to.setForPort(PinInsulatorMode.OUTPUT, 8);
                    List<Long> edges = cable(helper, from, to, 12);
                    helper.assertTrue(edges.size() == 3, "A cable is three conductors, not " + edges.size());
                })
                .thenWaitUntil(() -> helper.assertTrue(stored(helper, empty) > 0,
                        "The empty cell didn't fill: " + from.status() + " / " + to.status() + ", " + to.lastFlow() + " FE/t"))
                .thenExecute(() -> {
                    helper.assertTrue(from.status() == FlowSolver.PortStatus.OK && to.status() == FlowSolver.PortStatus.OK,
                            "Statuses " + from.status() + " / " + to.status());
                    // Each conductor carries its phase's share: the cable reads the same current on all three.
                    GridData.Dimension dimension = GridData.of(helper.getLevel());
                    var amps = GridManager.lastAmps(helper.getLevel());
                    List<Long> nodes = from.nodes(dimension);
                    double a = amps.getOrDefault(dimension.graph().edgesOf(nodes.get(0)).get(0).id(), 0.0);
                    for (long node : nodes) {
                        double each = amps.getOrDefault(dimension.graph().edgesOf(node).get(0).id(), 0.0);
                        helper.assertTrue(a > 0 && Math.abs(each - a) < 1.0E-6 * Math.max(1, a), "Unbalanced: " + each + " against " + a);
                    }
                })
                .thenSucceed();
    }

    // An HV termination on top of a block stands two blocks tall (a part above it), an EHV one hanging under a block three
    // (two parts below it); breaking one takes its parts.
    private static void tallTerminations(GameTestHelper helper) {
        floor(helper, 12);
        ServerLevel level = helper.getLevel();
        BlockPos base = helper.absolutePos(new BlockPos(2, 1, 4));
        level.setBlock(base, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        CableTerminationBlockEntity hv = terminate(helper, VoltageTier.HV, base, Direction.UP);
        BlockPos ceiling = helper.absolutePos(new BlockPos(6, 5, 4));
        level.setBlock(ceiling, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        CableTerminationBlockEntity ehv = terminate(helper, VoltageTier.EHV, ceiling, Direction.DOWN);
        helper.startSequence()
                .thenIdle(1)
                .thenExecute(() -> {
                    helper.assertTrue(level.getBlockState(hv.getBlockPos().above()).getBlock() instanceof CableTerminationPartBlock, "No HV part above");
                    for (int step = 1; step <= 2; step++) {
                        BlockState part = level.getBlockState(ehv.getBlockPos().below(step));
                        helper.assertTrue(part.getBlock() instanceof CableTerminationPartBlock && part.getValue(CableTerminationPartBlock.HANGING)
                                && part.getValue(CableTerminationPartBlock.STEP) == step, "No EHV part " + step + " below: " + part);
                    }
                    helper.assertTrue(hv.insulators().size() == 3 && ehv.insulators().size() == 3, "A termination is three conductors");
                    // Breaking a part breaks the whole termination.
                    level.destroyBlock(ehv.getBlockPos().below(2), false);
                    level.destroyBlock(hv.getBlockPos(), false);
                })
                .thenExecuteAfter(2, () -> {
                    helper.assertTrue(level.getBlockState(hv.getBlockPos().above()).isAir(), "The HV part stayed");
                    helper.assertTrue(level.getBlockState(ceiling.below()).isAir() && level.getBlockState(ceiling.below(2)).isAir(),
                            "The EHV termination or its part stayed");
                })
                .thenSucceed();
    }

    // An MV termination on a wrought (LV-only) energy cell, cabled to another MV termination, switched to Output: the cell
    // is an overvoltage, and goes. The same at LV is fine.
    private static void terminationOvervoltage(GameTestHelper helper) {
        floor(helper, 16);
        ServerLevel level = helper.getLevel();
        BlockPos lvOnly = arcforge(helper, "wrought_energy_cell", new BlockPos(2, 1, 4));
        BlockPos stone = helper.absolutePos(new BlockPos(8, 1, 4));
        level.setBlock(stone, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        CableTerminationBlockEntity mv = terminate(helper, VoltageTier.MV, lvOnly, Direction.UP);
        CableTerminationBlockEntity far = terminate(helper, VoltageTier.MV, stone, Direction.UP);
        BlockPos lvCell = arcforge(helper, "wrought_energy_cell", new BlockPos(2, 1, 8));
        BlockPos stone2 = helper.absolutePos(new BlockPos(8, 1, 8));
        level.setBlock(stone2, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        CableTerminationBlockEntity lv = terminate(helper, VoltageTier.LV, lvCell, Direction.UP);
        CableTerminationBlockEntity lvFar = terminate(helper, VoltageTier.LV, stone2, Direction.UP);
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    cable(helper, mv, far, 6);
                    cable(helper, lv, lvFar, 6);
                    mv.setForPort(PinInsulatorMode.OUTPUT, 8);
                    lv.setForPort(PinInsulatorMode.OUTPUT, 8);
                })
                .thenWaitUntil(() -> helper.assertTrue(!level.getBlockState(lvOnly).getBlock().getDescriptionId().contains("energy_cell"),
                        "The LV-only cell under the MV termination survived"))
                .thenExecuteAfter(5, () -> helper.assertTrue(level.getBlockState(lvCell).getBlock().getDescriptionId().contains("energy_cell"),
                        "The cell under the LV termination went"))
                .thenSucceed();
    }
}
