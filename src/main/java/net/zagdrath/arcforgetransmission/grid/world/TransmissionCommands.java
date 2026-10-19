/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.world;

import java.util.List;
import java.util.stream.Stream;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;

// /arcforgetransmission config reset: puts arcforgetransmission-common.toml back to its defaults from in game or the server
// console, for admins (permission level 3) who'd rather not edit the file, like Arcforge's /arcforge config reset.
//  - reset all            lists the settings that differ from their defaults (nothing changes)
//  - reset all confirm    resets every one of them
//  - reset <path>         resets one setting ("wires.ev.maxSpan"), or every setting in a section ("wires.ev"), at once
// The file is saved straight away and the new values apply at once (see ConfigReset).
@EventBusSubscriber(modid = ArcforgeTransmission.MODID)
public final class TransmissionCommands {
    // How many changed settings a reply names before "and N more".
    private static final int LISTED = 10;

    private TransmissionCommands() {}

    @SubscribeEvent
    static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(ArcforgeTransmission.MODID)
                .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .then(Commands.literal("config")
                        .then(Commands.literal("reset")
                                .then(Commands.literal("all")
                                        .executes(TransmissionCommands::preview)
                                        .then(Commands.literal("confirm").executes(context -> reset(context, ""))))
                                .then(Commands.argument("path", StringArgumentType.string())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                Stream.concat(ConfigReset.sections().stream(), ConfigReset.settings().keySet().stream()), builder))
                                        .executes(context -> reset(context, StringArgumentType.getString(context, "path")))))));
    }

    private static int preview(CommandContext<CommandSourceStack> context) {
        List<String> changed = ConfigReset.changed("");
        CommandSourceStack source = context.getSource();
        String m = ArcforgeTransmission.MODID;
        if (changed.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("command." + m + ".config.all_default"), false);
            return 0;
        }
        String command = "/" + m + " config reset all confirm";
        MutableComponent confirm = Component.literal(command).withStyle(style -> style.withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent.SuggestCommand(command)));
        source.sendSuccess(() -> Component.translatable("command." + m + ".config.preview", changed.size(), list(changed), confirm), false);
        return changed.size();
    }

    private static int reset(CommandContext<CommandSourceStack> context, String path) {
        CommandSourceStack source = context.getSource();
        String m = ArcforgeTransmission.MODID;
        if (!ConfigReset.exists(path)) {
            source.sendFailure(Component.translatable("command." + m + ".config.unknown", path));
            return 0;
        }
        List<String> changed = ConfigReset.reset(path);
        String scope = path.isEmpty() ? "all" : path;
        if (changed.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("command." + m + ".config.already_default", scope), false);
        } else {
            // Told to other admins too, as /gamerule is.
            source.sendSuccess(() -> Component.translatable("command." + m + ".config.reset", changed.size(), list(changed)), true);
        }
        return changed.size();
    }

    // The first few paths, comma separated, then how many more.
    private static Component list(List<String> paths) {
        String shown = String.join(", ", paths.subList(0, Math.min(LISTED, paths.size())));
        return paths.size() > LISTED
                ? Component.translatable("command." + ArcforgeTransmission.MODID + ".config.and_more", shown, paths.size() - LISTED)
                : Component.literal(shown);
    }
}
