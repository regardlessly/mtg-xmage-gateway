# XMage Gateway — Client Connection Details

WebSocket bridge from browser ↔ XMage server. One WS connection = one authenticated XMage session = one game. Engine: XMage (replaces the legacy Magarena engine).

## Status

**Deployed and live.** Running as the `xmage-gateway` OrbStack container on `timmys-mac-mini`, image `xmage-gateway:0.3`. Smoke-tested end-to-end (handshake → start_game → bot-vs-bot game advances through turns). Container talks to the `xmage` container over OrbStack's docker network using `XMAGE_HOST=xmage`.

## Endpoint

```
ws://timmys-mac-mini.tailaed610.ts.net:8082/
```

- Protocol: plain `ws://` (Tailscale provides the transport encryption — no TLS needed)
- Path: `/` (single endpoint)
- Port: `8082` (host-mapped from the container's `WS_PORT=8082`)
- Client device must be on the tailnet to resolve the hostname
- **One WS connection per game.** No multiplexing — reconnect to start a new match.

On open, the gateway eagerly dials the upstream XMage server. The browser then sends `start_game` to actually launch a match.

## Client → Gateway (messages the frontend sends)

```json
// Start a match. Both deck ids optional (omit → bundled Forest/Llanowar default).
// mode: "observe" (bot v bot) or "human" (P0 = the WS client).
{ "type": "start_game", "mode": "human", "player_deck": "<id>", "bot_deck": "<id>" }

// Respond to a prompt — N is the option index from the prompt's `options[]`.
{ "choice": 2 }
// equivalent form:
{ "type": "choice", "choice": 2 }
```

## Gateway → Client (messages the frontend receives)

```json
// 1) Once, when game state is first ready
{ "type": "handshake", "observe": false, "human_player": 0,
  "players": [
    { "index": 0, "name": "you",  "deck": "…" },
    { "index": 1, "name": "ai_2", "deck": "…" }
  ] }

// 2) After every engine update
{ "type": "observation",
  "event": { "player": 0, "source": "", "description": "…", "choice_type": "GAME_SELECT" },
  "state": {
    "turn": 1, "phase": "…", "step": "…", "stack_size": 0, "turn_player": 0,
    "players": [
      { "index": 0, "life": 20, "hand_size": 7, "library_size": 53,
        "graveyard_size": 0, "hand": ["…"], "battlefield": [ … ] },
      …
    ]
  } }

// 3) When XMage asks the human for input (human mode only)
{ "type": "prompt",
  "event": { … },
  "state": { … },
  "options": [
    { "index": 0, "desc": "Cast Forest" },
    { "index": 1, "desc": "pass" }
  ] }

// 4) End of match
{ "type": "game_over", "finished": true, "winner": 0, "state": { … } }

// Errors
{ "type": "engine_error",      "message": "…" }
{ "type": "engine_disconnect" }
```

Full schema in [`src/main/java/com/regardlessly/mtgweb/MageJsonAdapter.java`](src/main/java/com/regardlessly/mtgweb/MageJsonAdapter.java).

## Caveats to flag to the client

1. **Only own-hand cards are revealed.** `state.players[0].hand` is populated for the seated human (P0 in `human` mode); opponents show `hand_size` only.
2. **`pass` is a convention.** Option labels containing exactly `pass` route to "pass priority"; the legacy frontend binds that to spacebar / a gold button. Preserve the label verbatim in the UI.
3. **Strings are already HTML-stripped.** The gateway strips `<…>` tags from XMage messages — no need to re-sanitize.
4. **Unknown pre-con deck IDs fall back silently.** If the supplied id doesn't resolve, the gateway uses a basic 40 Forest + 20 Llanowar Elves deck. Treat unknown ids as a soft warning client-side.

## Gateway env vars (for reference; current container values shown)

| Var | Code default | In container | Purpose |
|---|---|---|---|
| `WS_PORT`     | `8080`      | `8082`      | Listen port for browser WS clients |
| `XMAGE_HOST`  | `localhost` | `xmage`     | XMage server hostname (upstream) |
| `XMAGE_PORT`  | `17171`     | `17171`     | XMage server port |
| `GATEWAY_USER`| `gateway`   | `gateway`   | Username for gateway → XMage auth |
