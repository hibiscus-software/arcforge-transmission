/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.gametest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import net.zagdrath.arcforge.blockentity.multiblock.SteamTurbineArrayBlockEntity;
import net.zagdrath.arcforge.machine.config.SideMode;
import net.zagdrath.arcforge.multiblock.MultiblockPorts;
import net.zagdrath.arcforge.registry.ModBlocks;
import net.zagdrath.arcforge.steam.SteamGrade;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.grid.FaultEvent;
import net.zagdrath.arcforgetransmission.grid.FlowSolver;
import net.zagdrath.arcforgetransmission.grid.Phase;
import net.zagdrath.arcforgetransmission.grid.GridEdge;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.block.GridContent;
import net.zagdrath.arcforgetransmission.grid.block.PoleTransformerBlock;
import net.zagdrath.arcforgetransmission.grid.world.ConfigReset;
import net.zagdrath.arcforgetransmission.grid.world.GridConfig;
import net.zagdrath.arcforgetransmission.grid.world.GridData;
import net.zagdrath.arcforgetransmission.grid.world.GridManager;
import net.zagdrath.arcforgetransmission.tower.TowerBlockEntity;
import net.zagdrath.arcforgetransmission.tower.TowerItem;
import net.zagdrath.arcforgetransmission.tower.TowerPlacement;
import net.zagdrath.arcforgetransmission.tower.TowerTier;

// The grid in a running world (./gradlew runGameTestServer; this source set is never shipped), built from the real
// blocks and wired by the grid's own calls:
//  - an MV feeder serves a house through the MV Distribution Transformer and a Service Meter;
//  - a yard (bus, disconnect, breaker, substation transformer, recloser, ground switch) carries power across tiers, and
//    its interlocks hold: the disconnect won't open under its closed breaker nor close onto a grounded line, the ground
//    switch won't close on a live line, the recloser won't close onto a grounded one, and a breaker in an unloaded chunk
//    counts as closed;
//  - voltage sags along a feeder, and a regulator (or a capacitor bank) brings it back;
//  - a fault past two reclosers trips the nearer only, with and without a Relay Panel;
//  - a recloser set fuse-saving clears a fault past a fuse before it blows; set fuse-blowing, the fuse clears it;
//  - an HV line turns a corner on a dead-end tower at 45 degrees, transposes and goes underground between two risers;
//  - a Steam Turbine Array feeds an EHV unit transformer, and the EHV line (turning, transposed, underground) delivers
//    through a substation to an HV unit transformer on Output;
//  - a transfer switch moves its load to the alternate when the preferred dies (judged from the preferred's own side),
//    and not when held;
//  - a device's state comes back whole from its saved data, the grid's save (node keys, a regulator's boost) reloads as
//    it was, and a block whose chunk unloads and loads again takes up where it left off;
//  - a grid saved before the tiers' voltages doubled loads unchanged (tiers are saved by name) and runs at the new ratings.
// Each test stands on a stone floor in an empty structure, with room round it.
@EventBusSubscriber(modid = ArcforgeTransmission.MODID)
public final class TransmissionGameTests {
    private static final String MODID = ArcforgeTransmission.MODID;
    private static final Map<String, Consumer<GameTestHelper>> TESTS = new LinkedHashMap<>();
    // The voltage test makes MV wire lossy for a while: it runs in its own batch (its own environment), after the rest.
    private static final String LOSSY = "regulator_and_capacitor_bank_hold_a_sagging_feeder";

    static {
        TESTS.put("mv_feeder_serves_a_house", TransmissionGameTests::mvFeederServesAHouse);
        TESTS.put("yard_and_interlocks", TransmissionGameTests::yardAndInterlocks);
        TESTS.put("nearest_recloser_trips_first", TransmissionGameTests::nearestRecloserTripsFirst);
        TESTS.put("relay_panel_grades_two_breakers", TransmissionGameTests::relayPanelGradesTwoBreakers);
        TESTS.put("fuse_saving_and_fuse_blowing", TransmissionGameTests::fuseSavingAndFuseBlowing);
        TESTS.put("hv_line_turns_transposes_and_goes_underground", TransmissionGameTests::hvLineTurnsTransposesAndGoesUnderground);
        TESTS.put("steam_turbine_feeds_ehv_line_through_a_substation", TransmissionGameTests::steamTurbineFeedsEhvLine);
        TESTS.put("transfer_switch_judges_the_preferred_by_its_own_side", TransmissionGameTests::transferSwitch);
        TESTS.put("device_state_survives_saving", TransmissionGameTests::deviceStateSurvivesSaving);
        TESTS.put("grid_save_reloads_and_chunks_unload", TransmissionGameTests::gridSaveReloadsAndChunksUnload);
        TESTS.put("old_save_loads_at_the_new_ratings", TransmissionGameTests::oldSaveLoadsAtTheNewRatings);
    }

    private TransmissionGameTests() {}

    @SubscribeEvent
    static void registerFunctions(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            TESTS.forEach((name, test) -> helper.register(id(name), test));
            helper.register(id(LOSSY), TransmissionGameTests::voltageIsHeld);
        });
    }

    @SubscribeEvent
    static void registerTests(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(id("default"));
        TestData<Holder<TestEnvironmentDefinition<?>>> data = new TestData<>(environment, Level.OVERWORLD, Identifier.withDefaultNamespace("empty"), 1600, 0,
                true, Rotation.NONE, false, 1, 1, true, 48);
        TESTS.keySet().forEach(name -> event.registerTest(id(name), new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id(name)), data)));
        Holder<TestEnvironmentDefinition<?>> lossy = event.registerEnvironment(id("lossy"));
        event.registerTest(id(LOSSY), new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id(LOSSY)), new TestData<>(lossy,
                Level.OVERWORLD, Identifier.withDefaultNamespace("empty"), 1600, 0, true, Rotation.NONE, false, 1, 1, true, 48)));
    }

    // Runs `restore` however the test ends, passed or failed (a test that changes the config puts it back).
    private static void finallyRestore(GameTestHelper helper, Runnable restore) {
        helper.testInfo.addListener(new GameTestListener() {
            @Override
            public void testStructureLoaded(GameTestInfo info) {}

            @Override
            public void testPassed(GameTestInfo info, GameTestRunner runner) {
                restore.run();
            }

            @Override
            public void testFailed(GameTestInfo info, GameTestRunner runner) {
                restore.run();
            }

            @Override
            public void testAddedForRerun(GameTestInfo original, GameTestInfo rerun, GameTestRunner runner) {}
        });
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MODID, path);
    }

    // --- Building ---------------------------------------------------------------------------------------------------

    // A stone floor under the test, x and z from -4 to size.
    private static void floor(GameTestHelper helper, int size) {
        for (int x = -4; x <= size; x++) {
            for (int z = -8; z <= 12; z++) {
                helper.getLevel().setBlock(helper.absolutePos(new BlockPos(x, 0, z)), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    // A structure standing on (x, 1, z), oriented eighths of a turn from north; its main block entity.
    private static TowerBlockEntity build(GameTestHelper helper, TowerTier tier, int x, int z, int eighths) {
        ServerLevel level = helper.getLevel();
        BlockPos centre = helper.absolutePos(new BlockPos(x, 1, z));
        Block block = BuiltInRegistries.BLOCK.getValue(id(tier.id()));
        TowerItem.build(level, block, tier, centre, eighths);
        for (BlockPos pos : net.zagdrath.arcforgetransmission.tower.TowerPlacement.positions(tier, eighths, centre)) {
            if (level.getBlockEntity(pos) instanceof TowerBlockEntity tower) {
                return tower;
            }
        }
        throw new IllegalStateException("No " + tier.id() + " at " + centre);
    }

    // An Arcforge block (an energy cell) at (x, 1, z).
    private static BlockPos cell(GameTestHelper helper, String id, int x, int z) {
        BlockPos pos = helper.absolutePos(new BlockPos(x, 1, z));
        helper.getLevel().setBlock(pos, BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("arcforge", id)).defaultBlockState(), Block.UPDATE_ALL);
        return pos;
    }

    // A generator's supply: an energy cell at (x, 1, z), filled.
    // (An energy cell gives from its front: turned to face south, toward the unit transformer behind it.)
    private static BlockPos source(GameTestHelper helper, int x, int z) {
        BlockPos pos = cell(helper, "tempered_energy_cell", x, z);
        BlockState state = helper.getLevel().getBlockState(pos);
        for (var property : state.getProperties()) {
            if (property.getName().equals("facing") && property instanceof net.minecraft.world.level.block.state.properties.EnumProperty<?> facing
                    && facing.getValueClass() == net.minecraft.core.Direction.class) {
                @SuppressWarnings("unchecked")
                var directional = (net.minecraft.world.level.block.state.properties.EnumProperty<net.minecraft.core.Direction>) facing;
                helper.getLevel().setBlock(pos, state.setValue(directional, net.minecraft.core.Direction.SOUTH), Block.UPDATE_ALL);
            }
        }
        fill(helper, pos);
        return pos;
    }

    // Fills an energy cell.
    private static void fill(GameTestHelper helper, BlockPos pos) {
        EnergyHandler handler = helper.getLevel().getCapability(Capabilities.Energy.BLOCK, pos, null);
        if (handler != null) {
            for (int i = 0; i < 64; i++) {
                try (var tx = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) {
                    int got = handler.insert(Integer.MAX_VALUE, tx);
                    tx.commit();
                    if (got <= 0) {
                        break;
                    }
                }
            }
        }
    }

    private static long stored(GameTestHelper helper, BlockPos cell) {
        EnergyHandler handler = helper.getLevel().getCapability(Capabilities.Energy.BLOCK, cell, null);
        return handler == null ? -1 : handler.getAmountAsLong();
    }

    // Wires insulator a[i] of one block to b[i] of another for each i, at a tier and kind; fails the test if refused.
    private static void wire(GameTestHelper helper, BlockEntity from, int[] a, BlockEntity to, int[] b, VoltageTier tier, GridEdge.Kind kind) {
        for (int i = 0; i < a.length; i++) {
            var refusal = GridManager.connect(helper.getLevel(), new GridData.Anchor(from.getBlockPos(), a[i]), new GridData.Anchor(to.getBlockPos(), b[i]),
                    tier, kind);
            if (refusal != null) {
                throw new IllegalStateException("Wire " + from.getBlockState().getBlock() + "[" + a[i] + "] to " + to.getBlockState().getBlock() + "["
                        + b[i] + "] refused: " + refusal);
            }
        }
    }

    private static final int[] ABC = { 0, 1, 2 }, SECOND = { 3, 4, 5 };

    // What a failure needs to say: the generator's status and flow, what its cell offers, the grid's live networks and
    // the ports it saw.
    private static String why(GameTestHelper helper, TowerBlockEntity generator) {
        ServerLevel level = helper.getLevel();
        BlockPos served = generator.servedPos();
        EnergyHandler handler = level.getCapability(Capabilities.Energy.BLOCK, served, generator.facing().getOpposite());
        long offer = -1;
        if (handler != null) {
            try (var tx = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) {
                offer = handler.extract(100000, tx);
            }
        }
        StringBuilder faces = new StringBuilder();
        for (net.minecraft.core.Direction side : net.minecraft.core.Direction.values()) {
            EnergyHandler h = level.getCapability(Capabilities.Energy.BLOCK, served, side);
            long got = -2;
            if (h != null) {
                try (var tx = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) {
                    got = h.extract(100000, tx);
                }
            }
            faces.append(side).append('=').append(got).append(h == null ? "" : "/" + h.getAmountAsLong()).append(' ');
        }
        GridManager.TickView view = GridManager.view(level);
        return " [faces " + faces + "; generator " + generator.status() + " " + generator.lastFlow() + " FE/t, mode " + generator.mode() + ", cell " + level.getBlockState(served)
                + " offers " + offer + ", live networks " + view.result().liveNetworks().size() + ", statuses " + view.result().status() + ", loaded "
                + GridManager.loadedIn(level).size() + "]";
    }

    // A generator: a filled energy cell at (x, 1, z) and, north of it facing it (south: the cell gives from its north
    // face), a unit transformer set to Input.
    private static TowerBlockEntity generator(GameTestHelper helper, TowerTier tier, int x, int z) {
        source(helper, x, z);
        TowerBlockEntity unit = build(helper, tier, x, z - 1, 4);
        unit.configure(false);
        return unit;
    }

    // A unit transformer at (x, z) facing north, serving the cell north of it, in the given mode (Configure steps it
    // from Off: 1 Input, 2 Output, 3 Storage).
    private static TowerBlockEntity unit(GameTestHelper helper, TowerTier tier, int x, int z, int steps) {
        TowerBlockEntity unit = build(helper, tier, x, z, 0);
        for (int i = 0; i < steps; i++) {
            unit.configure(false);
        }
        return unit;
    }

    // The average voltage (per unit) at a block's first three insulators, last tick.
    private static double volts(GameTestHelper helper, BlockEntity block) {
        return volts(helper, block, 0);
    }

    // The average voltage (per unit) at three of a block's insulators from `first`, last tick.
    private static double volts(GameTestHelper helper, BlockEntity block, int first) {
        GridData.Dimension dimension = GridData.of(helper.getLevel());
        GridManager.TickView view = GridManager.view(helper.getLevel());
        double sum = 0;
        for (int i = first; i < first + 3; i++) {
            Long node = dimension.nodeAt(new GridData.Anchor(block.getBlockPos(), i));
            sum += node == null ? 0 : view.volts(dimension.graph(), node);
        }
        return sum / 3;
    }

    // --- The tests ----------------------------------------------------------------------------------------------------

    // An MV feeder (a generator's MV unit transformer) straight to an MV Distribution Transformer on a pole, its split-phase
    // secondary to a Service Meter feeding a house's energy cell.
    private static void mvFeederServesAHouse(GameTestHelper helper) {
        floor(helper, 24);
        TowerBlockEntity generator = generator(helper, TowerTier.MV_UNIT, 2, 1);
        ServerLevel level = helper.getLevel();
        BlockPos pole = helper.absolutePos(new BlockPos(12, 1, 6));
        for (int y = 0; y < 4; y++) {
            level.setBlock(pole.above(y), Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);
        }
        BlockPos canPos = pole.above(2).north();
        level.setBlock(canPos, GridContent.MV_DISTRIBUTION_TRANSFORMER.get().defaultBlockState().setValue(PoleTransformerBlock.FACING,
                net.minecraft.core.Direction.NORTH), Block.UPDATE_ALL);
        BlockEntity can = level.getBlockEntity(canPos);
        BlockPos house = cell(helper, "tempered_energy_cell", 18, 2);
        TowerBlockEntity meter = build(helper, TowerTier.SERVICE_METER, 18, 3, 0);
        helper.runAfterDelay(2, () -> {
            wire(helper, generator, ABC, can, ABC, VoltageTier.MV, GridEdge.Kind.BARE);
            wire(helper, can, SECOND, meter, ABC, VoltageTier.LV, GridEdge.Kind.INSULATED);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(stored(helper, house) > 0, "The house got no FE through the MV distribution transformer" + why(helper, generator));
        });
    }

    // Generator (HV) -> HV bus -> HV disconnect -> HV breaker -> HV substation transformer -> MV recloser -> MV load, a
    // ground switch on the recloser's load side.
    private static void yardAndInterlocks(GameTestHelper helper) {
        floor(helper, 60);
        ServerLevel level = helper.getLevel();
        TowerBlockEntity generator = generator(helper, TowerTier.HV_UNIT, 0, 0);
        TowerBlockEntity bus = build(helper, TowerTier.HV_BUS, 9, 4, 0);
        TowerBlockEntity disconnect = build(helper, TowerTier.HV_DISCONNECT, 16, 4, 6);
        TowerBlockEntity breaker = build(helper, TowerTier.HV_BREAKER, 23, 4, 6);
        TowerBlockEntity transformer = build(helper, TowerTier.HV_SUBSTATION, 32, 4, 6);
        TowerBlockEntity recloser = build(helper, TowerTier.MV_RECLOSER, 41, 4, 6);
        TowerBlockEntity ground = build(helper, TowerTier.MV_GROUND_SWITCH, 45, 9, 6);
        TowerBlockEntity farGround = build(helper, TowerTier.HV_GROUND_SWITCH, 20, 12, 6);
        BlockPos load = cell(helper, "tempered_energy_cell", 50, -1);
        TowerBlockEntity sink = unit(helper, TowerTier.MV_UNIT, 50, 0, 2);
        disconnect.toggleControl(breaker.getBlockPos());
        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    wire(helper, generator, ABC, bus, ABC, VoltageTier.HV, GridEdge.Kind.BARE);
                    wire(helper, bus, ABC, disconnect, ABC, VoltageTier.HV, GridEdge.Kind.BARE);
                    wire(helper, disconnect, SECOND, breaker, ABC, VoltageTier.HV, GridEdge.Kind.BARE);
                    wire(helper, breaker, SECOND, transformer, ABC, VoltageTier.HV, GridEdge.Kind.BARE);
                    wire(helper, transformer, SECOND, recloser, ABC, VoltageTier.MV, GridEdge.Kind.BARE);
                    wire(helper, recloser, SECOND, sink, ABC, VoltageTier.MV, GridEdge.Kind.BARE);
                    wire(helper, recloser, SECOND, ground, ABC, VoltageTier.MV, GridEdge.Kind.BARE);
                    wire(helper, disconnect, SECOND, farGround, ABC, VoltageTier.HV, GridEdge.Kind.BARE);
                })
                .thenWaitUntil(() -> helper.assertTrue(stored(helper, load) > 0, "No power crossed the yard to the MV load" + why(helper, generator)))
                .thenExecute(() -> {
                    disconnect.operate(level);
                    helper.assertTrue(disconnect.closed(), "The disconnect opened under its closed breaker");
                    ground.operate(level);
                    helper.assertFalse(ground.closed(), "The ground switch closed on a live line");
                    recloser.operate(level);
                    helper.assertFalse(recloser.closed(), "The recloser didn't open by hand");
                })
                .thenExecuteAfter(4, () -> {
                    ground.operate(level);
                    helper.assertTrue(ground.closed(), "The ground switch didn't close on the dead line");
                })
                .thenExecuteAfter(4, () -> {
                    recloser.operate(level);
                    helper.assertFalse(recloser.closed(), "The recloser closed onto a grounded line");
                    breaker.operate(level);
                    helper.assertFalse(breaker.closed(), "The breaker didn't open");
                    disconnect.operate(level);
                    helper.assertFalse(disconnect.closed(), "The disconnect didn't open with its breaker open");
                    // Ground the line between the disconnect and the breaker's load side: the disconnect won't close onto it.
                    farGround.operate(level);
                    helper.assertTrue(farGround.closed(), "The HV ground switch didn't close on the dead line");
                })
                .thenExecuteAfter(4, () -> {
                    disconnect.operate(level);
                    helper.assertFalse(disconnect.closed(), "The disconnect closed onto a grounded line");
                    farGround.operate(level);
                    ground.operate(level);
                })
                .thenExecuteAfter(4, () -> {
                    // A breaker it's interlocked with, in a chunk that isn't loaded: counted closed.
                    BlockPos far = breaker.getBlockPos().offset(100_000, 0, 0);
                    disconnect.toggleControl(far);
                    disconnect.operate(level);
                    helper.assertFalse(disconnect.closed(), "The disconnect closed with a breaker it can't see");
                    disconnect.toggleControl(far);
                    disconnect.operate(level);
                    helper.assertTrue(disconnect.closed(), "The disconnect didn't close once it could see every breaker open");
                })
                .thenSucceed();
    }

    // Two generators' MV feeders, made lossy for the test (high resistance): one through an MV regulator, one with a
    // Capacitor Bank at its load end. Held, the voltage at the regulator's load side sags; let run, it steps it back into
    // its band (what it holds is its own load side: past it the line drops again). The bank, switched in, raises its load
    // end's voltage.
    private static void voltageIsHeld(GameTestHelper helper) {
        floor(helper, 40);
        double resistance = GridConfig.SETTINGS.resistance(VoltageTier.MV);
        int delay = GridConfig.TAP_DELAY.getAsInt();
        TowerBlockEntity generator = generator(helper, TowerTier.MV_UNIT, 0, 0);
        TowerBlockEntity regulator = build(helper, TowerTier.MV_REGULATOR, 16, 4, 6);
        cell(helper, "tempered_energy_cell", 34, -1);
        TowerBlockEntity sink = unit(helper, TowerTier.MV_UNIT, 34, 0, 2);
        TowerBlockEntity generator2 = generator(helper, TowerTier.MV_UNIT, 0, 8);
        cell(helper, "tempered_energy_cell", 20, 7);
        TowerBlockEntity sink2 = unit(helper, TowerTier.MV_UNIT, 20, 8, 2);
        TowerBlockEntity bank = build(helper, TowerTier.MV_CAPACITOR, 26, 11, 0);
        regulator.configure(false);
        double[] sagged = new double[2];
        // The generators' cells kept full: the feeders run the whole test.
        helper.onEachTick(() -> {
            fill(helper, helper.absolutePos(new BlockPos(0, 1, 0)));
            fill(helper, helper.absolutePos(new BlockPos(0, 1, 8)));
        });
        // Lossy MV wire and a quick tap changer for this test only: put back however it ends.
        finallyRestore(helper, () -> {
            setResistance(VoltageTier.MV, resistance);
            GridConfig.TAP_DELAY.set(delay);
        });
        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    // The cells give 2048 FE/t whatever the tier: the drop per unit is P R / V^2, so 4x the resistance it took at 512 V.
                    setResistance(VoltageTier.MV, 2.4);
                    GridConfig.TAP_DELAY.set(2);
                    wire(helper, generator, ABC, regulator, ABC, VoltageTier.MV, GridEdge.Kind.BARE);
                    wire(helper, regulator, SECOND, sink, ABC, VoltageTier.MV, GridEdge.Kind.BARE);
                    wire(helper, generator2, ABC, sink2, ABC, VoltageTier.MV, GridEdge.Kind.BARE);
                    wire(helper, sink2, ABC, bank, ABC, VoltageTier.MV, GridEdge.Kind.BARE);
                })
                .thenExecuteAfter(60, () -> {
                    sagged[0] = volts(helper, regulator, 3);
                    sagged[1] = volts(helper, sink2);
                    helper.assertTrue(sagged[0] > 0 && sagged[0] < 0.97, "The regulated feeder didn't sag: " + sagged[0] + why(helper, generator));
                    helper.assertTrue(sagged[1] > 0 && sagged[1] < 0.99, "The bank's feeder didn't sag: " + sagged[1]);
                    regulator.configure(false);
                    bank.operate(helper.getLevel());
                    helper.assertTrue(bank.closed(), "The bank didn't switch in");
                })
                .thenWaitUntil(() -> {
                    double now = volts(helper, regulator, 3);
                    helper.assertTrue(now > sagged[0] + 0.02 && Math.abs(now - 1.0) <= GridConfig.TAP_BAND.getAsDouble() / 2 + 0.005,
                            "The regulator hasn't held it: " + now + " from " + sagged[0] + "; regulator " + regulator.hudMode() + ", sink " + sink.status()
                                    + " " + sink.lastFlow() + ", wires " + GridData.of(helper.getLevel()).graph().edges().size() + why(helper, generator));
                    helper.assertTrue(volts(helper, sink2) > sagged[1] + 0.01, "The bank didn't raise the voltage: " + volts(helper, sink2) + " from "
                            + sagged[1]);
                })
                .thenSucceed();
    }

    private static void setResistance(VoltageTier tier, double ohms) {
        try {
            var field = GridConfig.class.getDeclaredField("RESISTANCE");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var map = (Map<VoltageTier, net.neoforged.neoforge.common.ModConfigSpec.DoubleValue>) field.get(null);
            map.get(tier).set(ohms);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // Generator -> recloser A -> recloser B -> load; a fault on the load side of B: B trips, A holds.
    private static void nearestRecloserTripsFirst(GameTestHelper helper) {
        floor(helper, 40);
        ServerLevel level = helper.getLevel();
        TowerBlockEntity generator = generator(helper, TowerTier.MV_UNIT, 0, 0);
        TowerBlockEntity a = build(helper, TowerTier.MV_RECLOSER, 10, 4, 6);
        TowerBlockEntity b = build(helper, TowerTier.MV_RECLOSER, 20, 4, 6);
        cell(helper, "tempered_energy_cell", 30, -1);
        TowerBlockEntity sink = unit(helper, TowerTier.MV_UNIT, 30, 0, 2);
        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    wire(helper, generator, ABC, a, ABC, VoltageTier.MV, GridEdge.Kind.BARE);
                    wire(helper, a, SECOND, b, ABC, VoltageTier.MV, GridEdge.Kind.BARE);
                    wire(helper, b, SECOND, sink, ABC, VoltageTier.MV, GridEdge.Kind.BARE);
                })
                .thenExecuteAfter(20, () -> {
                    Long node = GridData.of(level).nodeAt(new GridData.Anchor(sink.getBlockPos(), 0));
                    GridManager.fault(level, node, -1, FaultEvent.Cause.GROUND_FAULT, true);
                })
                .thenWaitUntil(() -> helper.assertFalse(b.closed(), "The recloser nearest the fault hasn't tripped"))
                .thenExecute(() -> helper.assertTrue(a.closed(), "The recloser further from the fault tripped too"))
                .thenSucceed();
    }

    // Generator -> HV dead-end tower at 45 degrees -> HV transposition tower -> HV riser -> underground cable -> HV riser
    // -> load.
    private static void hvLineTurnsTransposesAndGoesUnderground(GameTestHelper helper) {
        floor(helper, 80);
        TowerBlockEntity generator = generator(helper, TowerTier.HV_UNIT, 0, 0);
        TowerBlockEntity corner = build(helper, TowerTier.HV_DEAD_END_TOWER, 14, 4, 7);
        TowerBlockEntity transposition = build(helper, TowerTier.HV_TRANSPOSITION, 36, 4, 6);
        TowerBlockEntity riserA = build(helper, TowerTier.HV_RISER, 52, 4, 6);
        TowerBlockEntity riserB = build(helper, TowerTier.HV_RISER, 66, 4, 6);
        BlockPos load = cell(helper, "tempered_energy_cell", 76, -1);
        TowerBlockEntity sink = unit(helper, TowerTier.HV_UNIT, 76, 0, 2);
        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    wire(helper, generator, ABC, corner, ABC, VoltageTier.HV, GridEdge.Kind.BARE);
                    wire(helper, corner, SECOND, transposition, ABC, VoltageTier.HV, GridEdge.Kind.BARE);
                    wire(helper, transposition, SECOND, riserA, ABC, VoltageTier.HV, GridEdge.Kind.BARE);
                    wire(helper, riserA, SECOND, riserB, SECOND, VoltageTier.HV, GridEdge.Kind.BURIED);
                    wire(helper, riserB, ABC, sink, ABC, VoltageTier.HV, GridEdge.Kind.BARE);
                })
                .thenWaitUntil(() -> helper.assertTrue(stored(helper, load) > 0, "No power came through the corner, transposition and cable" + why(helper, generator)))
                .thenSucceed();
    }

    // A breaker's state (open, its trip setting, a CT, its interlock link) and a regulator's tap come back from its saved
    // data as they were.
    private static void deviceStateSurvivesSaving(GameTestHelper helper) {
        floor(helper, 20);
        ServerLevel level = helper.getLevel();
        TowerBlockEntity breaker = build(helper, TowerTier.MV_RECLOSER, 4, 4, 0);
        TowerBlockEntity disconnect = build(helper, TowerTier.MV_DISCONNECT, 12, 4, 0);
        breaker.configure(false);
        breaker.configure(false);
        breaker.fit(true);
        breaker.operate(level);
        disconnect.toggleControl(breaker.getBlockPos());
        helper.runAfterDelay(2, () -> {
            for (TowerBlockEntity device : new TowerBlockEntity[] { breaker, disconnect }) {
                CompoundTag tag = device.saveWithFullMetadata(level.registryAccess());
                BlockState state = device.getBlockState();
                BlockEntity copy = BlockEntity.loadStatic(device.getBlockPos(), state, tag, level.registryAccess());
                helper.assertTrue(copy instanceof TowerBlockEntity, "It didn't load back");
                TowerBlockEntity loaded = (TowerBlockEntity) copy;
                helper.assertTrue(loaded.closed() == device.closed(), "Its position wasn't kept");
                helper.assertTrue(String.valueOf(loaded.hudMode()).equals(String.valueOf(device.hudMode())),
                        "Its settings weren't kept: " + loaded.hudMode() + " against " + device.hudMode());
            }
            disconnect.operate(level);
            helper.assertFalse(disconnect.closed(), "The disconnect didn't open with its breaker open");
            helper.succeed();
        });
    }

    // Generator -> breaker A -> breaker B -> load, both with a CT and on one Relay Panel (its inverse curves, and the
    // instantaneous element where nothing protective is past it); a fault past B: B trips, A holds.
    private static void relayPanelGradesTwoBreakers(GameTestHelper helper) {
        floor(helper, 40);
        ServerLevel level = helper.getLevel();
        TowerBlockEntity generator = generator(helper, TowerTier.MV_UNIT, 0, 0);
        TowerBlockEntity a = build(helper, TowerTier.MV_RECLOSER, 10, 4, 6);
        TowerBlockEntity b = build(helper, TowerTier.MV_RECLOSER, 20, 4, 6);
        TowerBlockEntity panel = build(helper, TowerTier.RELAY_PANEL, 15, 10, 0);
        cell(helper, "tempered_energy_cell", 30, -1);
        TowerBlockEntity sink = unit(helper, TowerTier.MV_UNIT, 30, 0, 2);
        helper.assertTrue(a.fit(true).equals("fitted") && b.fit(true).equals("fitted"), "The CTs weren't fitted");
        panel.toggleControl(a.getBlockPos());
        panel.toggleControl(b.getBlockPos());
        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    wire(helper, generator, ABC, a, ABC, VoltageTier.MV, GridEdge.Kind.BARE);
                    wire(helper, a, SECOND, b, ABC, VoltageTier.MV, GridEdge.Kind.BARE);
                    wire(helper, b, SECOND, sink, ABC, VoltageTier.MV, GridEdge.Kind.BARE);
                })
                .thenExecuteAfter(20, () -> {
                    Long node = GridData.of(level).nodeAt(new GridData.Anchor(sink.getBlockPos(), 0));
                    GridManager.fault(level, node, -1, FaultEvent.Cause.GROUND_FAULT, true);
                })
                .thenWaitUntil(() -> helper.assertFalse(b.closed(), "The relayed breaker nearest the fault hasn't tripped"))
                .thenExecute(() -> helper.assertTrue(a.closed(), "The relayed breaker further from the fault tripped too"))
                .thenSucceed();
    }

    // Generator -> recloser (a CT, on a Relay Panel) -> three fuse cutouts (one a phase) -> load; a fault on phase A past
    // its fuse. Fuse-saving: the recloser trips first, fast, and the fuse survives. Fuse-blowing: the fuse blows, and the
    // recloser holds.
    private static void fuseSavingAndFuseBlowing(GameTestHelper helper) {
        floor(helper, 60);
        ServerLevel level = helper.getLevel();
        TowerBlockEntity[] recloser = new TowerBlockEntity[2];
        TowerBlockEntity[][] fuses = new TowerBlockEntity[2][3];
        TowerBlockEntity[] sinks = new TowerBlockEntity[2];
        TowerBlockEntity[] generators = new TowerBlockEntity[2];
        TowerBlockEntity[] panels = new TowerBlockEntity[2];
        for (int f = 0; f < 2; f++) {
            int z = f * 12;
            generators[f] = generator(helper, TowerTier.MV_UNIT, 0, z);
            recloser[f] = build(helper, TowerTier.MV_RECLOSER, 10, z + 4, 6);
            panels[f] = build(helper, TowerTier.RELAY_PANEL, 10, z + 9, 0);
            for (int k = 0; k < 3; k++) {
                fuses[f][k] = build(helper, TowerTier.MV_FUSE, 20 + 4 * k, z + 4, 6);
            }
            cell(helper, "tempered_energy_cell", 40, z - 1);
            sinks[f] = unit(helper, TowerTier.MV_UNIT, 40, z, 2);
            helper.assertTrue(recloser[f].fit(true).equals("fitted"), "The CT wasn't fitted");
            panels[f].toggleControl(recloser[f].getBlockPos());
        }
        // The second panel set fuse-blowing.
        panels[1].configure(true);
        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    GridData.Dimension dimension = GridData.of(level);
                    for (int f = 0; f < 2; f++) {
                        wire(helper, generators[f], ABC, recloser[f], ABC, VoltageTier.MV, GridEdge.Kind.BARE);
                        for (int k = 0; k < 3; k++) {
                            // Each cutout is one conductor, labelled for its phase.
                            for (int i = 0; i < 2; i++) {
                                dimension.graph().setPhase(dimension.nodeAt(new GridData.Anchor(fuses[f][k].getBlockPos(), i)), Phase.values()[k]);
                            }
                            wire(helper, recloser[f], new int[] { 3 + k }, fuses[f][k], new int[] { 0 }, VoltageTier.MV, GridEdge.Kind.BARE);
                            wire(helper, fuses[f][k], new int[] { 1 }, sinks[f], new int[] { k }, VoltageTier.MV, GridEdge.Kind.BARE);
                        }
                    }
                })
                .thenExecuteAfter(20, () -> {
                    for (int f = 0; f < 2; f++) {
                        helper.assertTrue(sinks[f].status() == FlowSolver.PortStatus.OK, "Feeder " + f + " isn't working: " + sinks[f].status()
                                + why(helper, generators[f]));
                        Long node = GridData.of(level).nodeAt(new GridData.Anchor(sinks[f].getBlockPos(), 0));
                        GridManager.fault(level, node, -1, FaultEvent.Cause.GROUND_FAULT, true);
                    }
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(recloser[0].lockedOut() || !recloser[0].closed() || recloser[0].tripsSinceHeld() > 0,
                            "The fuse-saving recloser hasn't tripped");
                    helper.assertFalse(fuses[1][0].closed(), "The fuse past the fuse-blowing recloser hasn't blown");
                })
                .thenExecute(() -> {
                    helper.assertTrue(fuses[0][0].closed(), "The fuse past the fuse-saving recloser blew");
                    helper.assertTrue(recloser[1].closed() && recloser[1].tripsSinceHeld() == 0, "The fuse-blowing recloser tripped");
                })
                .thenSucceed();
    }

    // A Steam Turbine Array (steam kept up) pushes into an EHV unit transformer on Input beside it; the EHV line turns at
    // a dead-end tower at 45 degrees, transposes, goes underground between two risers, and an EHV substation transformer
    // steps it to HV for an HV unit transformer on Output, which fills a cell. Statuses: both units OK.
    private static void steamTurbineFeedsEhvLine(GameTestHelper helper) {
        floor(helper, 126);
        ServerLevel level = helper.getLevel();
        // The turbine: a 5-long shell of casing along X at (2..6, 1..3, 8..10), hollow down its middle.
        BlockPos origin = new BlockPos(2, 1, 8);
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 3; j++) {
                for (int k = 0; k < 3; k++) {
                    boolean inside = j == 1 && k == 1 && i > 0 && i < 4;
                    if (!inside) {
                        helper.setBlock(origin.offset(i, j, k), ModBlocks.STEAM_TURBINE_ARRAY_CASING.get());
                    }
                }
            }
        }
        // The EHV unit goes north of the turbine, facing it (south), its front face against the turbine's north side.
        TowerBlockEntity[] generator = new TowerBlockEntity[1];
        TowerBlockEntity corner = build(helper, TowerTier.EHV_DEAD_END_TOWER, 30, 0, 7);
        TowerBlockEntity transposition = build(helper, TowerTier.EHV_TRANSPOSITION, 60, 0, 6);
        TowerBlockEntity riserA = build(helper, TowerTier.EHV_RISER, 80, 0, 6);
        TowerBlockEntity riserB = build(helper, TowerTier.EHV_RISER, 96, 0, 6);
        TowerBlockEntity substation = build(helper, TowerTier.EHV_SUBSTATION, 106, 0, 6);
        BlockPos load = cell(helper, "tempered_energy_cell", 120, -1);
        TowerBlockEntity sink = unit(helper, TowerTier.HV_UNIT, 120, 0, 2);
        helper.onEachTick(() -> {
            if (level.getBlockEntity(helper.absolutePos(origin)) instanceof SteamTurbineArrayBlockEntity turbine && turbine.isMaster()) {
                turbine.getSteam().set(0, SteamGrade.SUPERHEATED.resource(), 60000);
            }
        });
        helper.startSequence()
                .thenIdle(3)
                .thenExecute(() -> {
                    SteamTurbineArrayBlockEntity turbine = level.getBlockEntity(helper.absolutePos(origin)) instanceof SteamTurbineArrayBlockEntity t ? t : null;
                    helper.assertTrue(turbine != null && turbine.isMaster(), "The turbine didn't form");
                    // Its energy port on the middle of its north side, where the unit's front face meets it.
                    BlockPos port = helper.absolutePos(origin.offset(2, 0, 0));
                    MultiblockPorts.set(level, turbine, port, SideMode.ENERGY, net.minecraft.core.Direction.NORTH);
                    boolean set = MultiblockPorts.list(level, turbine).stream()
                            .anyMatch(p -> p.mode() == SideMode.ENERGY && p.pos().equals(port) && p.face() == net.minecraft.core.Direction.NORTH);
                    helper.assertTrue(set, "The turbine's energy port didn't go on its north side: " + MultiblockPorts.list(level, turbine));
                    // The unit: placed so one of its blocks sits just north of the port, facing south.
                    BlockPos touching = port.north();
                    int eighths = 2 * TowerPlacement.turns(net.minecraft.core.Direction.SOUTH);
                    TowerBlockEntity unit = null;
                    for (int dx = -4; dx <= 4 && unit == null; dx++) {
                        for (int dz = -8; dz <= 0 && unit == null; dz++) {
                            BlockPos centre = new BlockPos(touching.getX() + dx, helper.absolutePos(new BlockPos(0, 1, 0)).getY(), touching.getZ() + dz);
                            var cells = TowerPlacement.positions(TowerTier.EHV_UNIT, eighths, centre);
                            if (cells.contains(touching) && cells.stream().noneMatch(c -> c.getZ() >= port.getZ())) {
                                BlockPos relative = centre.subtract(helper.absolutePos(BlockPos.ZERO));
                                unit = build(helper, TowerTier.EHV_UNIT, relative.getX(), relative.getZ(), eighths);
                            }
                        }
                    }
                    helper.assertTrue(unit != null, "No room for the EHV unit against the turbine");
                    unit.configure(false);
                    generator[0] = unit;
                })
                .thenExecuteAfter(2, () -> {
                    wire(helper, generator[0], ABC, corner, ABC, VoltageTier.EHV, GridEdge.Kind.BARE);
                    wire(helper, corner, SECOND, transposition, ABC, VoltageTier.EHV, GridEdge.Kind.BARE);
                    wire(helper, transposition, SECOND, riserA, ABC, VoltageTier.EHV, GridEdge.Kind.BARE);
                    wire(helper, riserA, SECOND, riserB, SECOND, VoltageTier.EHV, GridEdge.Kind.BURIED);
                    wire(helper, riserB, ABC, substation, ABC, VoltageTier.EHV, GridEdge.Kind.BARE);
                    wire(helper, substation, SECOND, sink, ABC, VoltageTier.HV, GridEdge.Kind.BARE);
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(stored(helper, load) > 0, "No turbine power reached the HV load" + why(helper, generator[0]));
                    helper.assertTrue(generator[0].status() == FlowSolver.PortStatus.OK, "The EHV unit says " + generator[0].status());
                    helper.assertTrue(sink.status() == FlowSolver.PortStatus.OK, "The HV unit says " + sink.status());
                })
                .thenSucceed();
    }

    // Preferred feeder (a generator through a recloser) and alternate feeder (another generator) into an MV transfer
    // switch; its load a unit on Output, with a generator of its own on the load side. The preferred's recloser opened:
    // its side is dead from its own sources (the load side's generator back through the switch doesn't count), so the
    // switch moves to the alternate. Held (Configure: manual), it stays where it is.
    private static void transferSwitch(GameTestHelper helper) {
        floor(helper, 50);
        ServerLevel level = helper.getLevel();
        TowerBlockEntity preferred = generator(helper, TowerTier.MV_UNIT, 0, 0);
        TowerBlockEntity recloser = build(helper, TowerTier.MV_RECLOSER, 10, 4, 6);
        TowerBlockEntity alternate = generator(helper, TowerTier.MV_UNIT, 0, 14);
        TowerBlockEntity ats = build(helper, TowerTier.MV_ATS, 22, 8, 6);
        cell(helper, "tempered_energy_cell", 34, 7);
        TowerBlockEntity load = unit(helper, TowerTier.MV_UNIT, 34, 8, 2);
        TowerBlockEntity backfeed = generator(helper, TowerTier.MV_UNIT, 40, 14);
        int transfer = GridConfig.TRANSFER_TICKS.getAsInt();
        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    wire(helper, preferred, ABC, recloser, ABC, VoltageTier.MV, GridEdge.Kind.BARE);
                    wire(helper, recloser, SECOND, ats, ABC, VoltageTier.MV, GridEdge.Kind.BARE);
                    wire(helper, alternate, ABC, ats, new int[] { 6, 7, 8 }, VoltageTier.MV, GridEdge.Kind.BARE);
                    wire(helper, ats, SECOND, load, ABC, VoltageTier.MV, GridEdge.Kind.BARE);
                    wire(helper, backfeed, ABC, load, ABC, VoltageTier.MV, GridEdge.Kind.BARE);
                })
                .thenExecuteAfter(10, () -> {
                    helper.assertTrue(String.valueOf(ats.hudMode()).contains("preferred"), "It didn't start on the preferred: " + ats.hudMode());
                    // Held: the preferred dies and it stays.
                    ats.configure(false);
                    recloser.operate(level);
                    helper.assertFalse(recloser.closed(), "The preferred's recloser didn't open");
                })
                .thenExecuteAfter(transfer + 10, () -> {
                    helper.assertTrue(String.valueOf(ats.hudMode()).contains("preferred"), "Held, it moved anyway: " + ats.hudMode());
                    ats.configure(false);
                })
                .thenWaitUntil(() -> helper.assertTrue(String.valueOf(ats.hudMode()).contains("alternate"),
                        "Automatic, it didn't move to the alternate with the preferred dead from its own side: " + ats.hudMode()))
                .thenSucceed();
    }

    // The grid's save: written and read back, every node keeps its key and every wire (and a regulator's boost) is as it
    // was. Then a unit's chunk "unloads" (its block entity gone from the grid) and loads again: the load stops being
    // fed while it's away and is fed again after.
    private static final double BOOST = 0.0125;

    private static void gridSaveReloadsAndChunksUnload(GameTestHelper helper) {
        floor(helper, 40);
        ServerLevel level = helper.getLevel();
        TowerBlockEntity generator = generator(helper, TowerTier.MV_UNIT, 0, 0);
        TowerBlockEntity regulator = build(helper, TowerTier.MV_REGULATOR, 14, 4, 6);
        BlockPos loadCell = cell(helper, "tempered_energy_cell", 30, -1);
        TowerBlockEntity sink = unit(helper, TowerTier.MV_UNIT, 30, 0, 2);
        long[] before = new long[1];
        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    wire(helper, generator, ABC, regulator, ABC, VoltageTier.MV, GridEdge.Kind.BARE);
                    wire(helper, regulator, SECOND, sink, ABC, VoltageTier.MV, GridEdge.Kind.BARE);
                })
                .thenWaitUntil(() -> helper.assertTrue(stored(helper, loadCell) > 0, "Nothing reached the load" + why(helper, generator)))
                .thenExecute(() -> {
                    // A boost on the regulator's link (as its tap would set), saved with the rest.
                    GridData.Dimension grid = GridData.of(level);
                    Long linked = grid.nodeAt(new GridData.Anchor(regulator.getBlockPos(), 0));
                    grid.graph().edgesOf(linked).stream().filter(GridEdge::isJumper).findFirst().orElseThrow().setBoost(BOOST);
                    GridData data = GridData.get(level.getServer());
                    Tag saved = GridData.TYPE.codec().encodeStart(NbtOps.INSTANCE, data).getOrThrow();
                    GridData loaded = GridData.TYPE.codec().parse(NbtOps.INSTANCE, saved).getOrThrow();
                    String name = level.dimension().identifier().toString();
                    GridData.Dimension now = GridData.of(level), back = loaded.dimension(name);
                    helper.assertTrue(back != null, "The dimension didn't come back");
                    for (BlockEntity block : new BlockEntity[] { generator, regulator, sink }) {
                        var ids = now.nodesAt(block.getBlockPos());
                        helper.assertTrue(ids.equals(back.nodesAt(block.getBlockPos())), "A block's nodes changed: " + ids + " against "
                                + back.nodesAt(block.getBlockPos()));
                        for (long id : ids) {
                            helper.assertTrue(id < 0 || !back.node(id).key().isEmpty() && back.node(id).key().equals(now.node(id).key()),
                                    "A node lost its key");
                        }
                    }
                    var edges = GridData.edgesOf(now);
                    helper.assertTrue(edges.equals(GridData.edgesOf(back)), "The wires changed: " + edges + " against " + GridData.edgesOf(back));
                    helper.assertTrue(GridData.edgesOf(back).stream().anyMatch(e -> Math.abs(e.boost() - BOOST) < 1.0E-12), "The regulator's boost wasn't saved");
                    // The unit's chunk goes.
                    sink.onChunkUnloaded();
                    before[0] = stored(helper, loadCell);
                })
                .thenExecuteAfter(10, () -> {
                    helper.assertTrue(stored(helper, loadCell) == before[0], "The load was fed while its unit was unloaded");
                    sink.onLoad();
                    before[0] = stored(helper, loadCell);
                })
                .thenWaitUntil(() -> helper.assertTrue(stored(helper, loadCell) > before[0], "The load wasn't fed again after its unit came back"))
                .thenSucceed();
    }

    // A save written before each tier's volts and amps doubled: an LV wire and an EHV one (its tier still "ev", from before
    // the rename), in the format of the time. It needs no migration: tiers are saved by name, so it loads as it was and its
    // wires carry the new ratings.
    private static void oldSaveLoadsAtTheNewRatings(GameTestHelper helper) {
        ListTag nodes = new ListTag(), edges = new ListTag();
        String[] tiers = { "lv", "lv", "ev", "ev" };
        for (int i = 0; i < tiers.length; i++) {
            CompoundTag node = new CompoundTag();
            node.putLong("id", i + 1);
            node.put("pos", new IntArrayTag(new int[] { i * 16, 64, 0 }));
            node.putInt("index", 0);
            node.putString("tier", tiers[i]);
            node.putString("phase", "a");
            ListTag at = new ListTag();
            at.add(DoubleTag.valueOf(i * 16 + 0.5));
            at.add(DoubleTag.valueOf(70.0));
            at.add(DoubleTag.valueOf(0.5));
            node.put("at", at);
            nodes.add(node);
        }
        for (int i = 0; i < 2; i++) {
            CompoundTag edge = new CompoundTag();
            edge.putLong("id", 10 + i);
            edge.putLong("a", 2 * i + 1);
            edge.putLong("b", 2 * i + 2);
            edge.putString("tier", tiers[2 * i]);
            edge.putDouble("length", 16.2);
            edges.add(edge);
        }
        CompoundTag dimension = new CompoundTag();
        dimension.putString("dimension", "arcforgetransmission:old_save");
        dimension.putLong("next_id", 20);
        dimension.put("nodes", nodes);
        dimension.put("edges", edges);
        ListTag saved = new ListTag();
        saved.add(dimension);
        GridData loaded = GridData.TYPE.codec().parse(NbtOps.INSTANCE, saved).getOrThrow();
        GridData.Dimension grid = loaded.dimension("arcforgetransmission:old_save");
        helper.assertTrue(grid != null, "The old save didn't load");
        var wires = GridData.edgesOf(grid);
        helper.assertTrue(wires.size() == 2, "Wires lost: " + wires);
        helper.assertTrue(wires.stream().anyMatch(w -> w.id() == 10 && w.tier() == VoltageTier.LV)
                && wires.stream().anyMatch(w -> w.id() == 11 && w.tier() == VoltageTier.EHV), "Tiers changed: " + wires);
        helper.assertTrue(grid.graph().node(1).tier() == VoltageTier.LV && grid.graph().node(3).tier() == VoltageTier.EHV, "Nodes' tiers changed");
        // The loaded wires run at the new ratings: the tiers' voltages, and the config's defaults for their current (a file
        // written before keeps its own, and config reset lists it).
        helper.assertTrue(VoltageTier.LV.volts() == 256 && VoltageTier.EHV.volts() == 16_384, "Tier voltages");
        var settings = ConfigReset.settings();
        helper.assertTrue(((Number) settings.get("wires.lv.ratedAmps").getDefault()).doubleValue() == 16.0
                && ((Number) settings.get("wires.ehv.ratedAmps").getDefault()).doubleValue() == 128.0
                && ((Number) settings.get("transformers.poleRating").getDefault()).intValue() == 4_096, "The config's defaults aren't the new ratings");
        helper.succeed();
    }
}
