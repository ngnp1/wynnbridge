package com.wynnbridge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Self-update from GitHub releases.
 *
 * <p>{@code /bridge update} asks the GitHub API for the newest release and, if it
 * is newer than the running jar, drops it into {@code mods/} and removes the old
 * one. The jar cannot be swapped under a running JVM, so the new version only
 * loads after Minecraft is restarted.
 *
 * <p>No signature check is done: the release is trusted because it comes from
 * this repository over HTTPS.
 */
public final class BridgeUpdater {
	private static final String LATEST_RELEASE =
			"https://api.github.com/repos/ngnp1/wynnbridge/releases/latest";
	private static final Gson GSON = new Gson();
	private static final AtomicBoolean running = new AtomicBoolean();

	private BridgeUpdater() {
	}

	/** Version of the jar that is currently loaded, e.g. {@code 1.1.0}. */
	public static String currentVersion() {
		return FabricLoader.getInstance().getModContainer(WynnBridgeClient.MOD_ID)
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("unknown");
	}

	/** Starts the check off the render thread; results are posted to chat. */
	public static void checkAndInstall() {
		if (!running.compareAndSet(false, true)) {
			ChatBridgeHooks.showSystem("an update is already running");
			return;
		}
		ChatBridgeHooks.showSystem("checking for updates...");

		Thread thread = new Thread(BridgeUpdater::run, "wynnbridge-updater");
		thread.setDaemon(true);
		thread.start();
	}

	/**
	 * Checks the newest release off the render thread and hands a one-line status
	 * to {@code onResult}, which runs on the checking thread.
	 */
	public static void checkAsync(Consumer<String> onResult) {
		Thread thread = new Thread(() -> {
			String message;
			try {
				HttpClient http = HttpClient.newBuilder()
						.followRedirects(HttpClient.Redirect.NORMAL)
						.connectTimeout(Duration.ofSeconds(10))
						.build();
				message = updateStatus(http);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				message = "could not check for a new release";
			} catch (Exception e) {
				WynnBridgeClient.LOGGER.warn("Update check failed", e);
				message = "could not check for a new release";
			}
			onResult.accept(message);
		}, "wynnbridge-update-check");
		thread.setDaemon(true);
		thread.start();
	}

	/** The login notice: says whether a newer release waits to be downloaded. */
	private static String updateStatus(HttpClient http) throws IOException, InterruptedException {
		String current = currentVersion();
		JsonObject release = latestRelease(http);
		String latest = text(release, "tag_name");
		if (latest.startsWith("v")) {
			latest = latest.substring(1);
		}
		if (latest.isEmpty()) {
			return "could not read the newest release";
		}
		if (compareVersions(latest, current) <= 0) {
			return "Wynn Bridge is up to date (" + current + ")";
		}
		return "new release " + latest + " available - run /bridge update";
	}

	private static void run() {
		try {
			HttpClient http = HttpClient.newBuilder()
					.followRedirects(HttpClient.Redirect.NORMAL)
					.connectTimeout(Duration.ofSeconds(10))
					.build();

			String current = currentVersion();
			JsonObject release = latestRelease(http);
			String latest = text(release, "tag_name");
			if (latest.startsWith("v")) {
				latest = latest.substring(1);
			}
			if (latest.isEmpty()) {
				ChatBridgeHooks.showSystem("could not read the newest release");
				return;
			}
			if (compareVersions(latest, current) <= 0) {
				ChatBridgeHooks.showSystem("already on the newest version (" + current + ")");
				return;
			}

			String url = jarAssetUrl(release);
			if (url == null) {
				ChatBridgeHooks.showSystem("release " + latest + " has no jar attached");
				return;
			}

			Path mods = FabricLoader.getInstance().getGameDir().resolve("mods");
			if (!Files.isDirectory(mods)) {
				ChatBridgeHooks.showSystem("no mods folder at " + mods);
				return;
			}

			ChatBridgeHooks.showSystem("downloading " + latest + "...");
			byte[] jar = download(http, url, current);
			Path target = mods.resolve("wynnbridge-" + latest + ".jar");
			install(mods, target, jar);

			int left = removeOldJars(mods, target.getFileName().toString());
			ChatBridgeHooks.showSystem("updated to " + latest + " - restart Minecraft to use it");
			if (left > 0) {
				ChatBridgeHooks.showSystem("could not delete " + left + " old jar(s), remove them from mods/");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			ChatBridgeHooks.showSystem("update interrupted");
		} catch (Exception e) {
			WynnBridgeClient.LOGGER.warn("Update failed", e);
			ChatBridgeHooks.showSystem("update failed: " + e);
		} finally {
			running.set(false);
		}
	}

	private static JsonObject latestRelease(HttpClient http) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(LATEST_RELEASE))
				.header("Accept", "application/vnd.github+json")
				.header("User-Agent", "wynnbridge/" + currentVersion())
				.timeout(Duration.ofSeconds(20))
				.GET()
				.build();

		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IOException("GitHub returned HTTP " + response.statusCode());
		}
		JsonObject release = GSON.fromJson(response.body(), JsonObject.class);
		if (release == null) {
			throw new IOException("empty release payload");
		}
		return release;
	}

	/** First jar asset of the release. */
	private static String jarAssetUrl(JsonObject release) {
		if (!release.has("assets") || !release.get("assets").isJsonArray()) {
			return null;
		}
		JsonArray assets = release.getAsJsonArray("assets");
		for (JsonElement element : assets) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject asset = element.getAsJsonObject();
			if (text(asset, "name").endsWith(".jar")) {
				String url = text(asset, "browser_download_url");
				if (!url.isEmpty()) {
					return url;
				}
			}
		}
		return null;
	}

	private static byte[] download(HttpClient http, String url, String version)
			throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.header("User-Agent", "wynnbridge/" + version)
				.timeout(Duration.ofMinutes(2))
				.GET()
				.build();

		HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
		if (response.statusCode() != 200) {
			throw new IOException("download returned HTTP " + response.statusCode());
		}
		byte[] body = response.body();
		if (!isZip(body)) {
			throw new IOException("download is not a jar");
		}
		return body;
	}

	private static boolean isZip(byte[] body) {
		return body.length > 4 && body[0] == 'P' && body[1] == 'K';
	}

	/**
	 * Writes next to the target first so a half-finished download never replaces
	 * a working jar.
	 */
	private static void install(Path mods, Path target, byte[] jar) throws IOException {
		Path temp = Files.createTempFile(mods, "wynnbridge-", ".jar.part");
		try {
			Files.write(temp, jar);
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	/** Deletes every other {@code wynnbridge-*.jar}; returns how many remain. */
	private static int removeOldJars(Path mods, String keep) {
		int remaining = 0;
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(mods, "wynnbridge-*.jar")) {
			for (Path entry : entries) {
				if (entry.getFileName().toString().equals(keep)) {
					continue;
				}
				try {
					Files.deleteIfExists(entry);
				} catch (IOException e) {
					WynnBridgeClient.LOGGER.warn("Could not delete {}", entry, e);
					remaining++;
				}
			}
		} catch (IOException e) {
			WynnBridgeClient.LOGGER.warn("Could not list {}", mods, e);
		}
		return remaining;
	}

	/** Compares dotted versions numerically; anything unparsable sorts low. */
	static int compareVersions(String left, String right) {
		String[] a = left.split("[.-]");
		String[] b = right.split("[.-]");
		for (int i = 0; i < Math.max(a.length, b.length); i++) {
			int x = i < a.length ? number(a[i]) : 0;
			int y = i < b.length ? number(b[i]) : 0;
			if (x != y) {
				return Integer.compare(x, y);
			}
		}
		return 0;
	}

	private static int number(String part) {
		try {
			return Integer.parseInt(part.trim());
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static String text(JsonObject object, String key) {
		JsonElement element = object.get(key);
		if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
			return "";
		}
		return element.getAsString();
	}
}
