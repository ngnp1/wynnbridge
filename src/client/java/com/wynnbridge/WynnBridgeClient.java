package com.wynnbridge;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WynnBridgeClient implements ClientModInitializer {
	public static final String MOD_ID = "wynnbridge";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static BridgeConfig config;
	private static MailStore mailStore;
	private static BridgeSocket socket;

	@Override
	public void onInitializeClient() {
		config = BridgeConfig.load();
		mailStore = new MailStore();
		PlayerTitles.load();
		socket = new BridgeSocket(config, mailStore);

		ChatBridgeHooks.register(socket, config);
		BridgeCommands.register(socket, mailStore);

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
				socket.connect(client.getSession().getUsername()));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> socket.disconnect());
		ClientTickEvents.END_CLIENT_TICK.register(client -> socket.tick());

		LOGGER.info("WynnBridge ready (server: {})", config.url);
	}

	public static BridgeConfig config() {
		return config;
	}

	public static MailStore mailStore() {
		return mailStore;
	}

	public static BridgeSocket socket() {
		return socket;
	}

	public static MinecraftClient client() {
		return MinecraftClient.getInstance();
	}
}
