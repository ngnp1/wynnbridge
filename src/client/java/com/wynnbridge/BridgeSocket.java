package com.wynnbridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket client for the bot's bridge server.
 *
 * Sending happens on a dedicated thread because {@link WebSocket#sendText}
 * rejects overlapping sends, and receiving is dispatched on to the client
 * thread by {@link ChatBridgeHooks}.
 */
public final class BridgeSocket {
	private static final Gson GSON = new Gson();
	private static final long MAX_BACKOFF_MS = 30_000L;

	/**
	 * Pending mails arrive in a burst right after {@code auth_ok}, so the unread
	 * notice waits for the burst to settle and then reports the final count.
	 */
	private static final long MAIL_NOTICE_DELAY_MS = 1_500L;

	/**
	 * The server pushes mail to an online recipient, but that push can be lost
	 * (a reconnect leaves a stale socket behind), so ask for anything pending
	 * now and then as well.
	 */
	private static final long MAIL_POLL_MS = 15_000L;

	private final BridgeConfig config;
	private final MailStore mailStore;
	private final HttpClient http = HttpClient.newHttpClient();
	private final BlockingQueue<String> outbox = new LinkedBlockingQueue<>();
	private final StringBuilder inbound = new StringBuilder();

	private volatile WebSocket webSocket;
	private volatile boolean authenticated;
	private volatile boolean connecting;
	private volatile boolean warnedMissingToken;
	private volatile String playerName;
	private volatile long nextAttemptAt;
	private volatile long backoffMs = 1_000L;
	private volatile boolean mailNoticePending;
	private volatile long mailNoticeDueAt;
	private volatile long nextMailFetchAt;

	public BridgeSocket(BridgeConfig config, MailStore mailStore) {
		this.config = config;
		this.mailStore = mailStore;
	}

	public boolean isAuthenticated() {
		return authenticated;
	}

	public String url() {
		return config.url;
	}

	public void connect(String player) {
		this.playerName = player;
		this.backoffMs = 1_000L;
		this.nextAttemptAt = 0L;
		open();
	}

	public void disconnect() {
		this.playerName = null;
		this.authenticated = false;
		WebSocket ws = this.webSocket;
		this.webSocket = null;
		if (ws != null) {
			ws.sendClose(WebSocket.NORMAL_CLOSURE, "client disconnect");
		}
	}

	public void reconnect() {
		this.authenticated = false;
		WebSocket ws = this.webSocket;
		this.webSocket = null;
		if (ws != null) {
			ws.sendClose(WebSocket.NORMAL_CLOSURE, "reconnect");
		}
		this.backoffMs = 1_000L;
		this.nextAttemptAt = 0L;
		if (this.playerName != null) {
			open();
		}
	}

	/**
	 * Applies a shared link such as {@code wss://relay.example.com/ws#TOKEN},
	 * saves it to the config and reconnects.
	 */
	public boolean applyLink(String link) {
		URI uri;
		try {
			uri = URI.create(link.trim());
		} catch (IllegalArgumentException e) {
			return false;
		}

		String scheme = uri.getScheme();
		if (scheme == null || !(scheme.equals("ws") || scheme.equals("wss")) || uri.getRawAuthority() == null) {
			return false;
		}

		String token = null;
		String fragment = uri.getRawFragment();
		if (fragment != null && !fragment.isBlank()) {
			token = decode(fragment);
		}
		String query = uri.getRawQuery();
		if (token == null && query != null) {
			for (String part : query.split("&")) {
				if (part.startsWith("token=")) {
					token = decode(part.substring("token=".length()));
					break;
				}
			}
		}
		if (token != null && token.startsWith("token=")) {
			token = token.substring("token=".length());
		}

		String path = uri.getRawPath();
		config.url = scheme + "://" + uri.getRawAuthority()
				+ (path == null || path.isEmpty() ? "/ws" : path);
		if (token != null && !token.isBlank()) {
			config.token = token;
		}
		config.save();
		warnedMissingToken = false;
		reconnect();
		return true;
	}

	private static String decode(String value) {
		try {
			return URLDecoder.decode(value, StandardCharsets.UTF_8);
		} catch (IllegalArgumentException e) {
			return value;
		}
	}

	/** Called every client tick so reconnects and retries happen on the main thread. */
	public void tick() {
		flushMailNotice();
		pollMail();

		if (playerName == null || webSocket != null || connecting) {
			return;
		}
		if (System.currentTimeMillis() >= nextAttemptAt) {
			open();
		}
	}

	private void pollMail() {
		if (!authenticated) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now < nextMailFetchAt) {
			return;
		}
		nextMailFetchAt = now + MAIL_POLL_MS;
		mailFetch();
	}

	/** Shows one themed "you have x unread mails" line for the current inbox. */
	private void flushMailNotice() {
		if (!mailNoticePending || System.currentTimeMillis() < mailNoticeDueAt) {
			return;
		}
		mailNoticePending = false;
		int unread = mailStore.unreadCount();
		ChatBridgeHooks.showMailNotice("You have " + unread + (unread == 1 ? " unread mail" : " unread mails"));
	}

	private void scheduleMailNotice() {
		mailNoticeDueAt = System.currentTimeMillis() + MAIL_NOTICE_DELAY_MS;
		mailNoticePending = true;
	}

	private void open() {
		if (!config.isConfigured()) {
			if (!warnedMissingToken) {
				WynnBridgeClient.LOGGER.warn("No bridge token configured - run /bridge_link in Discord first.");
				warnedMissingToken = true;
			}
			return;
		}

		URI uri;
		try {
			uri = URI.create(config.url);
		} catch (IllegalArgumentException e) {
			WynnBridgeClient.LOGGER.warn("Invalid bridge url '{}'", config.url);
			scheduleRetry();
			return;
		}

		connecting = true;
		http.newWebSocketBuilder()
				.connectTimeout(Duration.ofSeconds(10))
				.buildAsync(uri, new Listener())
				.whenComplete((ws, error) -> {
					connecting = false;
					if (error != null) {
						WynnBridgeClient.LOGGER.warn("Bridge connection to {} failed: {}", config.url, error.toString());
						scheduleRetry();
						return;
					}
					webSocket = ws;
					inbound.setLength(0);
					startSender(ws);
					sendAuth();
				});
	}

	private void sendAuth() {
		JsonObject payload = new JsonObject();
		payload.addProperty("op", "auth");
		payload.addProperty("token", config.token);
		payload.addProperty("player", playerName == null ? "" : playerName);
		send(payload);
	}

	private void startSender(WebSocket ws) {
		Thread sender = new Thread(() -> {
			try {
				while (true) {
					if (webSocket != ws) {
						return;
					}
					String message = outbox.poll(1, TimeUnit.SECONDS);
					if (message == null) {
						continue;
					}
					if (webSocket != ws) {
						outbox.offer(message);
						return;
					}
					ws.sendText(message, true).join();
				}
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			} catch (Exception e) {
				WynnBridgeClient.LOGGER.warn("Bridge send failed", e);
			}
		}, "wynnbridge-sender");
		sender.setDaemon(true);
		sender.start();
	}

	public void send(JsonObject payload) {
		outbox.offer(GSON.toJson(payload));
	}

	public void sendChat(String content) {
		if (!authenticated) {
			return;
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("op", "chat");
		payload.addProperty("content", content);
		send(payload);
	}

	public void sendMail(String recipient, String content) {
		if (!authenticated) {
			ChatBridgeHooks.showSystem("not connected to the bridge");
			return;
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("op", "mail_send");
		payload.addProperty("recipient", recipient);
		payload.addProperty("content", content);
		send(payload);
	}

	public void mailRead(int id) {
		if (!authenticated) {
			return;
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("op", "mail_read");
		payload.addProperty("id", id);
		send(payload);
	}

	public void mailFetch() {
		if (!authenticated) {
			return;
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("op", "mail_fetch");
		send(payload);
	}

	private void scheduleRetry() {
		long wait = backoffMs;
		nextAttemptAt = System.currentTimeMillis() + wait;
		backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
	}

	/**
	 * Only the socket we currently consider active may change connection state;
	 * a socket replaced by reconnecting must not clear the new one.
	 */
	private void handleClosed(WebSocket ws) {
		if (ws != webSocket) {
			return;
		}
		webSocket = null;
		authenticated = false;
		scheduleRetry();
	}

	private void handleFrame(String raw) {
		JsonObject payload;
		try {
			payload = GSON.fromJson(raw, JsonObject.class);
		} catch (RuntimeException e) {
			return;
		}
		if (payload == null || !payload.has("op")) {
			return;
		}

		String op = payload.get("op").getAsString();
		switch (op) {
			case "auth_ok" -> {
				authenticated = true;
				backoffMs = 1_000L;
				WynnBridgeClient.LOGGER.info("Bridge authenticated as {}", text(payload, "player"));
				// The server pushes anything unread right after authenticating.
				scheduleMailNotice();
			}
			case "auth_error" -> {
				WynnBridgeClient.LOGGER.warn("Bridge rejected credentials: {}", text(payload, "error"));
				backoffMs = MAX_BACKOFF_MS;
				WebSocket ws = webSocket;
				webSocket = null;
				authenticated = false;
				if (ws != null) {
					ws.sendClose(WebSocket.NORMAL_CLOSURE, "auth failed");
				}
				scheduleRetry();
			}
			case "chat" -> {
				String author = text(payload, "author");
				String content = text(payload, "content");
				if (config.debugChat) {
					WynnBridgeClient.LOGGER.info("discord -> game: {}: {}", author, content);
				}
				ChatBridgeHooks.showDiscordChat(author, content);
			}
			case "mail" -> {
				int id = payload.has("id") ? payload.get("id").getAsInt() : 0;
				String sender = text(payload, "sender");
				String content = text(payload, "content");
				// Mail bodies stay hidden until /mail read, so only report the count.
				// Re-pushed mail is ignored, so it does not retrigger the notice.
				if (mailStore.add(id, sender, content, text(payload, "created_at"))) {
					scheduleMailNotice();
				}
			}
			case "mail_sent" -> ChatBridgeHooks.showSystem("mail sent to " + text(payload, "recipient"));
			case "mail_error", "error" -> ChatBridgeHooks.showSystem("bridge error: " + text(payload, "error"));
			default -> {
			}
		}
	}

	private static String text(JsonObject payload, String key) {
		if (!payload.has(key) || payload.get(key).isJsonNull()) {
			return "";
		}
		return payload.get(key).getAsString();
	}

	private final class Listener implements WebSocket.Listener {
		@Override
		public void onOpen(WebSocket ws) {
			ws.request(1);
		}

		@Override
		public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
			synchronized (inbound) {
				inbound.append(data);
				if (last) {
					String raw = inbound.toString();
					inbound.setLength(0);
					if (ws == webSocket) {
						handleFrame(raw);
					}
				}
			}
			ws.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
			handleClosed(ws);
			return null;
		}

		@Override
		public void onError(WebSocket ws, Throwable error) {
			WynnBridgeClient.LOGGER.warn("Bridge socket error: {}", error.toString());
			handleClosed(ws);
		}
	}
}
