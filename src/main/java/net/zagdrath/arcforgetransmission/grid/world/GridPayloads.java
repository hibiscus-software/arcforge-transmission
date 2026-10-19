/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.world;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.block.GridEndpointBlockEntity;
import net.zagdrath.arcforgetransmission.grid.client.GridClientPayloads;

// What the server tells clients beyond block updates:
//  - a block's HUD (its labels, currents, voltages and own state): sent to the players with its chunk loaded, without the
//    wires or a block update (so the chunk isn't redrawn each time a current changes);
//  - the grid settings a client shows (wire ratings and spans, the fuse factor, the tap step, the sectionalizer's count):
//    on joining, and to everyone when the config is reloaded, so a client shows the server's numbers, not its own.
public final class GridPayloads {
    private GridPayloads() {}

    public record Hud(BlockPos pos, CompoundTag tag) implements CustomPacketPayload {
        public static final Type<Hud> TYPE = new Type<>(Identifier.fromNamespaceAndPath(ArcforgeTransmission.MODID, "hud"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Hud> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, Hud::pos,
                ByteBufCodecs.COMPOUND_TAG, Hud::tag, Hud::new);

        @Override
        public Type<Hud> type() {
            return TYPE;
        }
    }

    public record Settings(GridConfig.Synced synced) implements CustomPacketPayload {
        public static final Type<Settings> TYPE = new Type<>(Identifier.fromNamespaceAndPath(ArcforgeTransmission.MODID, "settings"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Settings> CODEC = StreamCodec.of((buf, settings) -> {
            GridConfig.Synced s = settings.synced();
            for (VoltageTier tier : VoltageTier.values()) {
                buf.writeDouble(s.ratedAmps()[tier.ordinal()]);
                buf.writeVarInt(s.maxSpan()[tier.ordinal()]);
            }
            buf.writeDouble(s.fuseFactor());
            buf.writeDouble(s.tapStep());
            buf.writeVarInt(s.sectionalizerCount());
        }, buf -> {
            int n = VoltageTier.values().length;
            double[] rated = new double[n];
            int[] span = new int[n];
            for (int i = 0; i < n; i++) {
                rated[i] = buf.readDouble();
                span[i] = buf.readVarInt();
            }
            return new Settings(new GridConfig.Synced(rated, span, buf.readDouble(), buf.readDouble(), buf.readVarInt()));
        });

        @Override
        public Type<Settings> type() {
            return TYPE;
        }
    }

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(GridPayloads::registerPayloads);
        modEventBus.addListener(GridPayloads::configReloaded);
        NeoForge.EVENT_BUS.addListener(GridPayloads::loggedIn);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(Hud.TYPE, Hud.CODEC, (payload, context) -> GridClientPayloads.hud(payload))
                .playToClient(Settings.TYPE, Settings.CODEC, (payload, context) -> GridClientPayloads.settings(payload));
    }

    private static void loggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            send(player, new Settings(GridConfig.local()));
        }
    }

    private static void configReloaded(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() != GridConfig.SPEC) {
            return;
        }
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            server.execute(() -> server.getPlayerList().getPlayers().forEach(player -> send(player, new Settings(GridConfig.local()))));
        }
    }

    // Sends a block's HUD to the players with its chunk loaded.
    public static void sendHud(ServerLevel level, GridEndpointBlockEntity endpoint) {
        CompoundTag tag = endpoint.getUpdateTag(level.registryAccess());
        tag.remove("wires");
        Hud hud = new Hud(endpoint.getBlockPos(), tag);
        level.getChunkSource().chunkMap.getPlayers(ChunkPos.containing(endpoint.getBlockPos()), false).forEach(player -> send(player, hud));
    }

    // Only to a client that knows the payload (not a game test's mock player, nor a client without the mod).
    private static void send(ServerPlayer player, CustomPacketPayload payload) {
        if (player.connection != null && player.connection.hasChannel(payload)) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }
}
