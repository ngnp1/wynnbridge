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
			var send = ClientCommandManager.literal("send")
					.then(ClientCommandManager.argument("player", StringArgumentType.word())
							.then(ClientCommandManager.argument("title", StringArgumentType.string())
									.then(ClientCommandManager.argument("text", StringArgumentType.greedyString())
											.executes(context -> sendMail(socket, context)))));

			var inbox = ClientCommandManager.literal("inbox")
					.executes(context -> listInbox(socket, mailStore, null))
					.then(ClientCommandManager.literal("unread")
							.executes(context -> listInbox(socket, mailStore, Boolean.FALSE)))
					.then(ClientCommandManager.literal("read")
							.executes(context -> listInbox(socket, mailStore, Boolean.TRUE)));

			var read = ClientCommandManager.literal("read")
					.then(ClientCommandManager.argument("number", IntegerArgumentType.integer(1))
							.executes(context -> readMail(socket, mailStore,
									IntegerArgumentType.getInteger(context, "number"))));

			var clear = ClientCommandManager.literal("clear")
					.then(ClientCommandManager.argument("target", StringArgumentType.word())
							.executes(context -> clearMail(socket, mailStore,
									StringArgumentType.getString(context, "target"))));

			dispatcher.register(ClientCommandManager.literal("mail")
					.then(send)
					.then(inbox)
					.then(read)
					.then(clear));

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

	/** Reads the send arguments, checks the title and hands the mail to the socket. */
	private static int sendMail(BridgeSocket socket, com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> context) {
		String player = StringArgumentType.getString(context, "player");
		String title = StringArgumentType.getString(context, "title").trim();
		String text = StringArgumentType.getString(context, "text");
		if (title.length() > MAX_TITLE_LENGTH) {
			ChatBridgeHooks.showSystem("Titles are limited to " + MAX_TITLE_LENGTH + " characters");
			return 0;
		}
		socket.sendMail(player, title, text);
		return 1;
	}

	/** Shows the mail at this 1-based inbox position and marks it read. */
	private static int readMail(BridgeSocket socket, MailStore mailStore, int number) {
		List<MailStore.Mail> mails = mailStore.all();
		if (number < 1 || number > mails.size()) {
			ChatBridgeHooks.showMailNotice("No mail #" + number + " in the inbox.");
			return 0;
		}
		MailStore.Mail mail = mails.get(number - 1);
		ChatBridgeHooks.showMail(mail.sender, mail.content);
		mailStore.markRead(mail.id);
		socket.mailRead(mail.id);
		return 1;
	}

	private static int listInbox(BridgeSocket socket, MailStore mailStore, Boolean readFilter) {
		// Pull anything that arrived since the last poll before listing.
		socket.mailFetch();
		List<MailStore.Mail> mails = mailStore.all();
		ChatBridgeHooks.showMailCount(mailStore.unreadCount());

		int shown = 0;
		for (int i = 0; i < mails.size(); i++) {
			MailStore.Mail mail = mails.get(i);
			if (readFilter != null && mail.read != readFilter) {
				continue;
			}
			ChatBridgeHooks.showMailEntry(i + 1, mail.read, mail.title, mail.sender);
			shown++;
		}
		if (shown == 0 && readFilter != null) {
			ChatBridgeHooks.showMailNotice(readFilter ? "No read mail." : "No unread mail.");
		}
		return shown;
	}

	/**
	 * {@code /mail clear all}, {@code /mail clear 3} or {@code /mail clear 2-5}.
	 * Numbers follow the same 1-based order the inbox prints.
	 */
	private static int clearMail(BridgeSocket socket, MailStore mailStore, String target) {
		List<MailStore.Mail> mails = mailStore.all();
		List<Integer> ids = new ArrayList<>();

		if (target.equalsIgnoreCase("all")) {
			for (MailStore.Mail mail : mails) {
				ids.add(mail.id);
			}
		} else {
			int dash = target.indexOf('-');
			int from;
			int to;
			try {
				if (dash > 0) {
					from = Integer.parseInt(target.substring(0, dash).trim());
					to = Integer.parseInt(target.substring(dash + 1).trim());
				} else {
					from = Integer.parseInt(target.trim());
					to = from;
				}
			} catch (NumberFormatException e) {
				ChatBridgeHooks.showMailNotice("Use /mail clear all, /mail clear 3 or /mail clear 2-5.");
				return 0;
			}
			if (from < 1 || to < from) {
				ChatBridgeHooks.showMailNotice("Use /mail clear all, /mail clear 3 or /mail clear 2-5.");
				return 0;
			}
			for (int number = from; number <= to && number <= mails.size(); number++) {
				ids.add(mails.get(number - 1).id);
			}
		}

		if (ids.isEmpty()) {
			ChatBridgeHooks.showMailNotice("Nothing to clear.");
			return 0;
		}

		int removed = mailStore.remove(ids);
		socket.mailDelete(ids);
		ChatBridgeHooks.showMailNotice("Cleared " + removed + (removed == 1 ? " mail." : " mails."));
		ChatBridgeHooks.showMailCount(mailStore.unreadCount());
		return removed;
	}

	private static void feedback(FabricClientCommandSource source, String message) {
		source.sendFeedback(Text.literal(message));
	}
}
