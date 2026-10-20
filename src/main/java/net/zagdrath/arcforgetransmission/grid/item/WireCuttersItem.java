/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.item;

import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.zagdrath.arcforgetransmission.grid.block.GridInteractions;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.grid.block.GridEndpointBlockEntity;
import net.zagdrath.arcforgetransmission.grid.world.GridData;
import net.zagdrath.arcforgetransmission.grid.world.GridManager;

// Wire Cutters: cut the wire looked at (the one whose curve passes nearest the crosshair, within reach), its coil
// dropping where it was cut; used on an insulator with no wire in front of it, cut every wire on that insulator. Each
// wire cut costs a point of durability.
public class WireCuttersItem extends Item {
    // How near the crosshair a wire must pass, in blocks, and how far away it can be cut.
    private static final double AIM = 0.25, REACH = 6.0;

    public WireCuttersItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        if (!(context.getLevel() instanceof ServerLevel level)) {
            return InteractionResult.SUCCESS;
        }
        // A wire in front of the block clicked comes first.
        double blockDistance = player.getEyePosition().distanceTo(context.getClickLocation());
        if (cutLookedAt(level, player, context.getHand(), blockDistance + AIM)) {
            return InteractionResult.SUCCESS;
        }
        GridEndpointBlockEntity endpoint = WireCoilItem.endpointAt(level, context.getClickedPos());
        if (endpoint == null) {
            return InteractionResult.PASS;
        }
        if (!GridInteractions.mayChange(player, level, endpoint.getBlockPos())) {
            return InteractionResult.FAIL;
        }
        // A Cable Termination: its tray cables come up whole, never while energised.
        if (endpoint instanceof net.zagdrath.arcforgetransmission.factory.CableTerminationBlockEntity) {
            var cables = net.zagdrath.arcforgetransmission.factory.CableData.of(level).atEnd(endpoint.getBlockPos());
            if (!cables.isEmpty()) {
                int cut = 0;
                for (var cable : cables) {
                    if (net.zagdrath.arcforgetransmission.factory.CableNetwork.energized(level, cable)) {
                        player.sendOverlayMessage(Component.translatable("message." + ArcforgeTransmission.MODID + ".cable.energized"));
                        return InteractionResult.FAIL;
                    }
                }
                for (var cable : cables) {
                    net.zagdrath.arcforgetransmission.factory.CableNetwork.remove(level, cable, player.hasInfiniteMaterials() ? null : context.getClickLocation());
                    cut++;
                }
                cutDone(level, player, context.getHand(), context.getClickLocation(), cut);
                return InteractionResult.SUCCESS;
            }
        }
        int index = endpoint.nearest(context.getClickLocation());
        List<Long> wires = index < 0 ? List.of() : GridManager.wiresAt(level, new GridData.Anchor(endpoint.getBlockPos(), index));
        if (wires.isEmpty()) {
            player.sendOverlayMessage(Component.translatable("message." + ArcforgeTransmission.MODID + ".wire.nothing_to_cut"));
            return InteractionResult.FAIL;
        }
        int cut = 0;
        for (long wire : wires) {
            if (GridManager.cut(level, wire, context.getClickLocation(), !player.hasInfiniteMaterials())) {
                cut++;
            }
        }
        cutDone(level, player, context.getHand(), context.getClickLocation(), cut);
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level instanceof ServerLevel server && !cutLookedAt(server, player, hand, REACH)) {
            player.sendOverlayMessage(Component.translatable("message." + ArcforgeTransmission.MODID + ".wire.nothing_to_cut"));
        }
        return InteractionResult.SUCCESS;
    }

    // Cuts the wire the player looks at within `reach`; false if there's none.
    private static boolean cutLookedAt(ServerLevel level, Player player, InteractionHand hand, double reach) {
        Vec3 eye = player.getEyePosition();
        GridManager.WireHit hit = GridManager.wireAlong(level, eye, player.getLookAngle(), Math.min(reach, REACH), AIM);
        if (hit == null || !GridManager.mayCut(level, player, hit.edge()) || !GridManager.cut(level, hit.edge(), hit.at(), !player.hasInfiniteMaterials())) {
            return false;
        }
        cutDone(level, player, hand, hit.at(), 1);
        return true;
    }

    private static void cutDone(ServerLevel level, Player player, InteractionHand hand, Vec3 at, int count) {
        level.playSound(null, at.x, at.y, at.z, SoundEvents.SHEEP_SHEAR, SoundSource.PLAYERS, 1.0F, 1.2F);
        player.getItemInHand(hand).hurtAndBreak(count, player, hand);
        String m = ArcforgeTransmission.MODID;
        player.sendOverlayMessage(count == 1 ? Component.translatable("message." + m + ".wire.cut")
                : Component.translatable("message." + m + ".wire.cut_many", count));
    }
}
