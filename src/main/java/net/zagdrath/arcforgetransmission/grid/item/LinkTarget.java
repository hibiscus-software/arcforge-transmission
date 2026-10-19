/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

// The insulator a wire coil's loose end is tied to, waiting for its other end: the dimension, the block and the
// insulator's place in that block's list.
public record LinkTarget(String dimension, BlockPos pos, int index) {
    public static final Codec<LinkTarget> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("dimension").forGetter(LinkTarget::dimension),
            BlockPos.CODEC.fieldOf("pos").forGetter(LinkTarget::pos),
            Codec.INT.fieldOf("index").forGetter(LinkTarget::index)).apply(i, LinkTarget::new));
    public static final StreamCodec<ByteBuf, LinkTarget> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, LinkTarget::dimension,
            BlockPos.STREAM_CODEC, LinkTarget::pos,
            ByteBufCodecs.VAR_INT, LinkTarget::index,
            LinkTarget::new);
}
