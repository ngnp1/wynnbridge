package com.wynnbridge;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side commands. These are handled locally by the client command
 * dispatcher and never reach Wynncraft.
 */
public final class BridgeCommands {
	/** Longest mail title the mod accepts, matching the bot's own limit. */
	private static final int MAX_TITLE_LENGTH = 30;

	private BridgeCommands() {
	}

	public static void register(BridgeSocket socket, MailStore mailStore) {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(ClientCommandManager.literal("mail")
				.then(ClientCommandManager.literal("send")
						.then(ClientCommandManager.argument("player", StringArgumentType.word())
								.then(ClientCommandManager.argument("title", StringArgumentType.string())
										.then(ClientCommandManager.argument("text", StringArgumentType.greedyString())
												.executes(context -> {
													String player = StringArgumentType.getString(context, "player");
													String title = StringArgumentType.getString(context, "title").trim();
													String text = StringArgumentType.getString(context, "text");
													if (title.length() > MAX_TITLE_LENGTH) {
														ChatBridgeHooks.showSystem("Titles are limited to " + MAX_TITLE_LENGTH + " characters");
														return 0;
													}
													socket.sendMail(player, title, text);
													return 1;
												})))))
				.then(ClientCommandManager.literal("inbox")
							.executes(context -> {
								// Pull anything that arrived since the last poll before listing.
								socket.mailFetch();
								List<MailStore.Mail> unread = unreadMail(mailStore);
								ChatBridgeHooks.showMailCount(unread.size());
								for (int i = 0; i < unread.size(); i++) {
									MailStore.Mail mail = unread.get(i);
									ChatBridgeHooks.showMailEntry(i + 1, mail.title, mail.sender);
								}
								return unread.isEmpty() ? 0 : 1;
							}))
					.then(ClientCommandManager.literal("read")
							.then(ClientCommandManager.argument("number", IntegerArgumentType.integer(1))
									.executes(context -> {
										int number = IntegerArgumentType.getInteger(context, "number");
										List<MailStore.Mail> unread = unreadMail(mailStore);
										if (number > unread.size()) {
											ChatBridgeHooks.showMailNotice("no mail #" + number + " in the inbox");
											return 0;
										}
										MailStore.Mail mail = unread.get(number - 1);
										ChatBridgeHooks.showMail(mail.sender, mail.content);
										mailStore.markRead(mail.id);
										socket.mailRead(mail.id);
										return 1;
									}))));

			dispatcher.register(ClientCommandManager.literal("bridge")
					.then(ClientCommandManager.literal("join")
							.then(ClientCommandManager.argument("link", StringArgumentType.greedyString())
									.executes(context -> {
										String link = StringArgumentType.getString(context, "link");
										FabricClientCommandSource source = context.getSource();
										if (socket.applyLink(link)) {
											feedback(source, "bridge server set to " + socket.url());
											feedback(source, "connecting - check /bridge status");
											return 1;
										}
										feedback(source, "invalid link, expected wss://host/ws#token");
										return 0;
									})))
					.then(ClientCommandManager.literal("status")
							.executes(context -> {
								FabricClientCommandSource source = context.getSource();
								BridgeConfig config = WynnBridgeClient.config();
								feedback(source, "server: " + socket.url());
								feedback(source, "connected: " + (socket.isAuthenticated() ? "yes" : "no"));
								feedback(source, "unread mail: " + mailStore.unreadCount());
								feedback(source, "forwarding: " + (config.forwardGuildChat ? "on" : "off")
										+ ", debug: " + (config.debugChat ? "on" : "off"));
								feedback(source, "pattern: " + config.guildPattern);
								feedback(source, "chat font: " + ChatBridgeHooks.chatFontName());
								return 1;
							}))
					.then(ClientCommandManager.literal("reload")
							.executes(context -> {
								FabricClientCommandSource source = context.getSource();
								BridgeConfig config = WynnBridgeClient.config();
								config.reload();
								feedback(source, "reloaded config/wynnbridge.json");
								feedback(source, "server: " + config.url);
								feedback(source, "forwarding: " + (config.forwardGuildChat ? "on" : "off")
										+ ", debug: " + (config.debugChat ? "on" : "off"));
								feedback(source, "pattern: " + config.guildPattern);
								feedback(source, "run /bridge reconnect if the server changed");
								return 1;
							}))
					.then(ClientCommandManager.literal("debug")
							.then(ClientCommandManager.literal("on")
									.executes(context -> {
										BridgeConfig config = WynnBridgeClient.config();
										config.debugChat = true;
										config.save();
										feedback(context.getSource(), "chat logging on - incoming lines appear as 'chat: <line>'");
										return 1;
									}))
							.then(ClientCommandManager.literal("off")
									.executes(context -> {
										BridgeConfig config = WynnBridgeClient.config();
										config.debugChat = false;
										config.save();
										feedback(context.getSource(), "chat logging off");
										return 1;
									})))
					.then(ClientCommandManager.literal("pattern")
							.then(ClientCommandManager.argument("regex", StringArgumentType.greedyString())
									.executes(context -> {
										BridgeConfig config = WynnBridgeClient.config();
										String error = config.setPattern(StringArgumentType.getString(context, "regex"));
										if (error != null) {
											feedback(context.getSource(), "invalid regex: " + error);
											return 0;
										}
										feedback(context.getSource(), "pattern set: " + config.guildPattern);
										return 1;
									})))
					.then(ClientCommandManager.literal("reconnect")
							.executes(context -> {
								socket.reconnect();
								feedback(context.getSource(), "reconnecting to the bridge");
								return 1;
							}))
					.then(ClientCommandManager.literal("update")
							.executes(context -> {
								feedback(context.getSource(), "current version: " + BridgeUpdater.currentVersion());
								BridgeUpdater.checkAndInstall();
								return 1;
							})));
		});
	}

	private static List<MailStore.Mail> unreadMail(MailStore mailStore) {
		List<MailStore.Mail> unread = new ArrayList<>();
		for (MailStore.Mail mail : mailStore.all()) {
			if (!mail.read) {
				unread.add(mail);
			}
		}
		return unread;
	}

	private static void feedback(FabricClientCommandSource source, String message) {
		source.sendFeedback(Text.literal(message));
	}
}
