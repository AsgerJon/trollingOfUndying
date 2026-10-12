package io.github.asgerjon.trollingofundying;

import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.pack.PackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import org.jspecify.annotations.Nullable;

public class TrollingOfUndyingClient implements ClientModInitializer {
    public static final String MOD_ID = "trollingofundying";

    @Override
    public void onInitializeClient() {
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(TotemAnimationLoader.ID, new TotemAnimationLoader());
        // The built-in animation, El Risitas laughing, from resourcepacks/risitas in the jar. It shows up in the
        // Resource Packs list, switched off, so the vanilla pop stays until the player picks a pack.
        ResourceLoader.registerBuiltinPack(
                Identifier.fromNamespaceAndPath(MOD_ID, "risitas"),
                FabricLoader.getInstance().getModContainer(MOD_ID).orElseThrow(),
                Component.literal("Trolling of Undying: Risitas"),
                PackActivationType.NORMAL
        );
        SkillIssueCounter.load();
        TotemAnimationLoader.loadRotation();
        HudElementRegistry.attachElementAfter(
                VanillaHudElements.HOTBAR, Identifier.fromNamespaceAndPath(MOD_ID, "skill_issue_counter"), SkillIssueCounter::render
        );
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "totem_animation"), TotemAnimationPlayer::render);
        ClientTickEvents.END_CLIENT_TICK.register(TotemAnimationPlayer::tick);
        ClientTickEvents.END_CLIENT_TICK.register(SkillIssueCounter::tick);
        ClientTickEvents.END_CLIENT_TICK.register(SkillIssueAlert::tick);
        HudElementRegistry.attachElementAfter(
                VanillaHudElements.TITLE_AND_SUBTITLE, Identifier.fromNamespaceAndPath(MOD_ID, "skill_issue_alert"), SkillIssueAlert::render
        );
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> overlay || !SkillIssueCounter.isOwnPostmortal(message));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(
                ClientCommands.literal(MOD_ID)
                        .then(ClientCommands.literal("play")
                                .executes(command -> play(command.getSource(), TotemAnimationLoader.take()))
                                .then(ClientCommands.argument("name", StringArgumentType.word())
                                        .suggests((suggestionContext, builder) -> SharedSuggestionProvider.suggest(
                                                TotemAnimationLoader.all().keySet(), builder
                                        ))
                                        .executes(command -> play(
                                                command.getSource(),
                                                TotemAnimationLoader.get(StringArgumentType.getString(command, "name"))
                                        ))))
                        .then(ClientCommands.literal("stop").executes(command -> {
                            TotemAnimationPlayer.stop();
                            return 1;
                        }))
                        .then(ClientCommands.literal("info").executes(command -> {
                            command.getSource().sendFeedback(Component.literal(TotemAnimationLoader.status()));
                            return 1;
                        }))
        ));
    }

    private static int play(FabricClientCommandSource source, @Nullable TotemAnimation animation) {
        if (animation == null) {
            source.sendError(Component.literal(TotemAnimationLoader.status()));
            return 0;
        }
        TotemAnimationPlayer.start(animation);
        return 1;
    }
}
