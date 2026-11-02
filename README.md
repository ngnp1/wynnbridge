# Wynn Bridge (Fabric 1.21.11)

Client-side mod that connects Minecraft to the bot's chat bridge:

- **Wynn -> Discord:** guild chat lines are read client-side and forwarded to the bridge channel.
- **Discord -> Wynn:** messages from the bridge channel are injected into your chat HUD only —
  nothing is sent to Wynncraft.
- **Mail:** `/mail send <player> <title> <text>` stores a message server-side. Titles may be
  quoted to hold several words and are capped at 60 characters. The recipient sees a themed
  `You have <n> new Mails!` line when they log in or when mail arrives; `/mail inbox` lists every
  mail, unread first, with `<unr>`/`<r>` tags and paging. `/mail read <n>` opens one and
  `/mail clear` deletes them. Pending mail is polled every 15 seconds, so a mail the server
  could not push still shows up without a relog.

The backend (Discord bot + WebSocket server) lives in the companion bot repository, in
`cogs/chat_bridge.py`.

## Requirements

- JDK 21
- The bot running with the bridge server reachable from every player's machine
  (`BRIDGE_CHANNEL_ID`, `BRIDGE_HOST`, `BRIDGE_PORT`)
- Fabric API installed alongside this mod

Versions in `gradle.properties` are pinned to what Fabric publishes for 1.21.11:

```
minecraft_version=1.21.11
yarn_mappings=1.21.11+build.6
loader_version=0.20.1
fabric_api_version=0.141.6+1.21.11
loom_version=1.19-SNAPSHOT
```

## Build

The Gradle wrapper JAR is not checked in. Bootstrap it once:

```bash
cd minecraft-mod

# either, if you have Gradle installed:
gradle wrapper --gradle-version 9.8.2

# or copy the wrapper from Fabric's example mod (branch 1.21.11):
#   gradlew, gradlew.bat, gradle/
```

Then:

```bash
./gradlew build
```

The jar lands in `build/libs/wynnbridge-1.0.0.jar`. Drop it and
[Fabric API](https://modrinth.com/mod/fabric-api) for 1.21.11 into `.minecraft/mods`.

## Configure

The quickest path needs no file editing. In Discord run `/bridge_link`; the bot replies with a
token and a ready-to-paste command such as:

```
/bridge join wss://bridge.example.com/ws#TOKEN
```

Type that in Minecraft. The mod saves
the URL and token, then reconnects. Check `/bridge status` - it should report `connected: yes`.

You can also edit `.minecraft/config/wynnbridge.json` by hand:

```json
{
  "url": "wss://bridge.example.com/ws",
  "token": "paste-the-token-here",
  "forwardGuildChat": true,
  "debugChat": false,
  "guildPattern": "(?i)\\bguild\\b"
}
```

The URL has to be reachable by every player. Tailscale works, but a TLS tunnel means players
install nothing extra: Cloudflare Tunnel (`wss://bridge.example.com/ws`) or
`tailscale funnel 8765` on the server (`wss://<machine>.<tailnet>.ts.net/ws`).

## Commands

| Command | Effect |
| --- | --- |
| `/mail send <player> <title> <text>` | Send mail through the bridge; quote the title for several words (max 60 chars) |
| `/mail inbox [unread\|read] [page]` | Heads with `Inbox - Page x` / `Inbox <unread> - Page x` / `Inbox <read> - Page x`, then ten `<unr>`/`<r>` `#n <title> from <sender>` lines, unread first |
| `/mail read <n>` | Show that mail and mark it read; it stays in the inbox |
| `/mail clear <all\|n\|x-y> [from <player>]` | Delete one mail, an inclusive range, or everything; `from` narrows it to one sender |
| `/bridge join <link>` | Save a `wss://host/ws#token` link and reconnect |
| `/bridge status` | Server URL, connection state, unread count |
| `/bridge reconnect` | Force a reconnect |
| `/bridge update` | Download the newest GitHub release into `mods/` (restart to apply) |
| `/bridge help` | List every command in chat |

Client commands are handled locally and never sent to Wynncraft. If `/mail` collides with a
Wynncraft command, rename the literal in `BridgeCommands.java`.

## Notes and limits

- **Never auto-sends to Wynncraft.** Discord messages go straight to the chat HUD
  (`ChatHud.addMessage`), not through the server connection.
- Mod notices use their own `SYSTEM` badge in `#50fa7b`. Bridged Discord chat is themed pink:
  the arrow, badge and message use `#fd4988` with black lettering, the sender name `#cf0042`.
  Mail glyphs and the unread count use `#ffe3c5`, other mail text `#ffb86c`. Glyphs come from
  `tools/gen_badge.py`.
- `/bridge update` trusts the GitHub release over HTTPS and swaps the jar in `mods/`; the
  new version loads on the next start.
- The server dedupes guild chat for 5 seconds, because every connected client sees the same
  line and would otherwise post duplicates to Discord.
- Tokens are bearer credentials: anyone with one can claim a Minecraft name. Keep the bridge
  port private (VPN/tunnel) or put it behind TLS with auth.
- Chat is truncated to 240 characters by the server, mail to 900.
- Mail is stored in SQLite on the bot (`data/bridge.sqlite3`) plus a local copy in
  `config/wynnbridge-mail.json`; reading a mail marks it read in both, and it stays in the inbox
  until `/mail clear` removes it there and on the bot.

## If compilation fails

The code targets the Yarn mapping names for 1.21.11. If a Fabric API class moved in your exact
version, the error will name it — the likely spots are:

- `ClientReceiveMessageEvents.GAME` (`net.fabricmc.fabric.api.client.message.v1`)
- `ClientCommandManager` (`net.fabricmc.fabric.api.client.command.v2`)
- `ChatHud#addMessage` (Yarn: `inGameHud.getChatHud().addMessage(Text)`)

Check https://fabricmc.net/develop for the versions and the Fabric API javadocs for signatures.
