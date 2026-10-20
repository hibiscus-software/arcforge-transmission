/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.gametest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.factory.CableTerminationBlockEntity;
import net.zagdrath.arcforgetransmission.factory.LineupRatings;
import net.zagdrath.arcforgetransmission.grid.FlowSolver;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.block.PinInsulatorMode;
import net.zagdrath.arcforgetransmission.grid.block.PortHud;
import net.zagdrath.arcforgetransmission.grid.world.GridConfig;
import net.zagdrath.arcforgetransmission.grid.world.GridData;
import net.zagdrath.arcforgetransmission.grid.world.Generators;
import net.zagdrath.arcforgetransmission.grid.world.Loads;
import net.zagdrath.arcforgetransmission.tower.TowerBlockEntity;

// Machines as tiered loads. The Creative Energy Cell stands in for an EHV machine rated past one EHV circuit (mapped here,
// over the data map: the tests ship no datapack; nothing else mounts a port on one):
//  - it draws a whole EHV circuit through an EHV termination, its HUD and comparator showing its load;
//  - an HV termination on it says WRONG_TIER_LOAD and delivers nothing;
//  - two EHV feeds add up past one circuit, each still held to its own, together to no more than its rating;
//  - an unmapped foreign block still takes FE at LV only (MV is an overvoltage).
@EventBusSubscriber(modid = ArcforgeTransmission.MODID)
public final class LoadGameTests {
    private static final Map<String, Consumer<GameTestHelper>> TESTS = new LinkedHashMap<>();
    // The stand-in machine's rating: more than one EHV circuit, less than two.
    static final int RATING = 3_000_000;

    static {
        TESTS.put("ehv_machine_draws_through_an_ehv_termination", LoadGameTests::ehvDraws);
        TESTS.put("hv_termination_on_an_ehv_machine_is_wrong_tier", LoadGameTests::wrongTier);
        TESTS.put("two_ehv_feeds_add_up", LoadGameTests::twoFeeds);
        TESTS.put("foreign_block_takes_lv_only", LoadGameTests::foreignLvOnly);
    }

    private LoadGameTests() {}

    @SubscribeEvent
    static void registerFunctions(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> TESTS.forEach((name, test) -> helper.register(FactoryGameTests.id(name), test)));
    }

    @SubscribeEvent
    static void registerTests(RegisterGameTestsEvent event) {
        Loads.override(machineBlock(), new Generators.Rating(VoltageTier.EHV, OptionalInt.of(RATING)));
        TESTS.keySet().forEach(name -> {
            Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(FactoryGameTests.id("load_" + name));
            TestData<Holder<TestEnvironmentDefinition<?>>> data = new TestData<>(environment, Level.OVERWORLD, Identifier.withDefaultNamespace("empty"), 400,
                    0, true, Rotation.NONE, false, 1, 1, true, 64);
            event.registerTest(FactoryGameTests.id(name), new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, FactoryGameTests.id(name)),
                    data));
        });
    }

    static Block machineBlock() {
        return BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("arcforge", "creative_energy_cell"));
    }

    // The stand-in machine at a test position.
    static BlockPos machine(GameTestHelper helper, BlockPos at) {
        return FactoryGameTests.arcforge(helper, "creative_energy_cell", at);
    }

    // A termination of a tier on the machine's face, switched to Output, cabled from a source of its tier.
    static CableTerminationBlockEntity feed(GameTestHelper helper, TowerBlockEntity source, CableTerminationBlockEntity termination) {
        termination.setForPort(PinInsulatorMode.OUTPUT, 8);
        LineupGameTests.cable(helper, LineupGameTests.unitNodes(helper, source), termination.nodes(GridData.of(helper.getLevel())), 1);
        return termination;
    }

    static long circuit(VoltageTier tier) {
        return LineupRatings.circuit(GridConfig.SETTINGS, tier);
    }

    // --- The tests ----------------------------------------------------------------------------------------------------

    private static void ehvDraws(GameTestHelper helper) {
        FactoryGameTests.floor(helper, 30);
        BlockPos cell = machine(helper, new BlockPos(4, 1, -2));
        CableTerminationBlockEntity termination = FactoryGameTests.terminate(helper, VoltageTier.EHV, cell, Direction.UP);
        TowerBlockEntity source = LineupGameTests.source(helper, VoltageTier.EHV, 4, 12);
        long full = circuit(VoltageTier.EHV);
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> feed(helper, source, termination))
                .thenExecuteAfter(40, () -> {
                    helper.assertTrue(termination.status() == FlowSolver.PortStatus.OK, "Status " + termination.status());
                    helper.assertTrue(termination.lastFlow() >= full * 0.97, "It drew " + Math.round(termination.lastFlow()) + " FE/t of an EHV circuit's " + full);
                    PortHud.Load load = termination.hudLoad();
                    helper.assertTrue(load != null && !load.wrong() && load.tier() == VoltageTier.EHV && load.rating() == RATING
                            && load.draw() >= full * 0.97, "Its HUD shows " + load);
                    int signal = helper.getLevel().getBlockState(termination.getBlockPos()).getAnalogOutputSignal(helper.getLevel(), termination.getBlockPos(),
                            Direction.UP);
                    helper.assertTrue(signal == Math.round(15.0 * load.draw() / RATING), "Comparator " + signal);
                })
                .thenSucceed();
    }

    private static void wrongTier(GameTestHelper helper) {
        FactoryGameTests.floor(helper, 30);
        BlockPos cell = machine(helper, new BlockPos(4, 1, -2));
        CableTerminationBlockEntity termination = FactoryGameTests.terminate(helper, VoltageTier.HV, cell, Direction.UP);
        TowerBlockEntity source = LineupGameTests.source(helper, VoltageTier.HV, 4, 12);
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> feed(helper, source, termination))
                .thenExecuteAfter(40, () -> {
                    helper.assertTrue(termination.status() == FlowSolver.PortStatus.WRONG_TIER_LOAD, "Status " + termination.status());
                    helper.assertTrue(termination.lastFlow() < 1, "It delivered " + termination.lastFlow() + " FE/t");
                    PortHud.Load load = termination.hudLoad();
                    helper.assertTrue(load != null && load.wrong() && load.tier() == VoltageTier.EHV, "Its HUD shows " + load);
                    helper.assertTrue(helper.getLevel().getBlockState(cell).is(machineBlock()), "The machine went");
                })
                .thenSucceed();
    }

    private static void twoFeeds(GameTestHelper helper) {
        FactoryGameTests.floor(helper, 40);
        BlockPos cell = machine(helper, new BlockPos(10, 1, 2));
        CableTerminationBlockEntity up = FactoryGameTests.terminate(helper, VoltageTier.EHV, cell, Direction.UP);
        // (Its other face: the one it faces only gives.)
        CableTerminationBlockEntity side = FactoryGameTests.terminate(helper, VoltageTier.EHV, cell, Direction.SOUTH);
        TowerBlockEntity a = LineupGameTests.source(helper, VoltageTier.EHV, 4, 12), b = LineupGameTests.source(helper, VoltageTier.EHV, 18, 12);
        long full = circuit(VoltageTier.EHV);
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    feed(helper, a, up);
                    feed(helper, b, side);
                })
                .thenExecuteAfter(40, () -> {
                    double one = up.lastFlow(), two = side.lastFlow(), both = one + two;
                    helper.assertTrue(up.status() == FlowSolver.PortStatus.OK && side.status() == FlowSolver.PortStatus.OK,
                            "Statuses " + up.status() + " / " + side.status());
                    helper.assertTrue(one <= full + 1 && two <= full + 1, "A feed passed its circuit: " + Math.round(one) + " / " + Math.round(two));
                    helper.assertTrue(both > full * 1.2 && both <= RATING + 2, "The two feeds gave " + Math.round(both) + " FE/t (one circuit " + full
                            + ", its rating " + RATING + ")");
                    PortHud.Load load = up.hudLoad();
                    helper.assertTrue(load != null && Math.abs(load.draw() - both) <= 2, "Its HUD's total " + (load == null ? null : load.draw()) + " isn't "
                            + Math.round(both));
                })
                .thenSucceed();
    }

    private static void foreignLvOnly(GameTestHelper helper) {
        FactoryGameTests.floor(helper, 30);
        ServerLevel level = helper.getLevel();
        BlockPos lvCell = FactoryGameTests.arcforge(helper, "tempered_energy_cell", new BlockPos(4, 1, -2));
        BlockPos mvCell = FactoryGameTests.arcforge(helper, "wrought_energy_cell", new BlockPos(16, 1, -2));
        CableTerminationBlockEntity lv = FactoryGameTests.terminate(helper, VoltageTier.LV, lvCell, Direction.UP);
        CableTerminationBlockEntity mv = FactoryGameTests.terminate(helper, VoltageTier.MV, mvCell, Direction.UP);
        TowerBlockEntity lvSource = LineupGameTests.source(helper, VoltageTier.LV, 4, 12), mvSource = LineupGameTests.source(helper, VoltageTier.MV, 16, 12);
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    feed(helper, lvSource, lv);
                    feed(helper, mvSource, mv);
                })
                .thenWaitUntil(() -> helper.assertTrue(!level.getBlockState(mvCell).getBlock().getDescriptionId().contains("energy_cell"),
                        "The unmapped cell under the MV termination survived"))
                .thenWaitUntil(() -> helper.assertTrue(FactoryGameTests.stored(helper, lvCell) > 0, "The LV termination didn't fill its cell: " + lv.status()))
                .thenExecute(() -> {
                    helper.assertTrue(lv.hudLoad() == null, "An unmapped block shows as a load");
                    helper.assertTrue(!level.getBlockState(lvCell).is(Blocks.AIR), "The LV cell went");
                })
                .thenSucceed();
    }
}
