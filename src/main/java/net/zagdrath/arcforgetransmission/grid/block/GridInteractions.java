/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.block;

import java.util.List;
import java.util.StringJoiner;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.zagdrath.arcforge.item.tool.WrenchItem;
import net.zagdrath.arcforge.item.tool.WrenchMode;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.grid.GridNode;
import net.zagdrath.arcforgetransmission.grid.Phase;
import net.zagdrath.arcforgetransmission.grid.world.GridData;
import net.zagdrath.arcforgetransmission.grid.world.GridManager;
import net.zagdrath.arcforgetransmission.grid.world.Insulator;
import net.zagdrath.arcforgetransmission.tower.TowerBlockEntity;
import net.zagdrath.arcforgetransmission.tower.TowerTier;

// What Arcforge's Wrench does to anything with insulators (Arcforge's wrench passes on blocks that aren't its own, so
// they handle it in useItemOn):
//   Rotate     on a fuse cutout, steps its one conductor's label (line and load together, A B C);
//              on a transformer or device, turns it (sneaking: the other way), its wires kept;
//              on a tower's group of three-phase insulators, swaps B and C (ABC <-> ACB);
//              on a lone insulator (a pin insulator), steps its label (A B C, or L1 L2 N)
//   Port       switches the nearest insulator between three-phase and single-phase labels (only while it has no wires)
//   Configure  on a pin insulator, its mode (sneaking: its current limit); elsewhere, says the labels
//   Dismantle  (sneaking) on a pin insulator or transformer, takes it down whole
public final class GridInteractions {
    private GridInteractions() {}

    public static boolean isWrench(ItemStack stack) {
        return stack.getItem() instanceof WrenchItem;
    }

    // Handles a wrench on an endpoint (a tower's main block for any of its parts). Null if the stack isn't a wrench.
    public static @Nullable InteractionResult wrench(ItemStack stack, Level level, GridEndpointBlockEntity endpoint, Player player, Vec3 hit,
            boolean dismantleable) {
        if (!isWrench(stack)) {
            return null;
        }
        if (!(level instanceof ServerLevel server)) {
            return InteractionResult.SUCCESS;
        }
        BlockPos pos = endpoint.getBlockPos();
        // A Relay Panel has no insulators: Configure steps its time dial (sneaking: fuse-saving or fuse-blowing).
        if (endpoint instanceof TowerBlockEntity panel && panel.tier().kind() == TowerTier.Kind.RELAY) {
            if (WrenchItem.mode(stack) != WrenchMode.CONFIGURE) {
                return InteractionResult.PASS;
            }
            tell(player, panel.configure(player.isSecondaryUseActive()));
            level.playSound(null, pos, SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.BLOCKS, 0.3F, 1.4F);
            return InteractionResult.SUCCESS;
        }
        int index = endpoint.nearest(hit);
        if (index < 0) {
            return InteractionResult.PASS;
        }
        GridData.Anchor anchor = new GridData.Anchor(pos, index);
        List<Insulator> insulators = endpoint.insulators();
        int group = insulators.get(index).group();
        WrenchMode mode = WrenchItem.mode(stack);
        boolean sneaking = player.isSecondaryUseActive();
        switch (mode) {
            case ROTATE -> {
                // A fuse cutout is one conductor: its line and load step their label together (A -> B -> C).
                if (endpoint instanceof TowerBlockEntity fuse && fuse.tier().kind() == TowerTier.Kind.FUSE) {
                    for (int i = 0; i < insulators.size(); i++) {
                        GridData.Anchor each = new GridData.Anchor(pos, i);
                        GridNode node = GridManager.node(server, each);
                        if (node != null) {
                            GridManager.setPhase(server, each, node.phase().next());
                        }
                    }
                    tell(player, Component.translatable("message." + ArcforgeTransmission.MODID + ".phase.labels", labels(server, endpoint, group)));
                    GridManager.display(server, endpoint, true);
                    return InteractionResult.SUCCESS;
                }
                // A transformer turns, like an Arcforge machine (sneaking: the other way).
                if (endpoint instanceof TowerBlockEntity tower && tower.tier().rotatable()) {
                    boolean turned = tower.rotate(!sneaking);
                    tell(player, Component.translatable("message." + ArcforgeTransmission.MODID + ".transformer." + (turned ? "rotated" : "no_room")));
                    if (turned) {
                        level.playSound(null, pos, SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.BLOCKS, 0.3F, 1.4F);
                    }
                    return InteractionResult.SUCCESS;
                }
                long inGroup = insulators.stream().filter(i -> i.group() == group).count();
                GridNode node = GridManager.node(server, anchor);
                if (node == null) {
                    return InteractionResult.PASS;
                }
                if (inGroup > 1) {
                    if (!GridManager.swapBC(server, pos, group)) {
                        tell(player, Component.translatable("message." + ArcforgeTransmission.MODID + ".phase.no_rotation"));
                        return InteractionResult.SUCCESS;
                    }
                } else {
                    GridManager.setPhase(server, anchor, node.phase().next());
                }
                tell(player, Component.translatable("message." + ArcforgeTransmission.MODID + ".phase.labels", labels(server, endpoint, group)));
                if (endpoint instanceof PinInsulatorBlockEntity pinInsulator) {
                    GridManager.rejoin(server, pinInsulator.mountPos());
                }
                GridManager.display(server, endpoint, true);
            }
            case PORT -> {
                GridNode node = GridManager.node(server, anchor);
                if (node == null) {
                    return InteractionResult.PASS;
                }
                if (GridManager.isWired(server, anchor)) {
                    tell(player, Component.translatable("message." + ArcforgeTransmission.MODID + ".phase.unwire_first"));
                } else {
                    Phase phase = node.phase().otherCircuit();
                    GridManager.setPhase(server, anchor, phase);
                    tell(player, Component.translatable("message." + ArcforgeTransmission.MODID + ".phase.circuit",
                            Component.translatable("circuit." + ArcforgeTransmission.MODID + "." + phase.circuit().id()), phase.name()));
                    if (endpoint instanceof PinInsulatorBlockEntity pinInsulator) {
                        GridManager.rejoin(server, pinInsulator.mountPos());
                    }
                    GridManager.display(server, endpoint, true);
                }
            }
            case CONFIGURE -> {
                if (endpoint instanceof PinInsulatorBlockEntity pinInsulator) {
                    if (sneaking) {
                        int next = pinInsulator.limitEighths() % 8 + 1;
                        pinInsulator.setForPort(pinInsulator.mode(), next);
                        tell(player, Component.translatable("message." + ArcforgeTransmission.MODID + ".pin_insulator.limit",
                                format(pinInsulator.limitAmps()), next + "/8"));
                    } else {
                        PinInsulatorMode next = pinInsulator.mode().next();
                        pinInsulator.setForPort(next, pinInsulator.limitEighths());
                        tell(player, Component.translatable("message." + ArcforgeTransmission.MODID + ".pin_insulator.mode",
                                Component.translatable("pin_insulator_mode." + ArcforgeTransmission.MODID + "." + next.id())));
                    }
                } else if (endpoint instanceof TowerBlockEntity tower && tower.tier().kind() != TowerTier.Kind.TOWER
                        && tower.tier().kind() != TowerTier.Kind.DEAD_END && tower.configure(sneaking) != null) {
                    tell(player, tower.hudMode());
                } else {
                    tell(player, Component.translatable("message." + ArcforgeTransmission.MODID + ".phase.labels", labels(server, endpoint, group)));
                }
            }
            case DISMANTLE -> {
                if (!sneaking || !dismantleable) {
                    return InteractionResult.PASS;
                }
                level.destroyBlock(pos, !player.isCreative(), player);
            }
        }
        level.playSound(null, pos, SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.BLOCKS, 0.3F, 1.4F);
        return InteractionResult.SUCCESS;
    }

    // A group's labels in the block's order, e.g. "A B C".
    public static String labels(ServerLevel level, GridEndpointBlockEntity endpoint, int group) {
        StringJoiner joiner = new StringJoiner(" ");
        List<Insulator> insulators = endpoint.insulators();
        for (int i = 0; i < insulators.size(); i++) {
            if (insulators.get(i).group() != group) {
                continue;
            }
            GridNode node = GridManager.node(level, new GridData.Anchor(endpoint.getBlockPos(), i));
            joiner.add(node == null ? "?" : node.phase().name());
        }
        return joiner.toString();
    }

    public static String format(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    static void tell(Player player, Component message) {
        player.sendOverlayMessage(message);
    }
}
