package com.wynnbridge;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.TextColor;
import net.minecraft.util.Identifier;
import net.minecraft.util.Formatting;

import java.util.Locale;

/**
 * Incoming chat handling.
 *
 * Guild chat is read client-side from {@link ClientReceiveMessageEvents#GAME}
 * and forwarded to the bridge. Discord and mail messages are injected straight
 * into the chat HUD, so they are never sent to the server.
 */
public final class ChatBridgeHooks {
	private static final int CHAT_FRAME = 0xCFFFC;

	private ChatBridgeHooks() {
	}

	public static void register(BridgeSocket socket, BridgeConfig config) {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay) {
				return;
			}
			String raw = message.getString();
			if (raw == null || raw.isEmpty()) {
				return;
			}
			String cleaned = stripGlyphs(raw);
			boolean forward = config.forwardGuildChat
					&& !cleaned.isEmpty()
					&& isGuildChat(raw)
					&& config.pattern().matcher(cleaned).find();

			if (config.debugChat) {
				WynnBridgeClient.LOGGER.info("chat: [{}] {} {}", forward ? "fwd" : "skip", hexPrefix(raw), cleaned);
			}
			if (forward) {
				socket.sendChat(cleaned);
			}
		});
	}

	/**
	 * Guild chat and party chat share the same leading frame glyph (the glyph
	 * codepoints themselves are animated and change between messages), so the
	 * channel is read from the structure of the line:
	 *
	 * <pre>
	 * guild    CFFFC &lt;icon&gt; ' ' &lt;rank badge&gt; ' ' name ':' message
	 * party    CFFFC &lt;icon&gt; ' ' name ':' message
	 * whisper  CFFFC &lt;icon&gt; ' ' &lt;badge&gt; ' ' sender ' ' &lt;arrow&gt; ' ' name ':' message
	 * </pre>
	 *
	 * Only guild chat keeps plain text between the rank badge and the colon.
	 */
	static boolean isGuildChat(String raw) {
		if (raw.isEmpty() || raw.codePointAt(0) != CHAT_FRAME) {
			return false;
		}

		int i = skipGlyphs(raw, 0);
		if (!skipSpace(raw, i)) {
			return false;
		}
		i++;
		if (i >= raw.length() || !isGlyph(raw.codePointAt(i))) {
			return false;
		}
		i = skipGlyphs(raw, i);
		if (!skipSpace(raw, i)) {
			return false;
		}
		i++;

		// From the sender name to the colon there must be no further glyph run:
		// a whisper puts its arrow glyph here, between sender and recipient.
		for (; i < raw.length(); ) {
			int cp = raw.codePointAt(i);
			if (cp == ':') {
				return true;
			}
			if (isGlyph(cp)) {
				return false;
			}
			i += Character.charCount(cp);
		}
		return false;
	}

	private static boolean skipSpace(String raw, int i) {
		return i < raw.length() - 1 && raw.codePointAt(i) == ' ';
	}

	private static int skipGlyphs(String raw, int i) {
		while (i < raw.length()) {
			int cp = raw.codePointAt(i);
			if (!isGlyph(cp)) {
				break;
			}
			i += Character.charCount(cp);
		}
		return i;
	}

	private static boolean isGlyph(int cp) {
		int type = Character.getType(cp);
		return type == Character.PRIVATE_USE || type == Character.UNASSIGNED;
	}

	/** First few code points as hex, shown in debug logs to identify a line. */
	static String hexPrefix(String raw) {
		StringBuilder out = new StringBuilder("[");
		for (int i = 0, count = 0; i < raw.length() && count < 3; count++) {
			int cp = raw.codePointAt(i);
			i += Character.charCount(cp);
			if (count > 0) {
				out.append(' ');
			}
			out.append(Integer.toHexString(cp).toUpperCase(Locale.ROOT));
		}
		return out.append(']').toString();
	}

	/**
	 * Wynncraft draws channel icons with private-use font glyphs and colour
	 * codes, neither of which means anything outside Minecraft, so they are
	 * dropped before the line reaches Discord.
	 */
	static String stripGlyphs(String raw) {
		StringBuilder out = new StringBuilder(raw.length());
		for (int i = 0; i < raw.length(); ) {
			int cp = raw.codePointAt(i);
			i += Character.charCount(cp);

			if (cp == 0x00A7) {
				// section sign plus its formatting code
				if (i < raw.length()) {
					i += Character.charCount(raw.codePointAt(i));
				}
				continue;
			}

			int type = Character.getType(cp);
			if (type == Character.PRIVATE_USE || type == Character.UNASSIGNED
					|| type == Character.FORMAT || type == Character.CONTROL) {
				continue;
			}

			out.appendCodePoint(cp);
		}
		return out.toString().replaceAll("\\s+", " ").trim();
	}

	/** Colour used for messages mirrored in from Discord. */
	private static final int BRIDGE_COLOR = 0xACD8AA;

	/** Bitmap font shipped in resources, holding the {@code BRIDGE} badge glyph. */
	private static final StyleSpriteSource BADGE_FONT =
			new StyleSpriteSource.Font(Identifier.of("wynnbridge", "badge"));

	private static final String BADGE = "\uE000";

	public static void showDiscordChat(String author, String content) {
		TextColor color = TextColor.fromRgb(BRIDGE_COLOR);
		post(Text.literal(BADGE).styled(style -> style.withFont(BADGE_FONT))
				.append(Text.literal(" " + author + ": ").styled(style -> style.withColor(color)))
				.append(Text.literal(content).styled(style -> style.withColor(color))));
	}

	public static void showMail(String sender, String content) {
		post(Text.literal("[Mail] ").formatted(Formatting.GOLD)
				.append(Text.literal(sender).formatted(Formatting.WHITE))
				.append(Text.literal(": " + content)));
	}

	public static void showSystem(String message) {
		post(Text.literal("[Bridge] ").formatted(Formatting.DARK_AQUA)
				.append(Text.literal(message).formatted(Formatting.GRAY)));
	}

	private static void post(MutableText text) {
		MinecraftClient client = MinecraftClient.getInstance();
		client.execute(() -> {
			if (client.inGameHud != null) {
				client.inGameHud.getChatHud().addMessage(text);
			}
		});
	}
}
