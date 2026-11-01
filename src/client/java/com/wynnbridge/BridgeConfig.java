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
 * defaults on first launch.
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

	/** Lines matching this regex are treated as guild chat. */
	public String guildPattern = "(?i)\\bguild\\b";

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
