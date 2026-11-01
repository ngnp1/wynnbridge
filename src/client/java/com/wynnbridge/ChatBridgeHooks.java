package com.wynnbridge;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.TextColor;
import net.minecraft.util.Identifier;

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

	/** Colour of the sender name on a bridged line, up to and including the colon. */
	private static final int BRIDGE_COLOR = 0xCF0042;

	/** Colour of the message itself, after the colon, and of the bridge glyph fill. */
	private static final int BRIDGE_MESSAGE_COLOR = 0xFD4988;

	/** Colour of the sender name on a mail line. */
	private static final int MAIL_COLOR = 0xFFB86C;

	/** The brighter mail colour: the unread count and every mail badge. */
	private static final int MAIL_BRIGHT_COLOR = 0xFFE3C5;

	/** Colour used for the mod's own notices, drawn on the SYSTEM badge. */
	private static final int SYSTEM_COLOR = 0x50FA7B;

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

	/** Glyphs for the mod's own notices. */
	private static final StyleSpriteSource SYSTEM_ICON_FONT =
			new StyleSpriteSource.Font(Identifier.of("wynnbridge", "system_icon"));

	private static final StyleSpriteSource SYSTEM_BADGE_FONT =
			new StyleSpriteSource.Font(Identifier.of("wynnbridge", "system_badge"));

	private static final String ICON = "\uE001";
	private static final String BADGE = "\uE000";
	private static final String MAIL_ICON = "\uE004";
	private static final String MAIL_BADGE = "\uE003";
	private static final String SYSTEM_ICON = "\uE008";
	private static final String SYSTEM_BADGE = "\uE007";

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
		themedLine(ICON_FONT, ICON, BADGE_FONT, BADGE,
				" " + author + ": ", BRIDGE_COLOR, content, BRIDGE_MESSAGE_COLOR);
	}

	/** A mail body, shown only once the mail is read. */
	/** A mail body, shown only once the mail is read. */
	public static void showMail(String sender, String content) {
		themedLine(MAIL_ICON_FONT, MAIL_ICON, MAIL_BADGE_FONT, MAIL_BADGE,
				" " + sender + ": ", MAIL_COLOR, content, MAIL_BRIGHT_COLOR);
	}

	/** A mail notice drawn in the mail theme. */
	public static void showMailNotice(String text) {
		themedLine(MAIL_ICON_FONT, MAIL_ICON, MAIL_BADGE_FONT, MAIL_BADGE,
				" ", MAIL_COLOR, text, MAIL_COLOR);
	}

	/**
	 * {@code You have 5 new Mails!} with only the count in the brighter mail
	 * colour, for both the login notice and the {@code /mail inbox} header.
	 * An empty inbox reads {@code You have no new Mail.}
	 */
	public static void showMailCount(int count) {
		TextColor amber = TextColor.fromRgb(MAIL_COLOR);
		TextColor bright = TextColor.fromRgb(MAIL_BRIGHT_COLOR);

		String suffix;
		if (count <= 0) {
			suffix = " new Mail.";
		} else if (count == 1) {
			suffix = " new Mail!";
		} else {
			suffix = " new Mails!";
		}

		MutableText line = themedHead(MAIL_ICON_FONT, MAIL_ICON, MAIL_BADGE_FONT, MAIL_BADGE);
		line.append(Text.literal(" You have ").styled(style -> withChatFont(style.withColor(amber))));
		line.append(Text.literal(count == 0 ? "no" : Integer.toString(count))
				.styled(style -> withChatFont(style.withColor(bright))));
		line.append(Text.literal(suffix).styled(style -> withChatFont(style.withColor(amber))));
		post(line);
	}

	/**
	 * A continuation line under a mail notice: no badge, indented to line up with
	 * the text of the badge line above it, with the inbox position and the word
	 * "from" in the brighter colour. Positions count the unread mail only, so they
	 * start at 1 again once the earlier ones are read.
	 */
	public static void showMailEntry(int number, String title, String sender) {
		TextColor amber = TextColor.fromRgb(MAIL_COLOR);
		TextColor bright = TextColor.fromRgb(MAIL_BRIGHT_COLOR);
		String trimmed = title == null ? "" : title.trim();

		MutableText line = Text.empty();
		line.append(Text.literal("    #" + number).styled(style -> withChatFont(style.withColor(bright))));
		line.append(Text.literal(trimmed.isEmpty() ? " " : " " + trimmed + " ")
				.styled(style -> withChatFont(style.withColor(amber))));
		line.append(Text.literal("from").styled(style -> withChatFont(style.withColor(bright))));
		line.append(Text.literal(" " + sender).styled(style -> withChatFont(style.withColor(amber))));
		post(line);
	}

	/**
	 * One themed chat line: shift, arrow, badge, then a prefix and the body text.
	 * Notices pass a single space as the prefix, so they read as {@code badge text}.
	 */
	private static void themedLine(StyleSpriteSource iconFont, String icon,
			StyleSpriteSource badgeFont, String badge,
			String prefix, int prefixRgb, String body, int bodyRgb) {
		TextColor prefixColor = TextColor.fromRgb(prefixRgb);
		TextColor bodyColor = TextColor.fromRgb(bodyRgb);

		MutableText line = themedHead(iconFont, icon, badgeFont, badge);
		line.append(Text.literal(prefix).styled(style -> withChatFont(style.withColor(prefixColor))));
		line.append(Text.literal(body).styled(style -> withChatFont(style.withColor(bodyColor))));
		post(line);
	}

	/** Shift, arrow and badge: the unstyled glyph run every themed line starts with. */
	private static MutableText themedHead(StyleSpriteSource iconFont, String icon,
			StyleSpriteSource badgeFont, String badge) {
		// The root must stay unstyled: a style on the root is inherited by the
		// appended siblings, which would draw the whole line in the badge font.
		MutableText line = Text.empty();
		line.append(Text.literal(SHIFT).styled(style -> style.withFont(SHIFT_FONT)));
		line.append(Text.literal(icon).styled(style -> style.withFont(iconFont)));
		line.append(Text.literal(badge).styled(style -> style.withFont(badgeFont)));
		return line;
	}

	/** A notice from the mod itself, drawn on the SYSTEM badge. */
	public static void showSystem(String message) {
		themedLine(SYSTEM_ICON_FONT, SYSTEM_ICON, SYSTEM_BADGE_FONT, SYSTEM_BADGE,
				" ", SYSTEM_COLOR, sentence(message), SYSTEM_COLOR);
	}

	/** Capitalises the first letter and closes the sentence with a full stop. */
	static String sentence(String message) {
		if (message == null || message.isEmpty()) {
			return message == null ? "" : message;
		}
		String text = message.trim();
		if (text.isEmpty()) {
			return text;
		}

		int first = text.codePointAt(0);
		String head = new String(Character.toChars(Character.toUpperCase(first))) + text.substring(Character.charCount(first));

		char last = head.charAt(head.length() - 1);
		boolean closed = last == '.' || last == '!' || last == '?' || last == '\u2026';
		return closed ? head : head + ".";
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
