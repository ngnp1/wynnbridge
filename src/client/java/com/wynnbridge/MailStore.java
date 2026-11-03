package com.wynnbridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Local copy of the player's mail, kept in {@code config/wynnbridge-mail.json}
 * so messages remain readable after a restart. Sent mail is mirrored to
 * {@code config/wynnbridge-mail-sent.json}, since the bot never hands it back.
 */
public final class MailStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type LIST_TYPE = new TypeToken<List<Mail>>() {
	}.getType();
	private static final Type SENT_LIST_TYPE = new TypeToken<List<SentMail>>() {
	}.getType();

	public static final class Mail {
		public int id;
		public String sender = "";
		public String title = "";
		public String content = "";
		public String createdAt = "";
		public boolean read;
	}

	/** A mail this player sent; only the local copy exists. */
	public static final class SentMail {
		public int id;
		public String recipient = "";
		public String title = "";
		public String content = "";
		public String createdAt = "";
	}

	private final List<Mail> mails = new ArrayList<>();
	private final List<SentMail> sentMails = new ArrayList<>();
	private int nextSentId = 1;

	public MailStore() {
		loadInbox();
		loadSent();
	}

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("wynnbridge-mail.json");
	}

	public static Path sentPath() {
		return FabricLoader.getInstance().getConfigDir().resolve("wynnbridge-mail-sent.json");
	}

	public synchronized boolean add(int id, String sender, String title, String content, String createdAt) {
		for (Mail existing : mails) {
			if (existing.id == id) {
				return false;
			}
		}
		Mail mail = new Mail();
		mail.id = id;
		mail.sender = sender;
		mail.title = title == null ? "" : title;
		mail.content = content;
		mail.createdAt = createdAt;
		mails.add(mail);
		saveInbox();
		return true;
	}

	public synchronized List<Mail> all() {
		return new ArrayList<>(mails);
	}

	public synchronized int unreadCount() {
		int count = 0;
		for (Mail mail : mails) {
			if (!mail.read) {
				count++;
			}
		}
		return count;
	}

	/**
	 * Marks a mail read. The mail itself stays in the inbox so it can be reread;
	 * only the unread count ignores it.
	 */
	public synchronized boolean markRead(int id) {
		for (Mail mail : mails) {
			if (mail.id == id) {
				if (!mail.read) {
					mail.read = true;
					saveInbox();
				}
				return true;
			}
		}
		return false;
	}

	/** Drops the mails with these ids; returns how many were removed. */
	public synchronized int remove(List<Integer> ids) {
		int before = mails.size();
		mails.removeIf(mail -> ids.contains(mail.id));
		int removed = before - mails.size();
		if (removed > 0) {
			saveInbox();
		}
		return removed;
	}

	/** Keeps a copy of a mail this player just sent, in the sent inbox. */
	public synchronized int addSent(String recipient, String title, String content) {
		SentMail mail = new SentMail();
		mail.id = nextSentId++;
		mail.recipient = recipient;
		mail.title = title == null ? "" : title;
		mail.content = content;
		mail.createdAt = Instant.now().toString();
		sentMails.add(mail);
		saveSent();
		return mail.id;
	}

	public synchronized List<SentMail> sent() {
		return new ArrayList<>(sentMails);
	}

	/** Drops the sent mails with these ids; returns how many were removed. */
	public synchronized int removeSent(List<Integer> ids) {
		int before = sentMails.size();
		sentMails.removeIf(mail -> ids.contains(mail.id));
		int removed = before - sentMails.size();
		if (removed > 0) {
			saveSent();
		}
		return removed;
	}

	private void loadInbox() {
		Path file = path();
		if (!Files.exists(file)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(file)) {
			List<Mail> parsed = GSON.fromJson(reader, LIST_TYPE);
			if (parsed != null) {
				mails.addAll(parsed);
			}
		} catch (Exception e) {
			WynnBridgeClient.LOGGER.warn("Could not read {}", file, e);
		}
	}

	private void loadSent() {
		Path file = sentPath();
		if (!Files.exists(file)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(file)) {
			List<SentMail> parsed = GSON.fromJson(reader, SENT_LIST_TYPE);
			if (parsed != null) {
				sentMails.addAll(parsed);
			}
		} catch (Exception e) {
			WynnBridgeClient.LOGGER.warn("Could not read {}", file, e);
		}
		// Auto-incrementing ids only live in memory, so resume after the last one
		// already stored instead of reusing (and later mis-deleting) an id.
		for (SentMail mail : sentMails) {
			nextSentId = Math.max(nextSentId, mail.id + 1);
		}
	}

	private void saveInbox() {
		save(path(), mails);
	}

	private void saveSent() {
		save(sentPath(), sentMails);
	}

	private void save(Path file, Object data) {
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file)) {
				GSON.toJson(data, writer);
			}
		} catch (Exception e) {
			WynnBridgeClient.LOGGER.warn("Could not write {}", file, e);
		}
	}
}
