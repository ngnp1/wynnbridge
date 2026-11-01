# Agent notes

## Every change ships as a release

Unless the user explicitly says otherwise, any code change is finished by bumping the
version, building, releasing and installing it locally. Do not stop at a working tree
diff and ask first — ship it.

1. Bump `version=` in `gradle.properties` (patch bump for fixes, minor for features).
2. `./gradlew build` and check it says `BUILD SUCCESSFUL`; the jar is
   `build/libs/wynnbridge-<version>.jar`.
3. Commit with a short descriptive subject, then create a lightweight tag `v<version>`
   (the existing tags are lightweight, not annotated).
4. `git push origin main && git push origin v<version>`.
5. `gh release create v<version> build/libs/wynnbridge-<version>.jar --title "Wynn Bridge <version>" --notes-file <file> --latest`
   with a body in the same shape as earlier releases: one-line summary, `## Changed`,
   `## Upgrade` (remove the old jar from `mods/`, drop in the new one).
6. Install into the client: copy the jar to
   `~/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/wynncraft_1.21.11/minecraft/mods/`
   and delete the previous `wynnbridge-*.jar` there.

Say what was released, with the release URL, at the end of the turn.

## Project facts

- Fabric mod for Minecraft 1.21.11, client-side only; `src/client/java/com/wynnbridge`.
- `fabric.mod.json` declares the minimum Fabric Loader (`>=0.18.0`); `gradle.properties`
  pins the versions used to build, currently loader 0.20.1.
- Guild chat is read from `ClientReceiveMessageEvents.ALLOW_GAME`, i.e. before other
  client mods edit the line. Keep it that way: reading the edited text (`GAME`) makes
  each client forward different text for the same message (e.g. Wynntils'
  `RevealNicknamesFeature` turns `nick` into `RealName/nick`), which defeats the bot's
  content dedupe and produces duplicate Discord posts.
- The bridge server (bot repo, `cogs/chat_bridge.py`) dedupes guild chat by content for
  5 seconds, so forwarded text must be identical on every client.
