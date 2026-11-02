package com.wynnbridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Custom titles assigned by the bot and drawn above player names.
 *
 * <p>Titles are kept as legacy text, so {@code &a}, {@code &l} and
 * {@code &#rrggbb} colour codes are turned into styles when the label is built.
 */
public final class PlayerTitles {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {
	}.getType();

	private static volatile Map<String, String> titles = Map.of();

	private PlayerTitles() {
	}

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("wynnbridge-titles.json");
	}

	/** Reads the last titles the bot handed out, so they show before reconnecting. */
	public static void load() {
		Path file = path();
		if (!Files.exists(file)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(file)) {
			Map<String, String> parsed = GSON.fromJson(reader, MAP_TYPE);
			if (parsed != null) {
				replace(parsed);
			}
		} catch (Exception e) {
			WynnBridgeClient.LOGGER.warn("Could not read {}", file, e);
		}
	}

	/** Replaces the whole title map, as the bot sends it on auth and on change. */
	public static void replaceAll(Map<String, String> incoming) {
		replace(incoming == null ? Map.of() : incoming);
		save();
	}

	private static void replace(Map<String, String> incoming) {
		Map<String, String> copy = new HashMap<>();
		for (Map.Entry<String, String> entry : incoming.entrySet()) {
			if (entry.getKey() == null || entry.getValue() == null) {
				continue;
			}
			String name = entry.getKey().trim().toLowerCase(Locale.ROOT);
			String title = entry.getValue().trim();
			if (!name.isEmpty() && !title.isEmpty()) {
				copy.put(name, title);
			}
		}
		titles = Map.copyOf(copy);
	}

	private static void save() {
		try {
			Files.createDirectories(path().getParent());
			try (Writer writer = Files.newBufferedWriter(path())) {
				GSON.toJson(titles, writer);
			}
		} catch (Exception e) {
			WynnBridgeClient.LOGGER.warn("Could not write {}", path(), e);
		}
	}

	/** The title for this player name, or {@code null}. */
	public static String get(String playerName) {
		if (playerName == null) {
			return null;
		}
		return titles.get(playerName.trim().toLowerCase(Locale.ROOT));
	}

	/** Turns {@code &aHello} / {@code &#ff8800Hello} into styled text. */
	public static MutableText toText(String legacy) {
		MutableText out = Text.empty();
		Style style = Style.EMPTY;
		StringBuilder run = new StringBuilder();

		for (int i = 0; i < legacy.length(); ) {
			char ch = legacy.charAt(i);
			if (ch == '&' && i + 1 < legacy.length()) {
				char code = Character.toLowerCase(legacy.charAt(i + 1));

				if (code == '#' && i + 7 < legacy.length()) {
					String hex = legacy.substring(i + 2, i + 8);
					if (hex.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
						flush(out, run, style);
						style = style.withColor(Integer.parseInt(hex, 16));
						i += 8;
						continue;
					}
				}

				Formatting formatting = byCode(code);
				if (formatting != null) {
					flush(out, run, style);
					style = apply(style, formatting);
					i += 2;
					continue;
				}
			}

			int codePoint = legacy.codePointAt(i);
			run.appendCodePoint(codePoint);
			i += Character.charCount(codePoint);
		}

		flush(out, run, style);
		return out;
	}

	private static Style apply(Style style, Formatting formatting) {
		if (formatting == Formatting.RESET) {
			return Style.EMPTY;
		}
		// A colour code clears the modifiers, like it does in vanilla chat.
		return formatting.isColor() ? Style.EMPTY.withColor(formatting) : style.withFormatting(formatting);
	}

	private static void flush(MutableText out, StringBuilder run, Style style) {
		if (run.length() == 0) {
			return;
		}
		out.append(Text.literal(run.toString()).setStyle(style));
		run.setLength(0);
	}

	private static Formatting byCode(char code) {
		for (Formatting formatting : Formatting.values()) {
			if (formatting.getCode() == code) {
				return formatting;
			}
		}
		return null;
	}
}
