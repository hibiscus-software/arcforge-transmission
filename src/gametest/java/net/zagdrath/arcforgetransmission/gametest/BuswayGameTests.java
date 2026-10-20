/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.gametest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.factory.BusPieceBlockEntity;
import net.zagdrath.arcforgetransmission.factory.BuswayBlock;
import net.zagdrath.arcforgetransmission.factory.BuswayBlockEntity;
import net.zagdrath.arcforgetransmission.factory.BuswayFeedInBlock;
import net.zagdrath.arcforgetransmission.factory.BuswayReducerBlock;
import net.zagdrath.arcforgetransmission.factory.BuswayTapOffBlock;
import net.zagdrath.arcforgetransmission.factory.BuswayTapOffBlockEntity;
import net.zagdrath.arcforgetransmission.factory.CableTerminationBlockEntity;
import net.zagdrath.arcforgetransmission.factory.FactoryContent;
import net.zagdrath.arcforgetransmission.factory.SectionBlockEntity;
import net.zagdrath.arcforgetransmission.grid.GridEdge;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.world.GridData;
import net.zagdrath.arcforgetransmission.tower.TowerBlockEntity;
import net.zagdrath.arcforgetransmission.tower.model.FactoryMesh.OpState;
import net.zagdrath.arcforgetransmission.tower.model.FactoryMesh.Section;

// Busway in a running world:
//  - an LV 8W (128 A) bus duct run carries 32,768 FE/t to four 2W (32 A) tap boxes;
//  - an EHV 8W (1,024 A) gas-insulated line carries 16,777,216 FE/t, nothing overflowing;
//  - an overloaded tap box trips before the lineup feeder upstream of it;
//  - sizes and tiers never join, but for a reducer between the right sizes;
//  - a damaged block of busway breaks the circuit.
@EventBusSubscriber(modid = ArcforgeTransmission.MODID)
public final class BuswayGameTests {
    private static final Map<String, Consumer<GameTestHelper>> TESTS = new LinkedHashMap<>();

    static {
        TESTS.put("lv_busway_feeds_four_tap_boxes", BuswayGameTests::lvTapBoxes);
        TESTS.put("ehv_gas_insulated_line_carries_sixteen_million", BuswayGameTests::ehvFull);
        TESTS.put("an_overloaded_tap_trips_before_its_feeder", BuswayGameTests::tapBeforeFeeder);
        TESTS.put("busway_joins_only_its_size_and_tier", BuswayGameTests::sizesAndTiers);
        TESTS.put("a_damaged_busway_breaks_the_circuit", BuswayGameTests::damaged);
    }

    private BuswayGameTests() {}

    @SubscribeEvent
    static void registerFunctions(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> TESTS.forEach((name, test) -> helper.register(FactoryGameTests.id(name), test)));
    }

    @SubscribeEvent
    static void registerTests(RegisterGameTestsEvent event) {
        TESTS.keySet().forEach(name -> {
            Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(FactoryGameTests.id("busway_" + name));
            TestData<Holder<TestEnvironmentDefinition<?>>> data = new TestData<>(environment, Level.OVERWORLD, Identifier.withDefaultNamespace("empty"), 1600,
                    0, true, Rotation.NONE, false, 1, 1, true, 64);
            event.registerTest(FactoryGameTests.id(name), new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, FactoryGameTests.id(name)),
                    data));
        });
    }

    // --- Building ---------------------------------------------------------------------------------------------------

    // A block of busway (its state shaped for its neighbours) at a test position.
    static BuswayBlockEntity run(GameTestHelper helper, VoltageTier tier, int size, BlockPos at) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(at);
        BuswayBlock block = FactoryContent.BUSWAYS.get(tier).get(size).get();
        level.setBlock(pos, block.shaped(level, pos, block.defaultBlockState()), Block.UPDATE_ALL);
        return (BuswayBlockEntity) level.getBlockEntity(pos);
    }

    // A straight run of busway along x from `from`, `length` blocks.
    static List<BuswayBlockEntity> straight(GameTestHelper helper, VoltageTier tier, int size, BlockPos from, int length) {
        List<BuswayBlockEntity> out = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            out.add(run(helper, tier, size, from.east(i)));
        }
        return out;
    }

    static BusPieceBlockEntity feedIn(GameTestHelper helper, VoltageTier tier, BlockPos at, Direction facing) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(at);
        level.setBlock(pos, FactoryContent.FEED_INS.get(tier).get().defaultBlockState().setValue(BuswayFeedInBlock.FACING, facing), Block.UPDATE_ALL);
        return (BusPieceBlockEntity) level.getBlockEntity(pos);
    }

    // A tap-off clipped onto the busway block toward `toward` from `at`, with an LV-or-any-tier Cable Termination on its
    // far face (pointing away from the busway).
    static BuswayTapOffBlockEntity tap(GameTestHelper helper, VoltageTier tier, BlockPos at, Direction toward) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(at);
        level.setBlock(pos, FactoryContent.TAP_OFFS.get(tier).get().defaultBlockState().setValue(BuswayTapOffBlock.FACING, toward), Block.UPDATE_ALL);
        return (BuswayTapOffBlockEntity) level.getBlockEntity(pos);
    }

    static void refresh(GameTestHelper helper, List<? extends BusPieceBlockEntity> pieces) {
        for (BusPieceBlockEntity piece : pieces) {
            piece.rejoin(helper.getLevel());
        }
    }

    static List<Long> nodes(GameTestHelper helper, BusPieceBlockEntity piece, int group) {
        return piece.nodes(GridData.of(helper.getLevel()), group);
    }

    // --- The tests ----------------------------------------------------------------------------------------------------

    // Nine LV sources into a feed-in, an LV 8W run of six blocks, four 2W tap boxes on it, each tap's termination cabled to
    // two LV sinks (8,192 FE/t a tap): 32,768 FE/t reach the sinks, every tap closed and the run whole.
    private static void lvTapBoxes(GameTestHelper helper) {
        FactoryGameTests.floor(helper, 60);
        ServerLevel level = helper.getLevel();
        BusPieceBlockEntity feed = feedIn(helper, VoltageTier.LV, new BlockPos(2, 3, 4), Direction.EAST);
        List<BuswayBlockEntity> bus = straight(helper, VoltageTier.LV, 8, new BlockPos(3, 3, 4), 8);
        List<BuswayTapOffBlockEntity> taps = new ArrayList<>();
        List<CableTerminationBlockEntity> ends = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            BuswayTapOffBlockEntity t = tap(helper, VoltageTier.LV, new BlockPos(4 + 2 * i, 3, 3), Direction.SOUTH);
            taps.add(t);
            ends.add(FactoryGameTests.terminate(helper, VoltageTier.LV, t.getBlockPos(), Direction.NORTH));
        }
        List<TowerBlockEntity> sources = new ArrayList<>(), sinks = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            sources.add(LineupGameTests.source(helper, VoltageTier.LV, 2 + 3 * i, 12));
        }
        for (int i = 0; i < 8; i++) {
            sinks.add(LineupGameTests.sink(helper, VoltageTier.LV, 2 + 3 * i, -4));
        }
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    List<BusPieceBlockEntity> all = new ArrayList<>(bus);
                    all.add(feed);
                    all.addAll(taps);
                    refresh(helper, all);
                    for (TowerBlockEntity s : sources) {
                        LineupGameTests.cable(helper, LineupGameTests.unitNodes(helper, s), nodes(helper, feed, 0), 1);
                    }
                    for (int i = 0; i < 8; i++) {
                        LineupGameTests.cable(helper, ends.get(i / 2).nodes(GridData.of(level)), LineupGameTests.unitNodes(helper, sinks.get(i)), 1);
                    }
                    for (BuswayTapOffBlockEntity t : taps) {
                        t.breaker().setFrame(1);
                        t.operate(level);
                    }
                })
                .thenExecuteAfter(40, () -> {
                    double delivered = sinks.stream().mapToDouble(TowerBlockEntity::lastFlow).sum();
                    helper.assertTrue(delivered >= 32_768 * 0.99, "The LV busway delivered " + Math.round(delivered) + " FE/t of 32,768");
                    helper.assertTrue(taps.stream().allMatch(t -> t.breaker().closed()), "A tap box opened");
                    helper.assertTrue(bus.stream().noneMatch(BusPieceBlockEntity::damaged), "The busway burnt");
                })
                .thenSucceed();
    }

    // Eight EHV sources into a feed-in, an EHV 8W gas-insulated line of four blocks, its far end cabled to eight EHV sinks:
    // 16,777,216 FE/t arrive, at 1,024 A a conductor, nothing burnt or overflowed.
    private static void ehvFull(GameTestHelper helper) {
        FactoryGameTests.floor(helper, 60);
        BusPieceBlockEntity feed = feedIn(helper, VoltageTier.EHV, new BlockPos(2, 3, 4), Direction.EAST);
        List<BuswayBlockEntity> bus = straight(helper, VoltageTier.EHV, 8, new BlockPos(3, 3, 4), 4);
        List<TowerBlockEntity> sources = new ArrayList<>(), sinks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            sources.add(LineupGameTests.source(helper, VoltageTier.EHV, 2 + 6 * i, 14));
            sinks.add(LineupGameTests.sink(helper, VoltageTier.EHV, 2 + 6 * i, -6));
        }
        long full = 16_777_216L;
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    List<BusPieceBlockEntity> all = new ArrayList<>(bus);
                    all.add(feed);
                    refresh(helper, all);
                    BusPieceBlockEntity end = bus.get(bus.size() - 1);
                    for (int i = 0; i < 8; i++) {
                        LineupGameTests.cable(helper, LineupGameTests.unitNodes(helper, sources.get(i)), nodes(helper, feed, 0), 1);
                        LineupGameTests.cable(helper, nodes(helper, end, 0), LineupGameTests.unitNodes(helper, sinks.get(i)), 1);
                    }
                })
                .thenExecuteAfter(40, () -> {
                    double delivered = sinks.stream().mapToDouble(TowerBlockEntity::lastFlow).sum();
                    helper.assertTrue(delivered >= full * 0.999, "The EHV line delivered " + Math.round(delivered) + " FE/t of " + full);
                    GridData.Dimension dimension = GridData.of(helper.getLevel());
                    var amps = net.zagdrath.arcforgetransmission.grid.world.GridManager.lastAmps(helper.getLevel());
                    long node = nodes(helper, bus.get(1), 0).get(0);
                    for (GridEdge edge : dimension.graph().edgesOf(node)) {
                        if (edge.kind() == GridEdge.Kind.BUS) {
                            double a = amps.getOrDefault(edge.id(), 0.0);
                            helper.assertTrue(Math.abs(a - 1024) < 2, "A conductor carries " + a + " A, not 1,024");
                        }
                    }
                    helper.assertTrue(bus.stream().noneMatch(BusPieceBlockEntity::damaged), "The line burnt");
                })
                .thenSucceed();
    }

    // An MV lineup (transformer to an LV bus), its feeder at 2W (32 A), a feed-in on the feeder's roof, LV 2W busway, a 1W
    // (16 A) tap box loaded with two LV sinks (32 A): the tap trips, the feeder holds.
    private static void tapBeforeFeeder(GameTestHelper helper) {
        FactoryGameTests.floor(helper, 40);
        ServerLevel level = helper.getLevel();
        List<SectionBlockEntity> lineup = LineupGameTests.lineup(helper, VoltageTier.MV, 0, 2, Section.INCOMING, Section.TRANSFORMER, Section.MAIN,
                Section.FEEDER);
        SectionBlockEntity feeder = lineup.get(3);
        TowerBlockEntity source = LineupGameTests.source(helper, VoltageTier.MV, 2, 12);
        BusPieceBlockEntity feed = feedIn(helper, VoltageTier.LV, new BlockPos(3, 3, 2), Direction.EAST);
        List<BuswayBlockEntity> bus = straight(helper, VoltageTier.LV, 2, new BlockPos(4, 3, 2), 3);
        BuswayTapOffBlockEntity tap = tap(helper, VoltageTier.LV, new BlockPos(5, 3, 1), Direction.SOUTH);
        CableTerminationBlockEntity end = FactoryGameTests.terminate(helper, VoltageTier.LV, tap.getBlockPos(), Direction.NORTH);
        TowerBlockEntity a = LineupGameTests.sink(helper, VoltageTier.LV, 10, -4), b = LineupGameTests.sink(helper, VoltageTier.LV, 14, -4);
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> LineupGameTests.cable(helper, LineupGameTests.unitNodes(helper, source), LineupGameTests.group(helper, lineup.get(0), 0), 1))
                .thenWaitUntil(() -> helper.assertTrue(lineup.stream().allMatch(SectionBlockEntity::planned), "Not a lineup: " + LineupGameTests.planOf(lineup)))
                .thenExecute(() -> {
                    List<BusPieceBlockEntity> all = new ArrayList<>(bus);
                    all.add(feed);
                    all.add(tap);
                    refresh(helper, all);
                    feeder.breakers().get(1).setFrame(1);
                    LineupGameTests.closeAll(helper, lineup, false);
                    tap.operate(level);
                    LineupGameTests.cable(helper, end.nodes(GridData.of(level)), LineupGameTests.unitNodes(helper, a), 1);
                    LineupGameTests.cable(helper, end.nodes(GridData.of(level)), LineupGameTests.unitNodes(helper, b), 1);
                })
                .thenWaitUntil(() -> helper.assertTrue(tap.breaker().state() == OpState.TRIPPED, "The tap didn't trip: " + tap.breaker().state()
                        + ", load " + tap.load()))
                .thenExecute(() -> helper.assertTrue(feeder.breakers().get(1).closed(), "The feeder tripped too"))
                .thenSucceed();
    }

    // LV 2W beside LV 4W: no arm either way; a 4W-to-2W reducer between them: each has its arm toward it. LV beside MV: no
    // arm.
    private static void sizesAndTiers(GameTestHelper helper) {
        FactoryGameTests.floor(helper, 20);
        ServerLevel level = helper.getLevel();
        BuswayBlockEntity small = run(helper, VoltageTier.LV, 2, new BlockPos(2, 2, 2));
        BuswayBlockEntity big = run(helper, VoltageTier.LV, 4, new BlockPos(3, 2, 2));
        BuswayBlockEntity mv = run(helper, VoltageTier.MV, 2, new BlockPos(2, 2, 3));
        helper.startSequence()
                .thenIdle(1)
                .thenExecute(() -> {
                    helper.assertTrue(!level.getBlockState(small.getBlockPos()).getValue(BuswayBlock.EAST)
                            && !level.getBlockState(big.getBlockPos()).getValue(BuswayBlock.WEST), "2W and 4W joined");
                    helper.assertTrue(!level.getBlockState(small.getBlockPos()).getValue(BuswayBlock.SOUTH), "LV and MV joined");
                    // Move the 4W one along and put the reducer between, its small end toward the 2W.
                    level.removeBlock(big.getBlockPos(), false);
                    run(helper, VoltageTier.LV, 4, new BlockPos(4, 2, 2));
                    BlockPos at = helper.absolutePos(new BlockPos(3, 2, 2));
                    BlockState reducer = FactoryContent.REDUCERS.get(VoltageTier.LV).get(1).get().defaultBlockState().setValue(BuswayReducerBlock.FACING,
                            Direction.WEST);
                    level.setBlock(at, reducer, Block.UPDATE_ALL);
                })
                .thenIdle(1)
                .thenExecute(() -> {
                    helper.assertTrue(level.getBlockState(small.getBlockPos()).getValue(BuswayBlock.EAST), "The 2W didn't join the reducer's small end");
                    helper.assertTrue(level.getBlockState(helper.absolutePos(new BlockPos(4, 2, 2))).getValue(BuswayBlock.WEST),
                            "The 4W didn't join the reducer's big end");
                })
                .thenSucceed();
    }

    // An LV source, three blocks of 2W busway, an LV sink: power flows; the middle block damaged: it stops, and the block
    // shows it.
    private static void damaged(GameTestHelper helper) {
        FactoryGameTests.floor(helper, 30);
        ServerLevel level = helper.getLevel();
        List<BuswayBlockEntity> bus = straight(helper, VoltageTier.LV, 2, new BlockPos(3, 2, 4), 3);
        TowerBlockEntity source = LineupGameTests.source(helper, VoltageTier.LV, 2, 12), sink = LineupGameTests.sink(helper, VoltageTier.LV, 10, -4);
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    refresh(helper, bus);
                    LineupGameTests.cable(helper, LineupGameTests.unitNodes(helper, source), nodes(helper, bus.get(0), 0), 1);
                    LineupGameTests.cable(helper, nodes(helper, bus.get(2), 0), LineupGameTests.unitNodes(helper, sink), 1);
                })
                .thenWaitUntil(() -> helper.assertTrue(sink.lastFlow() > 0, "Nothing came through the busway"))
                .thenExecute(() -> bus.get(1).damage(level))
                .thenExecuteAfter(10, () -> {
                    helper.assertTrue(level.getBlockState(bus.get(1).getBlockPos()).getValue(BuswayBlock.DAMAGED), "It isn't shown damaged");
                    helper.assertTrue(sink.lastFlow() == 0, "Power still flows through the damaged busway: " + sink.lastFlow());
                })
                .thenSucceed();
    }
}
