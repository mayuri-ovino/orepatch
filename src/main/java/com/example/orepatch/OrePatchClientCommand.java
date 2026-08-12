package com.example.orepatch;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.network.chat.Component;

@Environment(EnvType.CLIENT)
final class OrePatchClientCommand {

    private OrePatchClientCommand() {}

    static void register(OrePatchConfig config) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("orepatch")
                        .executes(ctx -> {
                            sendFeedback(ctx, "Ore Patch is " + (config.isEnabled() ? "ON" : "OFF")
                                    + " (scanning " + config.getChunksPerTick() + " chunk(s)/tick).");
                            return 1;
                        })
                        .then(ClientCommands.literal("status").executes(ctx -> {
                            sendFeedback(ctx, "Ore Patch is " + (config.isEnabled() ? "ON" : "OFF")
                                    + " (scanning " + config.getChunksPerTick() + " chunk(s)/tick).");
                            return 1;
                        }))
                        .then(ClientCommands.literal("on").executes(ctx -> {
                            config.setEnabled(true);
                            sendFeedback(ctx, "Ore Patch is now ON.");
                            return 1;
                        }))
                        .then(ClientCommands.literal("off").executes(ctx -> {
                            config.setEnabled(false);
                            sendFeedback(ctx, "Ore Patch is now OFF.");
                            return 1;
                        }))
                        .then(ClientCommands.literal("toggle").executes(ctx -> {
                            boolean n = !config.isEnabled();
                            config.setEnabled(n);
                            sendFeedback(ctx, "Ore Patch is now " + (n ? "ON" : "OFF") + ".");
                            return 1;
                        }))
                        .then(ClientCommands.literal("rate")
                                .then(ClientCommands.argument("chunksPerTick", IntegerArgumentType.integer(1, 64))
                                        .executes(ctx -> {
                                            int r = IntegerArgumentType.getInteger(ctx, "chunksPerTick");
                                            config.setChunksPerTick(r);
                                            sendFeedback(ctx, "Ore Patch will scan " + r + " chunk(s)/tick.");
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("set")
                                .then(ClientCommands.argument("enabled", BoolArgumentType.bool())
                                        .executes(ctx -> {
                                            boolean e = BoolArgumentType.getBool(ctx, "enabled");
                                            config.setEnabled(e);
                                            sendFeedback(ctx, "Ore Patch is now " + (e ? "ON" : "OFF") + ".");
                                            return 1;
                                        })))
                ));
    }

    private static void sendFeedback(com.mojang.brigadier.context.CommandContext<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> ctx, String msg) {
        ctx.getSource().sendFeedback(Component.literal(msg));
    }
}
