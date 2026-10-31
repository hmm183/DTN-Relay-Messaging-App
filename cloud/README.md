# DTN Mesh on Cloudflare (links, second relay, TURN minting)

One Worker serves everything. Free plan is enough for a small group.

| Path | What |
|---|---|
| `/c#dtnid:…`, `/g#dtngrp:…` | Link fallback page (QR, Copy code, Open in app). The `#…` part never reaches Cloudflare. |
| `/.well-known/assetlinks.json` | Android App Links verification |
| `/relay?node=<hex8>` | WebSocket relay (Durable Object `RelayRoom`), used next to HiveMQ |
| `/turn?node=<hex8>` | Short-lived Metered TURN credentials (only for phones connected to `/relay`) |

Files to deploy: this whole folder (`wrangler.toml`, `src/worker.ts`, `public/`).

## Deploy
1. Install Node.js, then `npm i -g wrangler` and `wrangler login`.
2. In this folder: `wrangler deploy`. Note the URL `https://dtnmesh.<your-subdomain>.workers.dev`.
3. TURN (optional; without it `/turn` returns public STUN only):
   ```
   wrangler secret put METERED_SECRET_KEY    # Metered dashboard › Developers › Secret Key
   wrangler secret put METERED_DOMAIN        # e.g. dtnmesh.metered.live
   ```
   Check the mint call once with curl (the field names `username` / `password` are what the Worker reads):
   ```
   curl -X POST "https://<app>.metered.live/api/v1/turn/credential?secretKey=<SECRET>" -H "Content-Type: application/json" -d "{\"expiryInSeconds\":600,\"label\":\"test\"}"
   ```
4. Check `https://<host>/.well-known/assetlinks.json` returns 200 JSON with no redirect.
5. App: in `local.properties` set `dtn.workerHost=<host>` (no `https://`). Once the Worker mints TURN,
   delete `dtn.defaultTurnApiKey`, `dtn.defaultTurnUsername`, `dtn.defaultTurnCredential` and rotate
   them in Metered: they were only a stop-gap and anyone with an APK can extract them. Rebuild.
6. Phone: `adb shell pm get-app-links com.dtn.mesh` should list the host as verified.

## assetlinks.json
Has ONE fingerprint. Add every certificate that signs an installed build:
- debug: `.\gradlew signingReport` in the project root (variant debug, SHA-256);
- release: your upload key, or with Play App Signing the "App signing key" (Play Console › App integrity).
Redeploy after editing.

## Limits and trust
- Durable Object free quota: 100k requests/day (incoming WebSocket messages count 20:1). Over it, the
  relay refuses until 00:00 UTC and the app keeps using HiveMQ.
- Node ids are not authenticated (same as MQTT topics). The Worker stamps each frame with the
  sender socket's own id, caps frames at 16 KB and 3 sockets per id. `/turn` is rate-limited
  (10/min per IP, 5/min per node) and caches one credential per node; a credential handed out can
  be used by whoever holds it until it expires (4 h), which bounds the cost of abuse.
