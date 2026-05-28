# mtg-xmage-gateway

WebSocket ↔ XMage bridge. Will replace the Python `magarena-gateway` when
Phase 1 lands.

## Status — paused, not stuck on logic

| Layer | State |
|---|---|
| XMage server (mini, container `xmage`, ports 17171/17179) | ✅ Running |
| Gateway Maven setup, builds, packages 24 MB fat jar | ✅ Working |
| Gateway WS server (Tyrus) — accepts clients on configurable port | ✅ Working |
| Probe (`Probe.main`) — `SessionImpl.connectStart` → XMage server | ❌ EOFException during JBoss Remoting handshake |
| MageClient callback wiring | ⛔ Blocked on probe |
| JSON ↔ XMage event translation | ⛔ Blocked on probe |
| Frontend cutover | ⛔ Blocked on gateway |

See [`../docs/engine-switch.md`](../docs/engine-switch.md) §9 for the full
session log and where to pick up.

## Quick verifications

```bash
# Build
JAVA_HOME=/opt/homebrew/opt/openjdk@17 mvn -B -q package

# Run Tyrus WS server (works — stub handshake to browser)
WS_PORT=18080 java -jar target/mtg-xmage-gateway.jar
# then: ws://localhost:18080/  → {"type":"handshake","status":"scaffold-only",…}

# Run probe (currently fails)
java \
  --add-opens java.base/java.io=ALL-UNNAMED \
  --add-opens java.base/java.lang=ALL-UNNAMED \
  --add-opens java.base/java.net=ALL-UNNAMED \
  --add-opens java.base/java.util=ALL-UNNAMED \
  --add-opens java.base/java.nio=ALL-UNNAMED \
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
  -cp target/mtg-xmage-gateway.jar \
  com.regardlessly.mtgweb.Probe timmys-mac-mini.tailaed610.ts.net 17171
```

## Next session — the first hour

1. **Run XMage's own `LoadTest` against the live server**. It's their
   own integration test that uses the exact same `SessionImpl` flow our
   probe attempts. If LoadTest connects, copy its setup verbatim. If
   LoadTest also fails, the server config needs adjustment.

   ```bash
   cd /tmp/mage
   # Look at Mage.Tests/src/test/java/org/mage/test/load/LoadTest.java first;
   # there's a TEST_SERVER constant near top that needs to be set to
   # "timmys-mac-mini.tailaed610.ts.net" (or "xmage" if running from
   # a container on magarena-net). TEST_PORT=17171.
   mvn -pl Mage.Tests -Dtest=LoadTest test
   ```

2. **If LoadTest connects**, the failing piece in our `Probe.java` is
   probably:
   - We're missing a step LoadTest does (e.g. `client.setSession(session)`)
   - Our `Connection` is missing a field LoadTest's `createSimpleConnection` includes (unlikely — they're almost identical)
   - Our shaded jar is missing something the LoadTest classpath has

3. **If LoadTest also fails with EOFException**, the server-side config
   needs work. Likely candidates:
   - `serverAddress="0.0.0.0"` → try `"timmys-mac-mini.tailaed610.ts.net"` or the container's resolvable hostname
   - Server might need `secondaryConnectPort` set explicitly (not just `secondaryBindPort`) — see `Mage.Server/config/config.xml` comments around `secondaryBindPort`
   - Server JVM might need its own `--add-opens` flags (XMage's `startServer.sh` doesn't set any, which is suspicious for Java 17)

## Operational pointers

- **Server source** (depth=1 clone): `/tmp/mage/`
- **Server release zip**: `/tmp/mage-full.zip` (238 MB)
- **Build context for the container** (Dockerfile + jar + config): `caritahub-mini:/tmp/xmage-build/`
- **Server config currently in use**: `caritahub-mini:/tmp/xmage-build/mage-server/config/config.xml`
  - Modified: `secondaryBindPort="17179"` (was `-1`)
  - Otherwise stock
- **Probe binary**: `caritahub-mini:/tmp/probe.jar` (latest shaded gateway jar)
- **Engines still running for the live site**: all `magarena-*` containers untouched

## What works without XMage

The existing Magarena setup at `localhost:5195` continues to function.
This whole gateway-xmage scaffold is dormant — building it and probing
it has no effect on the live site. Safe to leave overnight as-is.
