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
	private BridgeCommands() {
	}

	public static void register(BridgeSocket socket, MailStore mailStore) {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(ClientCommandManager.literal("mail")
					.then(ClientCommandManager.literal("send")
							.then(ClientCommandManager.argument("player", StringArgumentType.word())
									.then(ClientCommandManager.argument("text", StringArgumentType.greedyString())
											.executes(context -> {
												String player = StringArgumentType.getString(context, "player");
												String text = StringArgumentType.getString(context, "text");
												socket.sendMail(player, text);
												feedback(context.getSource(), "sending mail to " + player);
												return 1;
											}))))
					.then(ClientCommandManager.literal("inbox")
							.executes(context -> {
								// Pull anything that arrived since the last poll before listing.
								socket.mailFetch();
								List<MailStore.Mail> unread = new ArrayList<>();
								for (MailStore.Mail mail : mailStore.all()) {
									if (!mail.read) {
										unread.add(mail);
									}
								}
								if (unread.isEmpty()) {
									ChatBridgeHooks.showMailNotice("You have no unread mail");
									return 0;
								}
								ChatBridgeHooks.showMailNotice("You have " + unread.size()
										+ (unread.size() == 1 ? " unread mail" : " unread mails"));
								for (MailStore.Mail mail : unread) {
									ChatBridgeHooks.showMailEntry("#" + mail.id + " from " + mail.sender);
								}
								return 1;
							}))
					.then(ClientCommandManager.literal("read")
							.then(ClientCommandManager.argument("id", IntegerArgumentType.integer(1))
									.executes(context -> {
										int id = IntegerArgumentType.getInteger(context, "id");
										MailStore.Mail mail = mailStore.find(id);
										if (mail == null) {
											ChatBridgeHooks.showMailNotice("no mail with id " + id);
											return 0;
										}
										ChatBridgeHooks.showMail(mail.sender, mail.content);
										mailStore.remove(id);
										socket.mailRead(id);
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

	private static void feedback(FabricClientCommandSource source, String message) {
		source.sendFeedback(Text.literal(message));
	}
}
