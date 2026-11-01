package com.wynnbridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Settings stored in {@code config/wynnbridge.json}. The file is created with
 * defaults on first launch and can be re-read with {@code /bridge reload}.
 */
public final class BridgeConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** WebSocket endpoint of the bot's bridge server. */
	public String url = "ws://127.0.0.1:8765/ws";

	/** Token from /bridge_link in Discord. */
	public String token = "";

	/** Forward matching guild chat lines to Discord. */
	public boolean forwardGuildChat = true;

	/** Log every incoming chat line so the guild pattern can be tuned. */
	public boolean debugChat = false;

	/**
	 * Lines matching this regex are treated as guild chat. It is matched against
	 * the readable text (icon glyphs and colour codes stripped), because
	 * Wynncraft changes the icon glyphs it uses from message to message.
	 */
	public String guildPattern = "^[^:]{1,48}: \\S";

	private transient Pattern compiledPattern;

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("wynnbridge.json");
	}

	public static BridgeConfig load() {
		Path file = path();
		BridgeConfig loaded = new BridgeConfig();

		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file)) {
				BridgeConfig parsed = GSON.fromJson(reader, BridgeConfig.class);
				if (parsed != null) {
					loaded = parsed;
				}
			} catch (IOException | RuntimeException e) {
				WynnBridgeClient.LOGGER.warn("Could not read {}, using defaults", file, e);
			}
		}

		loaded.save();
		return loaded;
	}

	/**
	 * Re-reads the file into this instance so edits apply without restarting
	 * the game. Missing keys keep their defaults.
	 */
	public void reload() {
		Path file = path();
		if (!Files.exists(file)) {
			WynnBridgeClient.LOGGER.warn("No config at {}, keeping current settings", file);
			return;
		}

		BridgeConfig parsed;
		try (Reader reader = Files.newBufferedReader(file)) {
			parsed = GSON.fromJson(reader, BridgeConfig.class);
		} catch (IOException | RuntimeException e) {
			WynnBridgeClient.LOGGER.warn("Could not read {}, keeping current settings", file, e);
			return;
		}
		if (parsed == null) {
			WynnBridgeClient.LOGGER.warn("{} is empty, keeping current settings", file);
			return;
		}

		if (parsed.url != null) {
			url = parsed.url;
		}
		if (parsed.token != null) {
			token = parsed.token;
		}
		if (parsed.guildPattern != null) {
			guildPattern = parsed.guildPattern;
		}
		forwardGuildChat = parsed.forwardGuildChat;
		debugChat = parsed.debugChat;
		compiledPattern = null;
	}

	/**
	 * Validates, applies and persists a new guild pattern.
	 *
	 * @return {@code null} on success, otherwise the pattern error
	 */
	public String setPattern(String regex) {
		try {
			compiledPattern = Pattern.compile(regex);
		} catch (PatternSyntaxException e) {
			return e.getDescription();
		}
		guildPattern = regex;
		save();
		return null;
	}

	public void save() {
		try {
			Files.createDirectories(path().getParent());
			try (Writer writer = Files.newBufferedWriter(path())) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			WynnBridgeClient.LOGGER.warn("Could not write {}", path(), e);
		}
	}

	public Pattern pattern() {
		if (compiledPattern == null) {
			try {
				compiledPattern = Pattern.compile(guildPattern);
			} catch (PatternSyntaxException e) {
				WynnBridgeClient.LOGGER.warn("Invalid guildPattern '{}', falling back to default", guildPattern, e);
				compiledPattern = Pattern.compile("(?i)\\bguild\\b");
			}
		}
		return compiledPattern;
	}

	public boolean isConfigured() {
		return token != null && !token.isBlank();
	}
}
