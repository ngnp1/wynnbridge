package com.wynnbridge;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Incoming chat handling.
 *
 * Guild chat is read client-side from {@link ClientReceiveMessageEvents#GAME}
 * and forwarded to the bridge. Discord and mail messages are injected straight
 * into the chat HUD, so they are never sent to the server.
 */
public final class ChatBridgeHooks {
	private ChatBridgeHooks() {
	}

	public static void register(BridgeSocket socket, BridgeConfig config) {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay) {
				return;
			}
			String raw = message.getString();
			if (raw == null || raw.isEmpty()) {
				return;
			}
			if (config.debugChat) {
				WynnBridgeClient.LOGGER.info("chat: {}", raw);
			}
			if (config.forwardGuildChat && config.pattern().matcher(raw).find()) {
				socket.sendChat(raw);
			}
		});
	}

	public static void showDiscordChat(String author, String content) {
		post(Text.literal("[Discord] ").formatted(Formatting.AQUA)
				.append(Text.literal(author).formatted(Formatting.WHITE))
				.append(Text.literal(": " + content).formatted(Formatting.GRAY)));
	}

	public static void showMail(String sender, String content) {
		post(Text.literal("[Mail] ").formatted(Formatting.GOLD)
				.append(Text.literal(sender).formatted(Formatting.WHITE))
				.append(Text.literal(": " + content)));
	}

	public static void showSystem(String message) {
		post(Text.literal("[Bridge] ").formatted(Formatting.DARK_AQUA)
				.append(Text.literal(message).formatted(Formatting.GRAY)));
	}

	private static void post(MutableText text) {
		MinecraftClient client = MinecraftClient.getInstance();
		client.execute(() -> {
			if (client.inGameHud != null) {
				client.inGameHud.getChatHud().addMessage(text);
			}
		});
	}
}
