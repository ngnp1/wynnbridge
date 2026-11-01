package com.wynnbridge;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.TextColor;
import net.minecraft.util.Identifier;
import net.minecraft.util.Formatting;

import java.util.Locale;

/**
 * Incoming chat handling.
 *
 * Guild chat is read client-side from {@link ClientReceiveMessageEvents#ALLOW_GAME}
 * and forwarded to the bridge. Discord and mail messages are injected straight
 * into the chat HUD, so they are never sent to the server.
 */
public final class ChatBridgeHooks {
	private static final int CHAT_FRAME = 0xCFFFC;

	private ChatBridgeHooks() {
	}

	public static void register(BridgeSocket socket, BridgeConfig config) {
		// ALLOW_GAME fires before MODIFY_GAME, i.e. before other client mods rewrite
		// the line, so every client forwards the text the server actually sent.
		// Reading the rewritten text (GAME) made the payload depend on each player's
		// own chat-mod config: Wynntils' nickname reveal turns "nick" into
		// "RealName/nick" (or "RealName" alone), so two players with different
		// settings sent different text for the same message and the server's content
		// dedupe let both through. Returning true keeps the message on screen.
		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
			if (!overlay) {
				forward(socket, config, message);
			}
			return true;
		});
	}

	private static void forward(BridgeSocket socket, BridgeConfig config, Text message) {
		String raw = message.getString();
		if (raw == null || raw.isEmpty()) {
			return;
		}
		String cleaned = stripGlyphs(raw);
		boolean shouldForward = config.forwardGuildChat
				&& !cleaned.isEmpty()
				&& isGuildChat(raw)
				&& config.pattern().matcher(cleaned).find();

		if (config.debugChat) {
			WynnBridgeClient.LOGGER.info("chat: [{}] {} {}", shouldForward ? "fwd" : "skip", hexPrefix(raw), cleaned);
		}
		if (shouldForward) {
			StyleSpriteSource font = findFont(message);
			if (font instanceof StyleSpriteSource.Font) {
				chatFont = font;
			}
			socket.sendChat(cleaned);
		}
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
	private static final int BRIDGE_COLOR = 0xCF0042;

	/** Colour used for mail lines, one shade warmer than the bridge. */
	private static final int MAIL_COLOR = 0xFFB86C;

	/** Bitmap fonts shipped in resources: the Discord icon and the BRIDGE badge. */
	private static final StyleSpriteSource ICON_FONT =
			new StyleSpriteSource.Font(Identifier.of("wynnbridge", "icon"));

	private static final StyleSpriteSource BADGE_FONT =
			new StyleSpriteSource.Font(Identifier.of("wynnbridge", "badge"));

	/** The same arrow and badge, drawn in the mail colour. */
	private static final StyleSpriteSource MAIL_ICON_FONT =
			new StyleSpriteSource.Font(Identifier.of("wynnbridge", "mail_icon"));

	private static final StyleSpriteSource MAIL_BADGE_FONT =
			new StyleSpriteSource.Font(Identifier.of("wynnbridge", "mail_badge"));

	private static final String ICON = "\uE001";
	private static final String BADGE = "\uE000";
	private static final String MAIL_ICON = "\uE004";
	private static final String MAIL_BADGE = "\uE003";

	/** Zero-width glyph with a negative advance, used to nudge the line left. */
	private static final StyleSpriteSource SHIFT_FONT =
			new StyleSpriteSource.Font(Identifier.of("wynnbridge", "shift"));

	private static final String SHIFT = "\uE002";

	/**
	 * Font Wynncraft uses for its own chat lines, mirrored so the bridged line has
	 * the same glyph height and spacing instead of the vanilla defaults.
	 */
	private static volatile StyleSpriteSource chatFont;

	public static String chatFontName() {
		return chatFont instanceof StyleSpriteSource.Font font ? font.id().toString() : "default";
	}

	private static Style withChatFont(Style style) {
		StyleSpriteSource font = chatFont;
		return font == null ? style : style.withFont(font);
	}

	/** First font declared anywhere in the component tree, if any. */
	private static StyleSpriteSource findFont(Text text) {
		StyleSpriteSource font = text.getStyle().getFont();
		if (font != null) {
			return font;
		}
		for (Text sibling : text.getSiblings()) {
			StyleSpriteSource found = findFont(sibling);
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	public static void showDiscordChat(String author, String content) {
		showChannel(SHIFT, ICON, ICON_FONT, BADGE, BADGE_FONT, BRIDGE_COLOR, author, content);
	}

	public static void showMail(String sender, String content) {
		showChannel(SHIFT, MAIL_ICON, MAIL_ICON_FONT, MAIL_BADGE, MAIL_BADGE_FONT, MAIL_COLOR, sender, content);
	}

	/**
	 * One themed chat line: shift, arrow, badge, then {@code author: content}.
	 */
	private static void showChannel(String shift, String icon, StyleSpriteSource iconFont,
			String badge, StyleSpriteSource badgeFont, int rgb, String author, String content) {
		TextColor color = TextColor.fromRgb(rgb);

		// The root must stay unstyled: a style on the root is inherited by the
		// appended siblings, which would draw the whole line in the badge font.
		MutableText line = Text.empty();
		line.append(Text.literal(shift).styled(style -> style.withFont(SHIFT_FONT)));
		line.append(Text.literal(icon).styled(style -> style.withFont(iconFont)));
		line.append(Text.literal(badge).styled(style -> style.withFont(badgeFont)));
		line.append(Text.literal(" " + author + ": ")
				.styled(style -> withChatFont(style.withColor(color))));
		line.append(Text.literal(content).styled(style -> withChatFont(style.withColor(color))));
		post(line);
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
