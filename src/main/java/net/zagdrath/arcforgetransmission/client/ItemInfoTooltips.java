/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.client;

import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;

// A short description of what each of the addon's items does, under its name, as Arcforge's items have: "Hold [Shift]
// for info", and the description while Shift is held. Descriptions live in the lang file as
// tooltip.arcforgetransmission.info.<item>; tiered items (pin insulators, wire coils) share one without
// the tier, e.g. tooltip.arcforgetransmission.info.pin_insulator.
@EventBusSubscriber(modid = ArcforgeTransmission.MODID, value = Dist.CLIENT)
public final class ItemInfoTooltips {
    private static final String PREFIX = "tooltip." + ArcforgeTransmission.MODID + ".info.";
    private static final String[] TIERS = { "lv_", "mv_", "hv_", "ehv_" };
    // Descriptions wrap at this width (the vanilla tooltip font is 9 px tall; ~40 characters a line).
    private static final int WRAP_WIDTH = 200;

    private ItemInfoTooltips() {}

    @SubscribeEvent
    static void onTooltip(ItemTooltipEvent event) {
        Identifier id = BuiltInRegistries.ITEM.getKey(event.getItemStack().getItem());
        if (!ArcforgeTransmission.MODID.equals(id.getNamespace())) {
            return;
        }
        String key = descriptionKey(id.getPath());
        if (key == null) {
            return;
        }
        List<Component> tooltip = event.getToolTip();
        // Right under the name, before the item's own lines.
        int at = Math.min(1, tooltip.size());
        if (!Minecraft.getInstance().hasShiftDown()) {
            tooltip.add(at, Component.translatable("tooltip." + ArcforgeTransmission.MODID + ".hold_shift",
                    Component.translatable("tooltip." + ArcforgeTransmission.MODID + ".shift").withStyle(ChatFormatting.GRAY))
                    .withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        List<FormattedText> lines = Minecraft.getInstance().font.getSplitter().splitLines(Component.translatable(key), WRAP_WIDTH, Style.EMPTY);
        for (int i = 0; i < lines.size(); i++) {
            tooltip.add(at + i, Component.literal(lines.get(i).getString()).withStyle(ChatFormatting.GRAY));
        }
    }

    // The item's description key, or the shared one for its tier, or null if it has none.
    public static @Nullable String descriptionKey(String path) {
        Language language = Language.getInstance();
        if (language.has(PREFIX + path)) {
            return PREFIX + path;
        }
        for (String tier : TIERS) {
            if (path.startsWith(tier) && language.has(PREFIX + path.substring(tier.length()))) {
                return PREFIX + path.substring(tier.length());
            }
        }
        return null;
    }
}
