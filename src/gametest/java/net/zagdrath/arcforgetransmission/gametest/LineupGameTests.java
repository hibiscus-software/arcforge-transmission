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
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.factory.FactoryContent;
import net.zagdrath.arcforgetransmission.factory.Lineup;
import net.zagdrath.arcforgetransmission.factory.LineupRatings;
import net.zagdrath.arcforgetransmission.factory.SectionBlock;
import net.zagdrath.arcforgetransmission.factory.SectionBlockEntity;
import net.zagdrath.arcforgetransmission.factory.SectionItem;
import net.zagdrath.arcforgetransmission.grid.ConnectionRules;
import net.zagdrath.arcforgetransmission.grid.FaultEvent;
import net.zagdrath.arcforgetransmission.grid.GridEdge;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.world.GridConfig;
import net.zagdrath.arcforgetransmission.grid.world.GridData;
import net.zagdrath.arcforgetransmission.grid.world.GridManager;
import net.zagdrath.arcforgetransmission.tower.TowerBlockEntity;
import net.zagdrath.arcforgetransmission.tower.TowerItem;
import net.zagdrath.arcforgetransmission.tower.TowerPlacement;
import net.zagdrath.arcforgetransmission.tower.TowerTier;
import net.zagdrath.arcforgetransmission.tower.model.FactoryMesh;
import net.zagdrath.arcforgetransmission.tower.model.FactoryMesh.OpState;
import net.zagdrath.arcforgetransmission.tower.model.FactoryMesh.Section;

// Switchgear lineups in a running world, at every tier, fed from unit transformers on creative energy cells (its own
// tier's, on Input) and feeding unit transformers on Output (its bus's tier):
//  - a full lineup carries its transformer's rating to its feeders, its winding losing what its %R says (MV, HV, EHV); LV
//    carries its incoming circuit;
//  - a fault on one feeder trips that feeder only (the main holds: selective);
//  - a transformer past its fan rating heats and trips its incomer on over-temperature (MV, HV, EHV);
//  - an EHV lineup with no transformer passes 2,097,152 FE/t from its incomer to EHV feeders;
//  - a double-ended lineup that loses an incomer carries both halves once its tie closes (its main opened first: the two
//    mains and the tie never all closed);
//  - a row of mixed tiers reports it, and does nothing;
//  - a section's breakers, disconnector, earthing switch and tap come back whole from its saved data.
@EventBusSubscriber(modid = ArcforgeTransmission.MODID)
public final class LineupGameTests {
    private static final Map<String, Consumer<GameTestHelper>> TESTS = new LinkedHashMap<>();
    private static final VoltageTier[] TIERS = VoltageTier.values();

    static {
        for (VoltageTier tier : TIERS) {
            String t = tier.id();
            TESTS.put(t + "_lineup_carries_its_rating", h -> carriesItsRating(h, tier));
            TESTS.put(t + "_feeder_fault_trips_only_its_feeder", h -> feederFault(h, tier));
            TESTS.put(t + "_double_ended_lineup_ties_a_lost_half", h -> doubleEnded(h, tier));
            TESTS.put(t + "_section_state_survives_saving", h -> stateSurvivesSaving(h, tier));
            if (tier != VoltageTier.LV) {
                TESTS.put(t + "_transformer_over_its_fan_rating_trips_the_incomer", h -> overTemperature(h, tier));
            }
        }
        TESTS.put("ehv_lineup_without_transformer_passes_a_full_circuit", LineupGameTests::ehvStraightThrough);
        TESTS.put("mixed_tier_lineup_reports_it", LineupGameTests::mixedTiers);
    }

    private LineupGameTests() {}

    @SubscribeEvent
    static void registerFunctions(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> TESTS.forEach((name, test) -> helper.register(FactoryGameTests.id(name), test)));
    }

    // Each test in its own batch (its own environment): some change the config for a while.
    @SubscribeEvent
    static void registerTests(RegisterGameTestsEvent event) {
        TESTS.keySet().forEach(name -> {
            Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(FactoryGameTests.id("lineup_" + name));
            TestData<Holder<TestEnvironmentDefinition<?>>> data = new TestData<>(environment, Level.OVERWORLD, Identifier.withDefaultNamespace("empty"), 2400,
                    0, true, Rotation.NONE, false, 1, 1, true, 64);
            event.registerTest(FactoryGameTests.id(name), new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, FactoryGameTests.id(name)),
                    data));
        });
    }

    // --- Building ---------------------------------------------------------------------------------------------------

    // A lineup's sections west to east from (x, 1, z), facing north; their block entities.
    static List<SectionBlockEntity> lineup(GameTestHelper helper, VoltageTier tier, int x, int z, Section... sections) {
        ServerLevel level = helper.getLevel();
        List<SectionBlockEntity> out = new ArrayList<>();
        int at = x;
        for (Section section : sections) {
            BlockPos main = helper.absolutePos(new BlockPos(at, 1, z));
            SectionBlock block = FactoryContent.SECTIONS.get(tier).get(section).get();
            BlockState state = block.defaultBlockState().setValue(SectionBlock.FACING, Direction.NORTH);
            level.setBlock(main, state, Block.UPDATE_ALL);
            SectionItem.place(level, main, block, Direction.NORTH);
            out.add((SectionBlockEntity) level.getBlockEntity(main));
            at += FactoryMesh.sectionWidth(tier.ordinal());
        }
        return out;
    }

    // A structure on (x, 1, z) facing `eighths` eighths of a turn from north.
    static TowerBlockEntity build(GameTestHelper helper, TowerTier tier, int x, int z, int eighths) {
        ServerLevel level = helper.getLevel();
        BlockPos centre = helper.absolutePos(new BlockPos(x, 1, z));
        Block block = BuiltInRegistries.BLOCK.getValue(FactoryGameTests.id(tier.id()));
        TowerItem.build(level, block, tier, centre, eighths);
        for (BlockPos pos : TowerPlacement.positions(tier, eighths, centre)) {
            if (level.getBlockEntity(pos) instanceof TowerBlockEntity tower) {
                return tower;
            }
        }
        throw new IllegalStateException("No " + tier.id() + " at " + centre);
    }

    private static TowerTier unitOf(VoltageTier tier) {
        return switch (tier) {
            case LV -> TowerTier.LV_UNIT;
            case MV -> TowerTier.MV_UNIT;
            case HV -> TowerTier.HV_UNIT;
            case EHV -> TowerTier.EHV_UNIT;
        };
    }

    // A source: a creative energy cell at (x, z) and, north of it facing it, a unit transformer of a tier on Input.
    static TowerBlockEntity source(GameTestHelper helper, VoltageTier tier, int x, int z) {
        BlockPos cell = FactoryGameTests.arcforge(helper, "creative_energy_cell", new BlockPos(x, 1, z));
        BlockState state = helper.getLevel().getBlockState(cell);
        for (var property : state.getProperties()) {
            if (property.getName().equals("facing") && property instanceof net.minecraft.world.level.block.state.properties.EnumProperty<?> facing
                    && facing.getValueClass() == Direction.class) {
                @SuppressWarnings("unchecked")
                var directional = (net.minecraft.world.level.block.state.properties.EnumProperty<Direction>) facing;
                helper.getLevel().setBlock(cell, state.setValue(directional, Direction.SOUTH), Block.UPDATE_ALL);
            }
        }
        TowerBlockEntity unit = build(helper, unitOf(tier), x, z - 1, 4);
        unit.configure(false);
        return unit;
    }

    // A sink: a unit transformer of a tier at (x, z) on Output, feeding a creative energy cell north of it.
    static TowerBlockEntity sink(GameTestHelper helper, VoltageTier tier, int x, int z) {
        FactoryGameTests.arcforge(helper, "creative_energy_cell", new BlockPos(x, 1, z - 1));
        TowerBlockEntity unit = build(helper, unitOf(tier), x, z, 0);
        unit.configure(false);
        unit.configure(false);
        return unit;
    }

    // The nodes of a unit transformer's bushings, A B C.
    static List<Long> unitNodes(GameTestHelper helper, TowerBlockEntity unit) {
        GridData.Dimension dimension = GridData.of(helper.getLevel());
        List<Long> out = new ArrayList<>();
        for (int k = 0; k < 3; k++) {
            out.add(dimension.nodeAt(new GridData.Anchor(unit.getBlockPos(), k)));
        }
        return out;
    }

    // A tray cable (three conductors, `size` wires' worth) between two circuits; fails the test if refused.
    static void cable(GameTestHelper helper, List<Long> a, List<Long> b, int size) {
        ConnectionRules.Refusal[] why = new ConnectionRules.Refusal[1];
        List<Long> made = GridManager.connectCircuit(helper.getLevel(), a, b, size > 1 ? GridEdge.Kind.BUS : GridEdge.Kind.CABLE, 4, size, 1.0, why);
        helper.assertTrue(!made.isEmpty(), "Cable refused: " + why[0]);
    }

    // A section's group of nodes.
    static List<Long> group(GameTestHelper helper, SectionBlockEntity section, int group) {
        return section.nodes(GridData.of(helper.getLevel()), group);
    }

    // Closes every breaker in a lineup west to east (an incomer, its transformer, the mains, the feeders; a tie only if
    // asked), failing the test if one refuses.
    static void closeAll(GameTestHelper helper, List<SectionBlockEntity> lineup, boolean tie) {
        for (SectionBlockEntity section : lineup) {
            if (section.section() == Section.TIE && !tie) {
                continue;
            }
            for (int i = 0; i < section.breakers().size(); i++) {
                if (!section.breakers().get(i).closed()) {
                    String said = section.toggle(helper.getLevel(), i).getString();
                    helper.assertTrue(section.breakers().get(i).closed(), section.getBlockState().getBlock().getName().getString() + " didn't close: " + said);
                }
            }
        }
    }

    static String planOf(List<SectionBlockEntity> lineup) {
        StringBuilder out = new StringBuilder();
        for (SectionBlockEntity s : lineup) {
            out.append(s.section()).append('=').append(s.problem()).append('/').append(s.role()).append(' ');
        }
        return out.toString();
    }

    // Puts a config value back when the test ends, passed or failed.
    static void restoring(GameTestHelper helper, Runnable restore) {
        helper.testInfo.addListener(new net.minecraft.gametest.framework.GameTestListener() {
            @Override
            public void testStructureLoaded(net.minecraft.gametest.framework.GameTestInfo info) {}

            @Override
            public void testPassed(net.minecraft.gametest.framework.GameTestInfo info, net.minecraft.gametest.framework.GameTestRunner runner) {
                restore.run();
            }

            @Override
            public void testFailed(net.minecraft.gametest.framework.GameTestInfo info, net.minecraft.gametest.framework.GameTestRunner runner) {
                restore.run();
            }

            @Override
            public void testAddedForRerun(net.minecraft.gametest.framework.GameTestInfo original, net.minecraft.gametest.framework.GameTestInfo rerun,
                    net.minecraft.gametest.framework.GameTestRunner runner) {}
        });
    }

    // The power through a transformer section's coupling, and its winding's loss, last tick.
    static double couplingFlow(GameTestHelper helper, SectionBlockEntity transformer) {
        return Math.abs(GridManager.view(helper.getLevel()).result().couplingFlow().getOrDefault(transformer.getBlockPos().asLong(), 0.0));
    }

    static double windingLoss(GameTestHelper helper, SectionBlockEntity transformer) {
        GridData.Dimension dimension = GridData.of(helper.getLevel());
        double loss = 0;
        for (long node : transformer.nodes(dimension, 1)) {
            for (GridEdge edge : dimension.graph().edgesOf(node)) {
                long other = edge.other(node);
                if (edge.isJumper() && transformer.nodes(dimension, 2).contains(other)) {
                    loss += GridManager.lastLoss(helper.getLevel()).getOrDefault(edge.id(), 0.0);
                }
            }
        }
        return loss;
    }

    // --- The tests ----------------------------------------------------------------------------------------------------

    // A full lineup (incomer, transformer, main, enough feeders) fed from two sources in parallel, each feeder a sink of
    // its bus's tier: the transformer passes its whole rating to the feeders, and its winding loses its %R of it. At LV
    // (no transformer) its incoming circuit reaches its feeders.
    private static void carriesItsRating(GameTestHelper helper, VoltageTier tier) {
        boolean transformer = tier != VoltageTier.LV;
        VoltageTier bus = transformer ? LineupRatings.lower(tier) : tier;
        long rating = transformer ? LineupRatings.transformerRating(GridConfig.SETTINGS, tier, GridConfig.LINEUP_TRANSFORMER_RATING.getAsDouble())
                : LineupRatings.circuit(GridConfig.SETTINGS, tier);
        int sinks = (int) Math.max(1, rating / LineupRatings.circuit(GridConfig.SETTINGS, bus));
        int perSection = tier.ordinal() <= 1 ? 2 : 1;
        List<Section> order = new ArrayList<>(List.of(Section.INCOMING));
        if (transformer) {
            order.add(Section.TRANSFORMER);
        }
        order.add(Section.MAIN);
        for (int i = 0; i < (sinks + perSection - 1) / perSection; i++) {
            order.add(Section.FEEDER);
        }
        FactoryGameTests.floor(helper, 60);
        List<SectionBlockEntity> lineup = lineup(helper, tier, 0, 2, order.toArray(new Section[0]));
        TowerBlockEntity a = source(helper, tier, 2, 10), b = source(helper, tier, 10, 10);
        List<TowerBlockEntity> loads = new ArrayList<>();
        for (int i = 0; i < sinks; i++) {
            loads.add(sink(helper, bus, 2 + i * 6, -4));
        }
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    cable(helper, unitNodes(helper, a), group(helper, lineup.get(0), 0), 1);
                    cable(helper, unitNodes(helper, b), group(helper, lineup.get(0), 0), 1);
                })
                .thenWaitUntil(() -> helper.assertTrue(lineup.stream().allMatch(SectionBlockEntity::planned), "Not a lineup: " + planOf(lineup)))
                .thenExecute(() -> {
                    int k = 0;
                    for (SectionBlockEntity section : lineup) {
                        if (section.section() != Section.FEEDER) {
                            continue;
                        }
                        for (int j = 0; j < section.breakers().size() && k < loads.size(); j++, k++) {
                            cable(helper, group(helper, section, 1 + j), unitNodes(helper, loads.get(k)), 1);
                        }
                    }
                })
                .thenExecute(() -> closeAll(helper, lineup, false))
                .thenExecuteAfter(60, () -> {
                    double delivered = loads.stream().mapToDouble(TowerBlockEntity::lastFlow).sum();
                    helper.assertTrue(delivered >= rating * 0.97, tier + " lineup delivered " + Math.round(delivered) + " FE/t of " + rating + ": "
                            + loads.stream().map(l -> l.status() + " " + Math.round(l.lastFlow())).toList());
                    if (transformer) {
                        SectionBlockEntity t = lineup.get(1);
                        double flow = couplingFlow(helper, t), loss = windingLoss(helper, t);
                        double r = GridConfig.LINEUP_RESISTANCE.getAsDouble();
                        helper.assertTrue(flow >= rating * 0.97, "The transformer passed " + Math.round(flow) + " of " + rating);
                        helper.assertTrue(Math.abs(loss / flow - r) < r * 0.15, "Winding loss " + loss + " is " + loss / flow + " of its power, not " + r);
                    }
                    helper.assertTrue(lineup.stream().allMatch(s -> s.breakers().stream().allMatch(br -> br.state() == OpState.CLOSED || s.section() == Section.TIE)),
                            "A breaker opened at the rating: " + planOf(lineup));
                })
                .thenSucceed();
    }

    // Incomer, (transformer), main and two feeder breakers to a sink each; a fault on one feeder's outlet: that feeder
    // trips, the main and the other feeder hold.
    private static void feederFault(GameTestHelper helper, VoltageTier tier) {
        boolean transformer = tier != VoltageTier.LV;
        VoltageTier bus = transformer ? LineupRatings.lower(tier) : tier;
        List<Section> order = new ArrayList<>(List.of(Section.INCOMING));
        if (transformer) {
            order.add(Section.TRANSFORMER);
        }
        order.add(Section.MAIN);
        order.add(Section.FEEDER);
        if (tier.ordinal() >= 2) {
            order.add(Section.FEEDER);
        }
        FactoryGameTests.floor(helper, 40);
        ServerLevel level = helper.getLevel();
        List<SectionBlockEntity> lineup = lineup(helper, tier, 0, 2, order.toArray(new Section[0]));
        TowerBlockEntity a = source(helper, tier, 2, 10);
        TowerBlockEntity good = sink(helper, bus, 2, -4), bad = sink(helper, bus, 12, -4);
        List<SectionBlockEntity> feeders = lineup.stream().filter(s -> s.section() == Section.FEEDER).toList();
        SectionBlockEntity faultySection = feeders.get(feeders.size() - 1);
        int faultyBreaker = faultySection.breakers().size() - 1;
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> cable(helper, unitNodes(helper, a), group(helper, lineup.get(0), 0), 1))
                .thenWaitUntil(() -> helper.assertTrue(lineup.stream().allMatch(SectionBlockEntity::planned), "Not a lineup: " + planOf(lineup)))
                .thenExecute(() -> {
                    cable(helper, group(helper, feeders.get(0), 1), unitNodes(helper, good), 1);
                    cable(helper, group(helper, faultySection, 1 + faultyBreaker), unitNodes(helper, bad), 1);
                })
                .thenExecute(() -> closeAll(helper, lineup, false))
                .thenExecuteAfter(20, () -> GridManager.fault(level, unitNodes(helper, bad).get(0), -1, FaultEvent.Cause.GROUND_FAULT, true))
                .thenWaitUntil(() -> helper.assertTrue(faultySection.breakers().get(faultyBreaker).state() == OpState.TRIPPED,
                        "The faulted feeder didn't trip: " + faultySection.breakers().get(faultyBreaker).state()))
                .thenExecute(() -> {
                    SectionBlockEntity main = lineup.stream().filter(s -> s.section() == Section.MAIN).findFirst().orElseThrow();
                    helper.assertTrue(main.breakers().get(0).closed(), "The main tripped too: not selective");
                    helper.assertTrue(feeders.get(0).breakers().get(0).closed(), "The other feeder tripped too");
                    helper.assertTrue(lineup.get(0).breakers().get(0).closed(), "The incomer tripped too");
                })
                .thenSucceed();
    }

    // A transformer rated at 65% of a circuit carrying a full circuit's worth (past its 133% fan rating): its fans run,
    // it heats, and it trips its incomer on over-temperature before anything else does.
    private static void overTemperature(GameTestHelper helper, VoltageTier tier) {
        double share = GridConfig.LINEUP_TRANSFORMER_RATING.getAsDouble();
        GridConfig.LINEUP_TRANSFORMER_RATING.set(0.65);
        restoring(helper, () -> GridConfig.LINEUP_TRANSFORMER_RATING.set(share));
        VoltageTier bus = LineupRatings.lower(tier);
        int sinks = (int) (LineupRatings.circuit(GridConfig.SETTINGS, tier) / LineupRatings.circuit(GridConfig.SETTINGS, bus));
        int perSection = tier.ordinal() <= 1 ? 2 : 1;
        List<Section> order = new ArrayList<>(List.of(Section.INCOMING, Section.TRANSFORMER, Section.MAIN));
        for (int i = 0; i < (sinks + perSection - 1) / perSection; i++) {
            order.add(Section.FEEDER);
        }
        FactoryGameTests.floor(helper, 60);
        List<SectionBlockEntity> lineup = lineup(helper, tier, 0, 2, order.toArray(new Section[0]));
        TowerBlockEntity a = source(helper, tier, 2, 10), b = source(helper, tier, 10, 10);
        List<TowerBlockEntity> loads = new ArrayList<>();
        for (int i = 0; i < sinks; i++) {
            loads.add(sink(helper, bus, 2 + i * 6, -4));
        }
        SectionBlockEntity transformer = lineup.get(1);
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    cable(helper, unitNodes(helper, a), group(helper, lineup.get(0), 0), 1);
                    cable(helper, unitNodes(helper, b), group(helper, lineup.get(0), 0), 1);
                })
                .thenWaitUntil(() -> helper.assertTrue(lineup.stream().allMatch(SectionBlockEntity::planned), "Not a lineup: " + planOf(lineup)))
                .thenExecute(() -> {
                    int k = 0;
                    for (SectionBlockEntity section : lineup) {
                        if (section.section() == Section.FEEDER) {
                            for (int j = 0; j < section.breakers().size() && k < loads.size(); j++, k++) {
                                cable(helper, group(helper, section, 1 + j), unitNodes(helper, loads.get(k)), 1);
                            }
                        }
                    }
                })
                .thenExecute(() -> closeAll(helper, lineup, false))
                .thenExecuteAfter(40, () -> {
                    helper.assertTrue(transformer.load() > GridConfig.FAN_RATING.getAsDouble(), "Not past its fan rating: " + transformer.load());
                    helper.assertTrue(transformer.fans(), "Its fans aren't running at " + transformer.load());
                    helper.assertTrue(transformer.heat() > 0, "It isn't heating at " + transformer.load());
                })
                .thenWaitUntil(() -> helper.assertTrue(lineup.get(0).breakers().get(0).state() == OpState.TRIPPED,
                        "The incomer hasn't tripped; heat " + transformer.heat() + ", load " + transformer.load()))
                .thenExecute(() -> {
                    SectionBlockEntity main = lineup.stream().filter(s -> s.section() == Section.MAIN).findFirst().orElseThrow();
                    helper.assertTrue(main.breakers().get(0).closed(), "The main tripped before the temperature did");
                })
                .thenSucceed();
    }

    // An EHV lineup with no transformer: incomer, main, feeder bay; its incomer's full circuit (2,097,152 FE/t) reaches
    // an EHV sink.
    private static void ehvStraightThrough(GameTestHelper helper) {
        VoltageTier tier = VoltageTier.EHV;
        FactoryGameTests.floor(helper, 40);
        List<SectionBlockEntity> lineup = lineup(helper, tier, 0, 2, Section.INCOMING, Section.MAIN, Section.FEEDER);
        TowerBlockEntity a = source(helper, tier, 2, 10), b = source(helper, tier, 10, 10);
        TowerBlockEntity load = sink(helper, tier, 4, -4);
        long full = LineupRatings.circuit(GridConfig.SETTINGS, tier);
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    cable(helper, unitNodes(helper, a), group(helper, lineup.get(0), 0), 1);
                    cable(helper, unitNodes(helper, b), group(helper, lineup.get(0), 0), 1);
                })
                .thenWaitUntil(() -> helper.assertTrue(lineup.stream().allMatch(SectionBlockEntity::planned), "Not a lineup: " + planOf(lineup)))
                .thenExecute(() -> cable(helper, group(helper, lineup.get(2), 1), unitNodes(helper, load), 1))
                .thenExecute(() -> closeAll(helper, lineup, false))
                .thenExecuteAfter(40, () -> {
                    helper.assertTrue(full == 2_097_152L, "An EHV circuit is " + full);
                    helper.assertTrue(load.lastFlow() >= full * 0.999, "The EHV sink got " + Math.round(load.lastFlow()) + " FE/t of " + full);
                    helper.assertTrue(lineup.stream().allMatch(s -> s.breakers().stream().allMatch(br -> br.closed())), "A breaker opened: " + planOf(lineup));
                })
                .thenSucceed();
    }

    // Incomer, (transformer), main, feeder, tie, feeder, main, (transformer), incomer; a sink on each feeder, one source
    // on each incomer. The west incomer opens: its half goes dead. Its main opens and the tie closes: the east half
    // carries both. The tie won't close while both mains are closed.
    private static void doubleEnded(GameTestHelper helper, VoltageTier tier) {
        boolean transformer = tier != VoltageTier.LV;
        VoltageTier bus = transformer ? LineupRatings.lower(tier) : tier;
        List<Section> half = new ArrayList<>(List.of(Section.INCOMING));
        if (transformer) {
            half.add(Section.TRANSFORMER);
        }
        half.add(Section.MAIN);
        half.add(Section.FEEDER);
        List<Section> order = new ArrayList<>(half);
        order.add(Section.TIE);
        List<Section> east = new ArrayList<>(half);
        java.util.Collections.reverse(east);
        order.addAll(east);
        FactoryGameTests.floor(helper, 60);
        ServerLevel level = helper.getLevel();
        List<SectionBlockEntity> lineup = lineup(helper, tier, 0, 2, order.toArray(new Section[0]));
        SectionBlockEntity westIn = lineup.get(0), eastIn = lineup.get(lineup.size() - 1);
        SectionBlockEntity westMain = lineup.get(half.indexOf(Section.MAIN)), tie = lineup.get(half.size());
        List<SectionBlockEntity> feeders = lineup.stream().filter(s -> s.section() == Section.FEEDER).toList();
        TowerBlockEntity a = source(helper, tier, 2, 12), b = source(helper, tier, 14, 12);
        TowerBlockEntity westLoad = sink(helper, bus, 2, -4), eastLoad = sink(helper, bus, 14, -4);
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    cable(helper, unitNodes(helper, a), group(helper, westIn, 0), 1);
                    cable(helper, unitNodes(helper, b), group(helper, eastIn, 0), 1);
                })
                .thenWaitUntil(() -> helper.assertTrue(lineup.stream().allMatch(SectionBlockEntity::planned), "Not a lineup: " + planOf(lineup)))
                .thenExecute(() -> {
                    cable(helper, group(helper, feeders.get(0), 1), unitNodes(helper, westLoad), 1);
                    cable(helper, group(helper, feeders.get(1), 1), unitNodes(helper, eastLoad), 1);
                })
                .thenExecute(() -> closeAll(helper, lineup, false))
                .thenWaitUntil(() -> helper.assertTrue(westLoad.lastFlow() > 0 && eastLoad.lastFlow() > 0, "Both halves aren't carrying"))
                .thenExecute(() -> {
                    // Both mains closed: the tie refuses.
                    tie.toggle(level, 0);
                    helper.assertTrue(!tie.breakers().get(0).closed(), "The tie closed with both mains closed");
                    westIn.toggle(level, 0);
                })
                .thenExecuteAfter(10, () -> {
                    helper.assertTrue(westLoad.lastFlow() == 0, "The west half is still fed with its incomer open: " + westLoad.lastFlow());
                    westMain.toggle(level, 0);
                    tie.toggle(level, 0);
                    helper.assertTrue(tie.breakers().get(0).closed(), "The tie didn't close with the west main open");
                })
                .thenWaitUntil(() -> helper.assertTrue(westLoad.lastFlow() > 0 && eastLoad.lastFlow() > 0, "The east half isn't carrying both: "
                        + westLoad.lastFlow() + " / " + eastLoad.lastFlow()))
                .thenSucceed();
    }

    // MV incomer, HV main, MV feeder: mixed tiers, every section says so and nothing is joined.
    private static void mixedTiers(GameTestHelper helper) {
        FactoryGameTests.floor(helper, 20);
        List<SectionBlockEntity> mv = lineup(helper, VoltageTier.MV, 0, 2, Section.INCOMING);
        List<SectionBlockEntity> hv = lineup(helper, VoltageTier.HV, 1, 2, Section.MAIN);
        List<SectionBlockEntity> mv2 = lineup(helper, VoltageTier.MV, 3, 2, Section.FEEDER);
        List<SectionBlockEntity> all = List.of(mv.get(0), hv.get(0), mv2.get(0));
        TowerBlockEntity a = source(helper, VoltageTier.MV, 2, 10);
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> cable(helper, unitNodes(helper, a), group(helper, mv.get(0), 0), 1))
                .thenWaitUntil(() -> helper.assertTrue(all.stream().allMatch(s -> s.problem() == Lineup.Problem.MIXED_TIER), "Not reported: " + planOf(all)))
                .thenExecute(() -> {
                    String said = mv.get(0).toggle(helper.getLevel(), 0).getString();
                    helper.assertTrue(!mv.get(0).breakers().get(0).closed(), "A section of a broken lineup closed: " + said);
                })
                .thenSucceed();
    }

    // A section's breakers (one closed, one tripped where it has two), an HV or EHV incomer's disconnector and earthing
    // switch, and a transformer's tap and heat come back from its saved data as they were.
    private static void stateSurvivesSaving(GameTestHelper helper, VoltageTier tier) {
        FactoryGameTests.floor(helper, 30);
        ServerLevel level = helper.getLevel();
        List<Section> order = new ArrayList<>(List.of(Section.INCOMING));
        if (tier != VoltageTier.LV) {
            order.add(Section.TRANSFORMER);
        }
        order.add(Section.MAIN);
        order.add(Section.FEEDER);
        List<SectionBlockEntity> lineup = lineup(helper, tier, 0, 2, order.toArray(new Section[0]));
        TowerBlockEntity a = source(helper, tier, 2, 10);
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> cable(helper, unitNodes(helper, a), group(helper, lineup.get(0), 0), 1))
                .thenWaitUntil(() -> helper.assertTrue(lineup.stream().allMatch(SectionBlockEntity::planned), "Not a lineup: " + planOf(lineup)))
                .thenExecute(() -> {
                    SectionBlockEntity feeder = lineup.get(lineup.size() - 1);
                    feeder.toggle(level, 0);
                    feeder.breakers().get(feeder.breakers().size() - 1).setState(OpState.TRIPPED);
                    feeder.breakers().get(0).setFrame(1);
                    for (SectionBlockEntity section : lineup) {
                        CompoundTag tag = section.saveWithFullMetadata(level.registryAccess());
                        BlockEntity copy = BlockEntity.loadStatic(section.getBlockPos(), section.getBlockState(), tag, level.registryAccess());
                        helper.assertTrue(copy instanceof SectionBlockEntity, "It didn't load back");
                        SectionBlockEntity loaded = (SectionBlockEntity) copy;
                        for (int i = 0; i < section.breakers().size(); i++) {
                            helper.assertTrue(java.util.Arrays.equals(loaded.breakers().get(i).save(), section.breakers().get(i).save()),
                                    section.section() + " breaker " + i + " wasn't kept");
                        }
                        helper.assertTrue(loaded.disconnector() == section.disconnector() && loaded.earthed() == section.earthed()
                                && loaded.tap() == section.tap(), section.section() + "'s switches or tap weren't kept");
                    }
                })
                .thenSucceed();
    }
}
