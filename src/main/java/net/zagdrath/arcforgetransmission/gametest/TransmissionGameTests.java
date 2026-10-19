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
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.grid.FaultEvent;
import net.zagdrath.arcforgetransmission.grid.GridEdge;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.block.GridContent;
import net.zagdrath.arcforgetransmission.grid.block.PoleTransformerBlock;
import net.zagdrath.arcforgetransmission.grid.world.GridConfig;
import net.zagdrath.arcforgetransmission.grid.world.GridData;
import net.zagdrath.arcforgetransmission.grid.world.GridManager;
import net.zagdrath.arcforgetransmission.tower.TowerBlockEntity;
import net.zagdrath.arcforgetransmission.tower.TowerItem;
import net.zagdrath.arcforgetransmission.tower.TowerTier;

// The grid in a running world (./gradlew runGameTestServer), built from the real blocks and wired by the grid's own calls:
//  - an MV feeder serves a house through the MV Distribution Transformer and a Service Meter;
//  - a yard (bus, disconnect, breaker, substation transformer, recloser, ground switch) carries power across tiers, and
//    its interlocks hold: the disconnect won't open under its closed breaker, the ground switch won't close on a live line
//    and the recloser won't close onto a grounded one;
//  - voltage sags along a feeder, and a regulator (or a capacitor bank) brings it back;
//  - a fault past two reclosers trips the nearer only;
//  - an HV line turns a corner on a dead-end tower at 45 degrees, transposes and goes underground between two risers;
//  - a device's state comes back whole from its saved data.
// Each test stands on a stone floor in an empty structure, with room round it.
public final class TransmissionGameTests {
    private static final String MODID = ArcforgeTransmission.MODID;
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, MODID);
    private static final Map<String, Consumer<GameTestHelper>> TESTS = new LinkedHashMap<>();
    // The voltage test makes MV wire lossy for a while: it runs in its own batch (its own environment), after the rest.
    private static final String LOSSY = "regulator_and_capacitor_bank_hold_a_sagging_feeder";

    static {
        TESTS.put("mv_feeder_serves_a_house", TransmissionGameTests::mvFeederServesAHouse);
        TESTS.put("yard_and_interlocks", TransmissionGameTests::yardAndInterlocks);
        TESTS.put("nearest_recloser_trips_first", TransmissionGameTests::nearestRecloserTripsFirst);
        TESTS.put("hv_line_turns_transposes_and_goes_underground", TransmissionGameTests::hvLineTurnsTransposesAndGoesUnderground);
        TESTS.put("device_state_survives_saving", TransmissionGameTests::deviceStateSurvivesSaving);
        TESTS.forEach((name, test) -> FUNCTIONS.register(name, () -> test));
        FUNCTIONS.register(LOSSY, () -> TransmissionGameTests::voltageIsHeld);
    }

    private TransmissionGameTests() {}

    public static void register(IEventBus modEventBus) {
        FUNCTIONS.register(modEventBus);
        modEventBus.addListener(TransmissionGameTests::registerTests);
    }

    private static void registerTests(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(id("default"));
        TestData<Holder<TestEnvironmentDefinition<?>>> data = new TestData<>(environment, Level.OVERWORLD, Identifier.withDefaultNamespace("empty"), 1600, 0,
                true, Rotation.NONE, false, 1, 1, true, 48);
        TESTS.keySet().forEach(name -> event.registerTest(id(name), new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id(name)), data)));
        Holder<TestEnvironmentDefinition<?>> lossy = event.registerEnvironment(id("lossy"));
        event.registerTest(id(LOSSY), new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id(LOSSY)), new TestData<>(lossy,
                Level.OVERWORLD, Identifier.withDefaultNamespace("empty"), 1600, 0, true, Rotation.NONE, false, 1, 1, true, 48)));
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
        return pos;
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
        GridData.Dimension dimension = GridData.of(helper.getLevel());
        GridManager.TickView view = GridManager.view(helper.getLevel());
        double sum = 0;
        for (int i = 0; i < 3; i++) {
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
                    ground.operate(level);
                    breaker.operate(level);
                    helper.assertFalse(breaker.closed(), "The breaker didn't open");
                    disconnect.operate(level);
                    helper.assertFalse(disconnect.closed(), "The disconnect didn't open with its breaker open");
                })
                .thenSucceed();
    }

    // Two generators' MV feeders, made lossy for the test (high resistance): one through an MV regulator, one with a
    // Capacitor Bank at its load end. Held, the regulator's load end sags; let run, it steps it back into its band. The
    // bank, switched in, raises its load end's voltage.
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
        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    setResistance(VoltageTier.MV, 2.5);
                    GridConfig.TAP_DELAY.set(2);
                    wire(helper, generator, ABC, regulator, ABC, VoltageTier.MV, GridEdge.Kind.BARE);
                    wire(helper, regulator, SECOND, sink, ABC, VoltageTier.MV, GridEdge.Kind.BARE);
                    wire(helper, generator2, ABC, sink2, ABC, VoltageTier.MV, GridEdge.Kind.BARE);
                    wire(helper, sink2, ABC, bank, ABC, VoltageTier.MV, GridEdge.Kind.BARE);
                })
                .thenExecuteAfter(60, () -> {
                    sagged[0] = volts(helper, sink);
                    sagged[1] = volts(helper, sink2);
                    helper.assertTrue(sagged[0] > 0 && sagged[0] < 0.97, "The regulated feeder didn't sag: " + sagged[0] + why(helper, generator));
                    helper.assertTrue(sagged[1] > 0 && sagged[1] < 0.99, "The bank's feeder didn't sag: " + sagged[1]);
                    regulator.configure(false);
                    bank.operate(helper.getLevel());
                    helper.assertTrue(bank.closed(), "The bank didn't switch in");
                })
                .thenWaitUntil(() -> {
                    double now = volts(helper, sink);
                    helper.assertTrue(now > sagged[0] + 0.02 && Math.abs(now - 1.0) <= GridConfig.TAP_BAND.getAsDouble() / 2 + 0.005,
                            "The regulator hasn't held it: " + now + " from " + sagged[0]);
                    helper.assertTrue(volts(helper, sink2) > sagged[1] + 0.01, "The bank didn't raise the voltage: " + volts(helper, sink2) + " from "
                            + sagged[1]);
                })
                .thenExecute(() -> {
                    setResistance(VoltageTier.MV, resistance);
                    GridConfig.TAP_DELAY.set(delay);
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
}
