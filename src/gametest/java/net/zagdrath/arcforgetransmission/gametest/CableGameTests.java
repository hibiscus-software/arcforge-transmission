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

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.factory.CableData;
import net.zagdrath.arcforgetransmission.factory.CableNetwork;
import net.zagdrath.arcforgetransmission.factory.CableTerminationBlockEntity;
import net.zagdrath.arcforgetransmission.factory.CableTrayBlock;
import net.zagdrath.arcforgetransmission.factory.CableTrayBlockEntity;
import net.zagdrath.arcforgetransmission.factory.FactoryContent;
import net.zagdrath.arcforgetransmission.grid.GridEdge;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.world.GridConfig;
import net.zagdrath.arcforgetransmission.grid.world.GridData;
import net.zagdrath.arcforgetransmission.tower.TowerBlockEntity;

// Tray cable in a running world. Each test lays its cables between two rows of terminations (x 1 and x 21, on the floor,
// pointing up) along tray on the floor at z 4:
//  - a cable follows the shortest run of tray, and is refused without one;
//  - four cables in a block derate each other, and a cable burns out over its derated rating though under its own;
//  - a full tray block and a mixed one (LV beside MV) are refused;
//  - EHV cable is refused in standard tray, laid in heavy-duty;
//  - cables come back from their saved data, and a tray block reloaded (its chunk unloaded) shows them again;
//  - breaking a tray block drops the cables through it, and takes their conductors.
@EventBusSubscriber(modid = ArcforgeTransmission.MODID)
public final class CableGameTests {
    private static final Map<String, Consumer<GameTestHelper>> TESTS = new LinkedHashMap<>();
    private static final int WEST = 1, EAST = 21, TRAY_Z = 4;

    static {
        TESTS.put("cable_follows_the_shortest_tray", CableGameTests::shortest);
        TESTS.put("crowded_tray_derates_and_burnout_uses_it", CableGameTests::derating);
        TESTS.put("full_and_mixed_trays_are_refused", CableGameTests::fullAndMixed);
        TESTS.put("ehv_cable_needs_heavy_duty_tray", CableGameTests::heavyOnly);
        TESTS.put("cables_survive_saving_and_unloading", CableGameTests::saving);
        TESTS.put("breaking_a_tray_drops_its_cables", CableGameTests::breaking);
    }

    private CableGameTests() {}

    @SubscribeEvent
    static void registerFunctions(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> TESTS.forEach((name, test) -> helper.register(FactoryGameTests.id(name), test)));
    }

    @SubscribeEvent
    static void registerTests(RegisterGameTestsEvent event) {
        TESTS.keySet().forEach(name -> {
            Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(FactoryGameTests.id("cable_" + name));
            TestData<Holder<TestEnvironmentDefinition<?>>> data = new TestData<>(environment, Level.OVERWORLD, Identifier.withDefaultNamespace("empty"), 400,
                    0, true, Rotation.NONE, false, 1, 1, true, 64);
            event.registerTest(FactoryGameTests.id(name), new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, FactoryGameTests.id(name)),
                    data));
        });
    }

    // --- Building ---------------------------------------------------------------------------------------------------

    // Tray blocks of a kind on the floor at test positions, each shaped for its neighbours once all are down.
    static List<BlockPos> tray(GameTestHelper helper, CableTrayBlock.Kind kind, List<BlockPos> at) {
        ServerLevel level = helper.getLevel();
        CableTrayBlock block = FactoryContent.TRAYS.get(kind).get();
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : at) {
            BlockPos pos = helper.absolutePos(p);
            level.setBlock(pos, block.defaultBlockState(), Block.UPDATE_ALL);
            out.add(pos);
        }
        for (BlockPos pos : out) {
            level.setBlock(pos, block.shaped(level, pos, level.getBlockState(pos)), Block.UPDATE_ALL);
        }
        return out;
    }

    // A straight run along x at z, from x0 to x1.
    static List<BlockPos> run(int x0, int x1, int z) {
        List<BlockPos> out = new ArrayList<>();
        for (int x = x0; x <= x1; x++) {
            out.add(new BlockPos(x, 1, z));
        }
        return out;
    }

    // `count` pairs of terminations of a tier facing each other across the tray (west at z 1.., east the same).
    static List<CableTerminationBlockEntity[]> pairs(GameTestHelper helper, VoltageTier tier, int count) {
        List<CableTerminationBlockEntity[]> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int z = 1 + i;
            out.add(new CableTerminationBlockEntity[] {
                    FactoryGameTests.terminate(helper, tier, helper.absolutePos(new BlockPos(WEST, 0, z)), Direction.UP),
                    FactoryGameTests.terminate(helper, tier, helper.absolutePos(new BlockPos(EAST, 0, z)), Direction.UP) });
        }
        return out;
    }

    // Lays a cable between a pair as a creative player would (any length): null if laid, else the refusal's key.
    static @Nullable String lay(GameTestHelper helper, CableTerminationBlockEntity[] pair, VoltageTier tier) {
        Component why = CableNetwork.lay(helper.getLevel(), pair[0], pair[1], tier, Integer.MAX_VALUE, new int[1]);
        return why == null ? null : why.getContents() instanceof TranslatableContents t ? t.getKey() : why.getString();
    }

    static void laid(GameTestHelper helper, CableTerminationBlockEntity[] pair, VoltageTier tier) {
        String why = lay(helper, pair, tier);
        helper.assertTrue(why == null, "The cable was refused: " + why);
    }

    static void refused(GameTestHelper helper, CableTerminationBlockEntity[] pair, VoltageTier tier, String key) {
        String why = lay(helper, pair, tier);
        helper.assertTrue(why != null && why.endsWith(".cable." + key), "Expected the cable refused (" + key + "), got " + why);
    }

    // This test's cables (other tests lay theirs in the same world): those ending in its bounds.
    static List<CableData.Cable> cables(GameTestHelper helper) {
        BlockPos min = helper.absolutePos(new BlockPos(-4, 0, -8)), max = helper.absolutePos(new BlockPos(48, 8, 16));
        return CableData.of(helper.getLevel()).all().stream().filter(c -> within(c.from(), min, max)).toList();
    }

    private static boolean within(BlockPos p, BlockPos a, BlockPos b) {
        return p.getX() >= Math.min(a.getX(), b.getX()) && p.getX() <= Math.max(a.getX(), b.getX()) && p.getY() >= Math.min(a.getY(), b.getY())
                && p.getY() <= Math.max(a.getY(), b.getY()) && p.getZ() >= Math.min(a.getZ(), b.getZ()) && p.getZ() <= Math.max(a.getZ(), b.getZ());
    }

    // --- The tests ----------------------------------------------------------------------------------------------------

    // With no tray between them (further apart than a free drop) a cable is refused. A straight run and a long loop round:
    // it lies in the straight, each block once, and takes an item for every few blocks of its length.
    private static void shortest(GameTestHelper helper) {
        FactoryGameTests.floor(helper, 24);
        List<CableTerminationBlockEntity[]> pairs = pairs(helper, VoltageTier.LV, 1);
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    refused(helper, pairs.get(0), VoltageTier.LV, "no_tray");
                    List<BlockPos> blocks = new ArrayList<>(run(3, 19, TRAY_Z));
                    for (int z = TRAY_Z + 1; z <= TRAY_Z + 6; z++) {
                        blocks.add(new BlockPos(3, 1, z));
                        blocks.add(new BlockPos(19, 1, z));
                    }
                    blocks.addAll(run(4, 18, TRAY_Z + 6));
                    tray(helper, CableTrayBlock.Kind.STANDARD, blocks);
                    laid(helper, pairs.get(0), VoltageTier.LV);
                    CableData.Cable cable = cables(helper).get(0);
                    helper.assertTrue(cable.path().size() == 17, "It lay in " + cable.path().size() + " tray blocks, not the straight's 17");
                    helper.assertTrue(cable.path().stream().allMatch(p -> p.getZ() == helper.absolutePos(new BlockPos(0, 0, TRAY_Z)).getZ()),
                            "It went round the loop");
                    helper.assertTrue(cable.items() == CableNetwork.items(cable.length()) && cable.length() > 16 && cable.length() < 26,
                            "Length " + cable.length() + ", " + cable.items() + " items");
                    helper.assertTrue(cable.edges().size() == 3, "A cable is three conductors");
                })
                .thenSucceed();
    }

    // Four LV cables share the tray: each is derated (the config's middle step) and the Clamp Meter's rating with it. One
    // of them carries an LV source's whole circuit to a sink: at its full rating, over the derated one, it burns out.
    private static void derating(GameTestHelper helper) {
        FactoryGameTests.floor(helper, 40);
        ServerLevel level = helper.getLevel();
        List<CableTerminationBlockEntity[]> pairs = pairs(helper, VoltageTier.LV, 4);
        TowerBlockEntity source = LineupGameTests.source(helper, VoltageTier.LV, 30, 14);
        TowerBlockEntity sink = LineupGameTests.sink(helper, VoltageTier.LV, 30, -4);
        double mid = GridConfig.DERATE_MID.getAsDouble();
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    tray(helper, CableTrayBlock.Kind.STANDARD, run(3, 19, TRAY_Z));
                    for (CableTerminationBlockEntity[] pair : pairs) {
                        laid(helper, pair, VoltageTier.LV);
                    }
                    GridData.Dimension grid = GridData.of(level);
                    for (CableData.Cable cable : cables(helper)) {
                        helper.assertTrue(Math.abs(CableNetwork.derate(level, cable) - mid) < 1.0E-9 && CableNetwork.crowd(level, cable) == 4,
                                "Four cables in a block should derate to " + mid);
                        for (long id : cable.edges()) {
                            GridEdge edge = grid.graph().edge(id);
                            helper.assertTrue(edge != null && Math.abs(GridConfig.SETTINGS.ratedAmps(edge) - mid * GridConfig.SETTINGS.ratedAmps(VoltageTier.LV)) < 1.0E-6,
                                    "A conductor's rating isn't derated");
                        }
                    }
                    CableTerminationBlockEntity[] first = pairs.get(0);
                    LineupGameTests.cable(helper, LineupGameTests.unitNodes(helper, source), first[0].nodes(grid), 1);
                    LineupGameTests.cable(helper, first[1].nodes(grid), LineupGameTests.unitNodes(helper, sink), 1);
                })
                .thenWaitUntil(() -> helper.assertTrue(cables(helper).size() == 3, "The derated cable hasn't burnt out: " + Math.round(sink.lastFlow())
                        + " FE/t through it"))
                .thenExecute(() -> {
                    // Three left in the block: none derated now.
                    for (CableData.Cable cable : cables(helper)) {
                        helper.assertTrue(CableNetwork.derate(level, cable) == 1.0, "Three cables in a block shouldn't be derated");
                    }
                })
                .thenSucceed();
    }

    // A standard tray block takes six LV cables, not a seventh; an MV cable isn't laid beside LV.
    private static void fullAndMixed(GameTestHelper helper) {
        FactoryGameTests.floor(helper, 24);
        int fill = GridConfig.TRAY_FILL_STANDARD.getAsInt() / GridConfig.cableSlots(VoltageTier.LV);
        List<CableTerminationBlockEntity[]> lv = pairs(helper, VoltageTier.LV, fill + 1);
        CableTerminationBlockEntity[] mv = new CableTerminationBlockEntity[] {
                FactoryGameTests.terminate(helper, VoltageTier.MV, helper.absolutePos(new BlockPos(WEST + 1, 0, 1 + fill + 1)), Direction.UP),
                FactoryGameTests.terminate(helper, VoltageTier.MV, helper.absolutePos(new BlockPos(EAST - 1, 0, 1 + fill + 1)), Direction.UP) };
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    tray(helper, CableTrayBlock.Kind.STANDARD, run(3, 19, TRAY_Z));
                    refusedOrLaid(helper, mv, lv);
                })
                .thenSucceed();
    }

    private static void refusedOrLaid(GameTestHelper helper, CableTerminationBlockEntity[] mv, List<CableTerminationBlockEntity[]> lv) {
        laid(helper, lv.get(0), VoltageTier.LV);
        refused(helper, mv, VoltageTier.MV, "segregated");
        for (int i = 1; i < lv.size() - 1; i++) {
            laid(helper, lv.get(i), VoltageTier.LV);
        }
        refused(helper, lv.get(lv.size() - 1), VoltageTier.LV, "full");
        helper.assertTrue(cables(helper).size() == lv.size() - 1, cables(helper).size() + " cables laid");
    }

    // An EHV cable won't go in standard tray; in heavy-duty it's laid.
    private static void heavyOnly(GameTestHelper helper) {
        FactoryGameTests.floor(helper, 24);
        List<CableTerminationBlockEntity[]> pairs = pairs(helper, VoltageTier.EHV, 1);
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    tray(helper, CableTrayBlock.Kind.STANDARD, run(3, 19, TRAY_Z));
                    refused(helper, pairs.get(0), VoltageTier.EHV, "heavy_only");
                    tray(helper, CableTrayBlock.Kind.HEAVY, run(3, 19, TRAY_Z));
                    laid(helper, pairs.get(0), VoltageTier.EHV);
                })
                .thenSucceed();
    }

    // Two cables: the saved data loads back the same, and a tray block loaded again from its save (as when its chunk comes
    // back) shows both again, as their saved data says they lie.
    private static void saving(GameTestHelper helper) {
        FactoryGameTests.floor(helper, 24);
        ServerLevel level = helper.getLevel();
        List<CableTerminationBlockEntity[]> pairs = pairs(helper, VoltageTier.MV, 2);
        List<BlockPos> trays = new ArrayList<>();
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    trays.addAll(tray(helper, CableTrayBlock.Kind.STANDARD, run(3, 19, TRAY_Z)));
                    laid(helper, pairs.get(0), VoltageTier.MV);
                    laid(helper, pairs.get(1), VoltageTier.MV);
                    CableData data = level.getServer().getDataStorage().computeIfAbsent(CableData.TYPE);
                    var encoded = CableData.TYPE.codec().encodeStart(NbtOps.INSTANCE, data).getOrThrow();
                    CableData loaded = CableData.TYPE.codec().parse(NbtOps.INSTANCE, encoded).getOrThrow();
                    var again = loaded.dimension(level);
                    helper.assertTrue(again != null && cables(helper).stream().allMatch(c -> c.equals(again.cable(c.id()))), "The cables didn't load back as they were");
                    BlockPos middle = trays.get(trays.size() / 2);
                    CableTrayBlockEntity tray = (CableTrayBlockEntity) level.getBlockEntity(middle);
                    CompoundTag tag = tray.saveWithFullMetadata(level.registryAccess());
                    level.removeBlockEntity(middle);
                    BlockEntity copy = BlockEntity.loadStatic(middle, level.getBlockState(middle), tag, level.registryAccess());
                    helper.assertTrue(copy instanceof CableTrayBlockEntity, "The tray didn't load back");
                    level.setBlockEntity(copy);
                    copy.onLoad();
                    helper.assertTrue(((CableTrayBlockEntity) copy).lies().size() == 2
                            && ((CableTrayBlockEntity) copy).lies().equals(CableNetwork.lies(level, middle)), "The reloaded tray doesn't show its cables");
                })
                .thenSucceed();
    }

    // Breaking a tray block the cable lies in drops its items there and takes its conductors off the grid.
    private static void breaking(GameTestHelper helper) {
        FactoryGameTests.floor(helper, 24);
        ServerLevel level = helper.getLevel();
        List<CableTerminationBlockEntity[]> pairs = pairs(helper, VoltageTier.LV, 1);
        List<BlockPos> trays = new ArrayList<>();
        List<Long> edges = new ArrayList<>();
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    trays.addAll(tray(helper, CableTrayBlock.Kind.STANDARD, run(3, 19, TRAY_Z)));
                    laid(helper, pairs.get(0), VoltageTier.LV);
                    edges.addAll(cables(helper).get(0).edges());
                    level.destroyBlock(trays.get(8), true);
                })
                .thenExecute(() -> {
                    helper.assertTrue(cables(helper).isEmpty(), "The cable is still there");
                    GridData.Dimension grid = GridData.of(level);
                    helper.assertTrue(edges.stream().allMatch(id -> grid.graph().edge(id) == null), "Its conductors are still on the grid");
                    var item = FactoryContent.CABLES.get(VoltageTier.LV).get();
                    helper.assertTrue(!level.getEntitiesOfClass(ItemEntity.class, new AABB(trays.get(8)).inflate(2), e -> e.getItem().is(item)).isEmpty(),
                            "No cable dropped at the tray");
                })
                .thenSucceed();
    }
}
