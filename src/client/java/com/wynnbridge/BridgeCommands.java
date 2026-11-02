package com.wynnbridge;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Client-side commands. These are handled locally by the client command
 * dispatcher and never reach Wynncraft.
 */
public final class BridgeCommands {
	/** Longest mail title the mod accepts, matching the bot's own limit. */
	private static final int MAX_TITLE_LENGTH = 60;

	/** Mails shown per inbox page. */
	private static final int MAIL_PAGE_SIZE = 10;

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
					.executes(context -> listInbox(socket, mailStore, null, 1))
					.then(inboxPage(socket, mailStore, null))
					.then(ClientCommandManager.literal("unread")
							.executes(context -> listInbox(socket, mailStore, Boolean.FALSE, 1))
							.then(inboxPage(socket, mailStore, Boolean.FALSE)))
					.then(ClientCommandManager.literal("read")
							.executes(context -> listInbox(socket, mailStore, Boolean.TRUE, 1))
							.then(inboxPage(socket, mailStore, Boolean.TRUE)));

			var read = ClientCommandManager.literal("read")
					.then(ClientCommandManager.argument("number", IntegerArgumentType.integer(1))
							.executes(context -> readMail(socket, mailStore,
									IntegerArgumentType.getInteger(context, "number"))));

			var clear = ClientCommandManager.literal("clear")
					.then(ClientCommandManager.argument("target", StringArgumentType.word())
							.executes(context -> clearMail(socket, mailStore,
									StringArgumentType.getString(context, "target"), null))
							.then(ClientCommandManager.literal("from")
									.then(ClientCommandManager.argument("player", StringArgumentType.word())
											.executes(context -> clearMail(socket, mailStore,
													StringArgumentType.getString(context, "target"),
													StringArgumentType.getString(context, "player"))))));

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
							}))
					.then(ClientCommandManager.literal("help")
							.executes(context -> {
								showHelp(context.getSource());
								return 1;
							})));
		});
	}

	/** Reads the send arguments, checks the title and hands the mail to the socket. */
	private static int sendMail(BridgeSocket socket, CommandContext<FabricClientCommandSource> context) {
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

	private static RequiredArgumentBuilder<FabricClientCommandSource, Integer> inboxPage(
			BridgeSocket socket, MailStore mailStore, Boolean readFilter) {
		return ClientCommandManager.argument("page", IntegerArgumentType.integer(1))
				.suggests((context, builder) -> {
					int pages = pageCount(mailStore, readFilter);
					for (int page = 1; page <= pages; page++) {
						builder.suggest(page);
					}
					return builder.buildFuture();
				})
				.executes(context -> listInbox(socket, mailStore, readFilter,
						IntegerArgumentType.getInteger(context, "page")));
	}

	/**
	 * Mails in the order the inbox prints them: unread first, then read, each by
	 * arrival. Numbers in every command index into this list.
	 */
	private static List<MailStore.Mail> orderedMail(MailStore mailStore) {
		List<MailStore.Mail> mails = new ArrayList<>(mailStore.all());
		mails.sort(Comparator.comparing((MailStore.Mail mail) -> mail.read));
		return mails;
	}

	private static List<MailStore.Mail> filteredMail(MailStore mailStore, Boolean readFilter) {
		List<MailStore.Mail> listed = new ArrayList<>();
		for (MailStore.Mail mail : orderedMail(mailStore)) {
			if (readFilter == null || mail.read == readFilter) {
				listed.add(mail);
			}
		}
		return listed;
	}

	private static int pageCount(MailStore mailStore, Boolean readFilter) {
		int count = filteredMail(mailStore, readFilter).size();
		return Math.max(1, (count + MAIL_PAGE_SIZE - 1) / MAIL_PAGE_SIZE);
	}

	private static int listInbox(BridgeSocket socket, MailStore mailStore, Boolean readFilter, int page) {
		// Pull anything that arrived since the last poll before listing.
		socket.mailFetch();

		List<MailStore.Mail> mails = orderedMail(mailStore);
		List<MailStore.Mail> listed = new ArrayList<>();
		for (MailStore.Mail mail : mails) {
			if (readFilter == null || mail.read == readFilter) {
				listed.add(mail);
			}
		}

		// Nothing to show: say so on its own, without the Inbox head above it.
		if (listed.isEmpty()) {
			ChatBridgeHooks.showMailNotice(readFilter == null ? "No mail."
					: (readFilter ? "No read mail." : "No unread mail."));
			return 0;
		}

		int pages = Math.max(1, (listed.size() + MAIL_PAGE_SIZE - 1) / MAIL_PAGE_SIZE);
		if (page > pages) {
			ChatBridgeHooks.showMailNotice("No page " + page + ".");
			return 0;
		}

		ChatBridgeHooks.showMailNotice(
				(readFilter == null ? "Inbox" : (readFilter ? "Inbox <read>" : "Inbox <unread>"))
						+ " - Page " + page);
		ChatBridgeHooks.showMailBlank();

		int from = (page - 1) * MAIL_PAGE_SIZE;
		int to = Math.min(listed.size(), from + MAIL_PAGE_SIZE);
		Boolean previousRead = null;
		for (int i = from; i < to; i++) {
			MailStore.Mail mail = listed.get(i);
			// Keep the unread and read blocks apart when both land on one page.
			if (previousRead != null && !previousRead && mail.read) {
				ChatBridgeHooks.showMailBlank();
			}
			// Number the line by its place in the whole inbox, so /mail read and
			// /mail clear agree with what the filtered views print.
			int number = mails.indexOf(mail) + 1;
			ChatBridgeHooks.showMailEntry(number, mail.read, mail.title, mail.sender);
			previousRead = mail.read;
		}
		return to - from;
	}

	/** Shows the mail at this 1-based inbox position and marks it read. */
	private static int readMail(BridgeSocket socket, MailStore mailStore, int number) {
		List<MailStore.Mail> mails = orderedMail(mailStore);
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

	/**
	 * {@code /mail clear all}, {@code /mail clear 3} or {@code /mail clear 2-5},
	 * optionally narrowed to one sender with {@code from <player>}. Numbers follow
	 * the order the inbox prints, or the sender's own mails when filtering.
	 */
	private static int clearMail(BridgeSocket socket, MailStore mailStore, String target, String senderFilter) {
		List<MailStore.Mail> pool;
		if (senderFilter == null) {
			pool = orderedMail(mailStore);
		} else {
			pool = new ArrayList<>();
			for (MailStore.Mail mail : orderedMail(mailStore)) {
				if (mail.sender.equalsIgnoreCase(senderFilter)) {
					pool.add(mail);
				}
			}
		}

		String usage = "Use /mail clear all, /mail clear 3, /mail clear 2-5 or /mail clear all from <player>.";
		List<Integer> ids = new ArrayList<>();

		if (target.equalsIgnoreCase("all")) {
			for (MailStore.Mail mail : pool) {
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
				ChatBridgeHooks.showMailNotice(usage);
				return 0;
			}
			if (from < 1 || to < from) {
				ChatBridgeHooks.showMailNotice(usage);
				return 0;
			}
			for (int number = from; number <= to && number <= pool.size(); number++) {
				ids.add(pool.get(number - 1).id);
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

	private static void showHelp(FabricClientCommandSource source) {
		feedback(source, "Wynn Bridge commands:");
		feedback(source, "/mail send <player> <title> <text> - send mail (quote multi-word titles)");
		feedback(source, "/mail inbox [unread|read] [page] - list mail, unread first");
		feedback(source, "/mail read <n> - open the mail at position n");
		feedback(source, "/mail clear <all|n|x-y> [from <player>] - delete mail");
		feedback(source, "/bridge join <wss://host/ws#token> - set the bridge link");
		feedback(source, "/bridge status - server, connection and chat settings");
		feedback(source, "/bridge reconnect - reconnect to the bridge");
		feedback(source, "/bridge pattern <regex> - matcher for guild chat lines");
		feedback(source, "/bridge debug on|off - log incoming chat lines");
		feedback(source, "/bridge reload - reload config/wynnbridge.json");
		feedback(source, "/bridge update - download the newest release");
		feedback(source, "/bridge help - this list");
	}

	private static void feedback(FabricClientCommandSource source, String message) {
		source.sendFeedback(Text.literal(message));
	}
}
