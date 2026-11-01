package com.wynnbridge;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

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
								List<MailStore.Mail> mails = mailStore.all();
								if (mails.isEmpty()) {
									feedback(context.getSource(), "no mail");
									return 0;
								}
								int from = Math.max(0, mails.size() - 10);
								feedback(context.getSource(), (mails.size() - from) + " of " + mails.size() + " mail(s)");
								for (int i = from; i < mails.size(); i++) {
									MailStore.Mail mail = mails.get(i);
									feedback(context.getSource(),
											"#" + mail.id + (mail.read ? " [read] " : " [unread] ")
													+ mail.sender + ": " + mail.content);
								}
								return 1;
							}))
					.then(ClientCommandManager.literal("read")
							.then(ClientCommandManager.argument("id", IntegerArgumentType.integer(1))
									.executes(context -> {
										int id = IntegerArgumentType.getInteger(context, "id");
										if (mailStore.markRead(id)) {
											socket.mailRead(id);
											feedback(context.getSource(), "marked mail #" + id + " as read");
											return 1;
										}
										feedback(context.getSource(), "no mail with id " + id);
										return 0;
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
									}))))
					.then(ClientCommandManager.literal("status")
							.executes(context -> {
								FabricClientCommandSource source = context.getSource();
								feedback(source, "server: " + socket.url());
								feedback(source, "connected: " + (socket.isAuthenticated() ? "yes" : "no"));
								feedback(source, "unread mail: " + mailStore.unreadCount());
								return 1;
							}))
					.then(ClientCommandManager.literal("reconnect")
							.executes(context -> {
								socket.reconnect();
								feedback(context.getSource(), "reconnecting to the bridge");
								return 1;
							})));
		});
	}

	private static void feedback(FabricClientCommandSource source, String message) {
		source.sendFeedback(Text.literal(message));
	}
}
