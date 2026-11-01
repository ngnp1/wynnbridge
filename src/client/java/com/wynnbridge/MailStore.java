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
import java.util.ArrayList;
import java.util.List;

/**
 * Local copy of the player's mail, kept in {@code config/wynnbridge-mail.json}
 * so messages remain readable after a restart.
 */
public final class MailStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type LIST_TYPE = new TypeToken<List<Mail>>() {
	}.getType();

	/** How many read tombstones to keep so re-pushed mail is recognised. */
	private static final int MAX_READ_HISTORY = 200;

	public static final class Mail {
		public int id;
		public String sender = "";
		public String title = "";
		public String content = "";
		public String createdAt = "";
		public boolean read;
	}

	private final List<Mail> mails = new ArrayList<>();

	public MailStore() {
		load();
	}

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("wynnbridge-mail.json");
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
		save();
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
	 * Marks a mail read. The entry stays behind as a tombstone: the fields still
	 * hold the id, so a re-push of the same mail is ignored by {@link #add} instead
	 * of climbing back into the inbox and raising the count again.
	 */
	public synchronized boolean markRead(int id) {
		for (Mail mail : mails) {
			if (mail.id == id) {
				if (!mail.read) {
					mail.read = true;
					save();
				}
				return true;
			}
		}
		return false;
	}

	private void load() {
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

	private void save() {
		pruneReadHistory();
		try {
			Files.createDirectories(path().getParent());
			try (Writer writer = Files.newBufferedWriter(path())) {
				GSON.toJson(mails, writer);
			}
		} catch (Exception e) {
			WynnBridgeClient.LOGGER.warn("Could not write {}", path(), e);
		}
	}

	/** Keeps the read tombstones from growing without bound. */
	private void pruneReadHistory() {
		int read = 0;
		for (Mail mail : mails) {
			if (mail.read) {
				read++;
			}
		}
		for (int i = 0; i < mails.size() && read > MAX_READ_HISTORY; ) {
			if (mails.get(i).read) {
				mails.remove(i);
				read--;
			} else {
				i++;
			}
		}
	}
}
