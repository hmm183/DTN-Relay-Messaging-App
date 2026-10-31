# DTN Mesh Relay — project steering

Always-on context for this workspace. Keep it short and accurate; update it when
architecture or commands change.

## What this is
An off-grid **delay-tolerant-network (DTN)** messenger. Phones store-carry-forward
self-contained bundles over **BLE** (primary P2P), **WiFi Direct** (fallback), and a
custom **ESP32-S3 LoRa hub** (Heltec WiFi LoRa 32 V3). Hubs flood bundles hub-to-hub
over LoRa. An optional **internet relay** (MQTT, public HiveMQ by default) reaches contacts
anywhere. It is **independent of Meshtastic** — `docs/meshtastic-ref/` is read-only reference only.

Routing on phones (6 selectable): PRoPHET (RFC 6693), Stable PRoPHET, MaxProp (Dijkstra),
DiMaxProp (MaxProp + per-hop distance penalty, `routing/DiMaxPropStrategy.kt`, from the team's paper),
Epidemic, Double Q-learning (12-feature state incl. DiMaxProp cost C(B,D), hops, relay buffer; per-hop
progress reward η·ΔC; tables saved in `filesDir/q_tables_v2.bin`). The algorithm switch and λ / η are in
Settings › Developer Tools › Routing & Tuning. End-to-end delivery receipts let carriers drop custody copies.
Multi-hop (Phase 9): PRoPHET transitivity is RFC 6693's max form; MaxProp / DiMaxProp summaries also carry
the sender's own path cost to nodes further away (distance vectors) and nothing a neighbour reports is
copied into a phone's own f-table. The per-bundle carrier rules are pure in `service/CarrierRules.kt`
(`sendToSingle` and the Q-learning pass call them); `routing/MeshSim` + `MultiHopRoutingTest` run them
with one real strategy set per simulated phone (A → B → C → D → E, candidates F / G / H).
Default algorithm: Epidemic (saved). MaxProp (Oct 2026): when Dijkstra's next hop is another peer, the
evaluated peer still gets a lower-priority copy if its own reported f(peer, dest) is above the
exploratory floor (`f / (1 + cost)`), so one walking-away next hop no longer starves the bundle.
Relay-only peers never get another phone's DM from a strategy: only a route-query answer ("I see C
over radio", see wire-protocol.md › internet relay) makes one a carrier (A → relay → B → BLE → C).

## Layout
- `app/` — Android app, package `com.dtn.mesh`, Kotlin + Compose + Hilt + Room + WorkManager.
  - `service/DtnOrchestrator.kt` — core single-consumer event loop (send/receive/receipts/flush).
  - `receiver/` — transports (`BleTransportAdapter`, `WifiDirectTransportAdapter`,
    `LoRaHubTransportAdapter`), `MultiTransportManager`, `DtnWireCodec` (32-byte header).
    Wi-Fi Direct = invitation-free groups (Android 10+): `WifiDirectGroupPolicy` (pure election)
    + `WifiDirectHints` (group credentials in the BLE advert). Each transport publishes a
    `LinkInfo` (summary + peers) for its link page. `MeshTransport.reachesDirectly`
    (BLE / Wi-Fi Direct / hub-as-target) vs `isRadioReachable` (also phones on our hub's roster);
    `sendMessageVia(msg, peer, only)` forces a subset of the send chain, `directLinks(peer)` names them.
    `HubBeacon` / `HubBeaconRegistry` read the hubs' Bluetooth beacons.
  - `queue/` — `MessageQueueManager` (store-carry-forward buffer, Room-backed; also persists the
    chat row's delivered / expired / dropped status).
  - `service/MessageLifecycleLog.kt` — persistent message journal (events + per-message routes,
    `filesDir/message_journal_v1.log`). Only user DATA goes in it; control traffic → engine console.
    TTL expiry is journaled once per message by `ForwardingWorker` (`getExpiring` + `expireMessages`
    on one clock); a receipt this phone already had (hub replay, carrier re-push) isn't journaled again.
  - `service/DiagnosticsLog.kt` — the app's diagnostics log; `export/` — `DataExportManager` +
    `ExportModel` (Export Data), `ResearchExporter` (the CSV / JSON).
  - `routing/`, `learning/` — strategies + Q-learning (`BenchmarkTracker`). `database/` — Room.
  - `ui/` — Compose, Navigation Compose 2.8.5. `shell/DtnApp.kt` = one NavHost: the `tabs` destination
    (ONE `TabScaffold` with ONE `HorizontalPager`: Network (start) · Chats · Queue · Peers; swipe and the
    bottom nav only move the pager, never the back stack; Back on another tab → Network; badges = chats
    with unread / our own Queued messages), then `chat/{key}`, `contact/{node}`, `message/{id}`, `queued/{id}`, `groups`,
    `group/{channel}`, `groups/new`, `identity`, `settings`, `relay`, `transport/{kind}`, `dev`, `dev/{page}`
    and the Connection links sheet. Packages: `theme` (DtnTheme / DtnColors / DtnType, bundled `DtnIcons`),
    `components` (shared cards, rows, chips, buttons — reuse them), `network`, `chats`, `queue`, `peers`,
    `groups`, `identity`, `settings`, `links` (the 4 link pages), `devtools` (8 pages). Root `ui/`:
    `DtnViewModel` (the one shared state source), `MessageStatus.kt` — `deliveryState()`, the ONE status
    model for bubbles, the status sheet, the Queue, route rows and the Message page — `TransportChips.kt`
    (link health + tap rule), `CommonUi.kt` (snackbars, Before connecting, Clear buffer text), `UiText`, QR.
    Pure page logic is kept out of the composables (`QueueModel`, `ChatListModel`, `MessageTrace`,
    `DevToolsModels`, `PeerLists`, …) and has JVM tests.
- `firmware/` — Heltec V3 hub firmware (PlatformIO, single `src/main.cpp`), currently v5
  (`DTN_FW_VERSION` in `platformio.ini`) = v3 + a Bluetooth beacon (v4, NimBLE-Arduino 1.4.3, pinned)
  + v5: a late re-send of a DM whose receipt already passed the hub is dropped, not re-flooded.
  Every hub must run v3 or later (v2 hubs send nothing to phones); only v4+ hubs are listed under
  LoRa hub › Available hubs.
- `PROJECT_MEMORY.md` — living design + changelog (Parts A target / B done / C todo / E–L sessions;
  L = the UI redesign).
- `docs/dtn-hub-architecture.md` — architecture proposal. `docs/play-store/` — store listing,
  privacy policy, data safety, release checklist.

## Build & test (Windows PowerShell)
- App build: `./gradlew.bat :app:assembleDebug`
- Unit tests: `./gradlew.bat :app:testDebugUnitTest`  (JVM tests under `app/src/test`)
- Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
  (adb lives at `$env:USERPROFILE\AppData\Local\Android\Sdk\platform-tools\adb.exe`)
- Release (Play): `./gradlew.bat :app:bundleRelease :app:assembleRelease` — R8-minified, signed with
  the upload key from the git-ignored `keystore.properties` + `keystore/dtn-upload.jks` (unsigned if absent).
- R8 check on a phone: `./gradlew.bat :app:assembleStaging` → `com.dtn.mesh.staging`, debug-signed,
  installs next to the debug app (own data). Uninstall it after testing.
- Firmware build: `pio run` in `firmware/` (`pio` at `$env:USERPROFILE\.platformio\penv\Scripts\pio.exe`)
- Firmware flash: `pio run -t upload --upload-port COMxx` (one board at a time)
- Toolchain here: JDK 21, Gradle 8.13 wrapper, AGP 8.13.2, Kotlin 2.0.21, compileSdk/targetSdk 36
  (Play's requirement since 31 Aug 2026), minSdk 26.

## Rules / conventions
- Never use `fallbackToDestructiveMigration` for new schema changes — write a real Room
  `Migration` and a migration test. The buffer, chats, contacts and keys must survive upgrades.
- No secrets in source (no hard-coded Wi-Fi passphrases/keys in committed code once settings land).
- Encryption is end-to-end: encrypt at the origin, decrypt only at the destination. Relays,
  hubs and the internet relay must stay payload-agnostic (header-only routing).
- TTL is relative minutes and must only ever count DOWN across hops; never refresh it.
- "Delivered" (✓✓) on a DM only from the destination's end-to-end receipt (ROUTING_ACK). Since Oct
  2026 a successful direct BLE / Wi-Fi Direct write to the destination is only **Sent** (✓,
  `ChatDao.markSentIfSending`: SENDING / BUFFERED → SENT, never downgrading Delivered): the receiver
  may still be unable to decrypt it. A relay PUBACK, a hub flood or a hand-off to a phone on the
  same hub (`SendPath.HUB_LOCAL`) never changes the status (stays Queued until the receipt).
- Delete for me (`ChatDao.deleteMessage`): removes only this phone's chat row. The buffer row is
  kept on purpose — it dedups later copies (a forgotten broadcast would be re-accepted and re-shown),
  carried copies keep travelling, and our own pending DM is still delivered (no unsend).
- The "N queued" counters, the Queue's Pending / Forwarded / Delivered / History segments and Buffer
  History show only DMs. Broadcasts and group messages (`^all`) spread until their TTL and are listed
  only in the Queue's **Broadcasts & groups** segment (TTL left, phones reached). Control traffic
  (announces, receipts, summaries, our own group receipts) never appears in the Queue: only `DATA`
  (in SQL and in `QueueModel`; our group-receipt DMs are `portNum` 257 locally, excluded in SQL).
- DM fallback (`service/DmFallback.kt`, pure; `DtnOrchestrator.fallbackPass`): a DM (own or carried)
  queued ≥ 5 s on this phone (`DtnMessage.bufferedAtMs`) without delivery keeps trying its direct links
  and ALSO goes to every other path that is on — our hub (`HUB_LOCAL` when the destination is on its
  roster, else a flood, once per hub session) and the relay (destination present; publish, stay
  buffered until the receipt, 30 s window; a carried DM for a phone that isn't a contact here gets the
  relay from the fallback only). A peer's own pass sends DATA DMs (and custody copies) only
  over its tier: BLE → Wi-Fi Direct at once, `HUB_LOCAL` for a roster-only phone, the relay for a
  relay-only one — no fall-through. A fallback pass is scheduled at the 5 s mark (coalesced; it doesn't
  wait for a busy flush), then a flush. Never switches a
  link on; broadcasts / groups unaffected; fallback sends don't train routing or Q-learning and aren't
  benchmark forwards. Journal "fallback after BLE: …", route `fallbackVia` / `fallbackAfter` → the
  sheet says "Sent via Internet relay (fallback after BLE)"; diagnostics `routing` "fallback 1a2b3c4d → …".
- Relay status (fix 1, `receiver/RelayLink.kt`): ONE model, `RelayStatus` = Off · Waiting for internet ·
  Connecting… (only while an attempt is in flight) · Retrying in N s · Connected · <endpoint> · Couldn't
  reach the relay · Retry; every surface (tile, status card, Connection links, relay page, Relay
  Configuration, Settings) uses `ui/RelayStatusUi.kt` `relayStatusText()`. `RelayLinkMachine` (pure)
  decides every retry: no HiveMQ automatic reconnect, back-off 2/4/8/15/30/60 s + next endpoint, gives up
  after 10 in a row (or a fatal CONNACK), a dropped session retried on the SAME endpoint after 1 s, a 30 s
  attempt watchdog. No validated default network (`RadioStatusMonitor`, INTERNET + VALIDATED; the hub's
  SoftAP never counts) → Waiting, no attempts; when it returns or the network changes
  (`internetNetworkId`) → a fresh run at once (DNS, first endpoint). All on one control thread; each state
  change is one `relay` diagnostics line (with the broker's DISCONNECT / CONNACK reason code).
- BLE client links (fix 1, `receiver/BleLinkRules.kt`): ONE GATT client link per peer phone (`LinkTable`,
  keyed by node, callbacks matched by their GATT object), reused for every bundle while it is up even after
  the peer's address changes; an older link to the same phone is always closed (leaked links to rotated
  addresses piled up → status 133 for minutes). `TRANSPORT_LE`; a failed connect is retried once (400 ms)
  within the 8 s budget; no MTU answer in 2 s → 20 B chunks (one MTU request per link: CONNECTED can be
  reported twice); the peer's cached GATT table is dropped (`refresh()`) before each discovery; one send
  sequence per peer (mutex). Per-phone results + link role on the Bluetooth page.
- BLE one link per phone pair (fix 3, `BleRoles`, `ServerLinkTable`, `BleHello`, `FrameAssembler`,
  `DirectLinkChoice` in `BleLinkRules.kt`): only the LOWER node id connects (central, in the background,
  on sighting); the higher id waits 3 s and connects itself only if no link appeared; never a client link
  to a phone linked with us in either role; duplicates → the lower id's link stays. The central subscribes
  to the link characteristic (indications) and writes HELLO (its node id); the peripheral then sends over
  that link as indications (one chunk in flight, ATT-confirmed = delivered, like a write with response).
  A BLE send uses a link that is up NOW or returns null at once: `MultiTransportManager` then tries
  Wi-Fi Direct immediately; `BleTransportAdapter.linkReady` → `MeshTransport.linkReady` → the orchestrator
  clears the peer's back-off and flushes. Per-phone connect back-off 1/2/4/8/16/30 s, reset when a link
  comes up. Only a send failure that took ≥ 1 s backs the peer off in the flush; routing summaries go over
  BLE / Wi-Fi Direct only; "Slow flush pass" is logged above 2 s. Diagnostics `ble`: role decisions,
  "Link up/down with X … (central | peripheral)", "Sent DATA 1a2b3c4d (135 B) to X as central in 40 ms".
  Older builds (no link characteristic): write-only client link, both sides connect as before.
- Wi-Fi Direct joins (fix 2): the owner advertises its group's channel (BLE hint byte 9, TXT `f`) and
  joiners scan only that channel; join timeout 12 s (was 30) with a 2 s retry, falling back to every
  channel; one password nonce per app run (a re-created group keeps its password); a join whose owner
  re-created / stopped its group restarts at once; searching ticks every 1 s; a new owner skips
  discovery for 20 s.
- Carrier copies: a mule copy handed over BLE / Wi-Fi Direct (link-acknowledged) goes to that carrier once
  per session (`HoldReason.CARRIER_HAS_IT`, `CarrierRules.Facts.handedOverThisSession`), not every 30 s;
  re-offered after the carrier goes offline and comes back. Q-learning makes no new decision for it either.
- Group receipts (`service/GroupReceipts.kt`): a member that reads a group message for the first time
  (not its own, not from a blocked contact) sends the origin an ordinary encrypted DM whose plaintext
  starts with 0xA7 (never text), batched per (origin, channel) for 3 s, ≤ 12 ids, TTL ≥ 30 min. Its
  own DM receipt clears carriers. A group message NEVER gets a ROUTING_ACK. The origin stores member,
  time and link in the journal route (`groupReceipts`); known members = invited (DataStore
  `group_invited:<ch>`, cleared on Leave) ∪ posters ∪ receipt senders − me − blocked. `deliveryState()`
  → `RECEIVED_BY_SOME` / `RECEIVED_BY_ALL` (`GroupProgress`); bubble "✓ Received by 1 of 2" /
  "✓✓ Received by all (2)" / "✓ Spreading"; the sheet and the Message page list each member (✓ time ·
  via link, or Not yet). The public Broadcast (channel 0) has no receipts. Older builds show a group
  receipt as garbage text: every phone needs the Oct 2026 Phase 8 build.
- The Message page (`message/{id}`, "Message details") shows the routing decision SAVED at forward time
  (`RouteRecord.decision`), never a live recomputation with the currently selected algorithm. Live
  "who would carry it now" math is only on the Queued message page (`queued/{id}`).
- Tapping / long-pressing a chat bubble opens the simple status sheet: status, sent / delivered times,
  one "Received via / Delivered via / Sent via BLE · Wi-Fi Direct · LoRa hub (hub A9B0) · Internet relay"
  line (`chats/TravelText.kt`, from `RouteRecord`), Copy text, and "Full trace in Developer Tools" → the
  Message page (also opened from the Queue, Event Log, Buffer History and Routing Engine).
- The algorithm switch exists only in Developer Tools › Routing & Tuning (applies at once and is saved,
  DataStore `routing_algorithm`, default **Epidemic**; the orchestrator applies the saved value on
  `start()`, so it holds when the service starts without the UI; Reset = Epidemic, λ 0.50, η 0.20).
  Developer Tools also has a top-bar shortcut. The Network status card shows the active algorithm
  read-only. Settings that would break the mesh are read-only facts.
- Notifications (`service/MessageNotifier.kt`, called from `persistInboundChat`): nothing for the chat
  that is open; an in-app banner (latest wins, 3.5 s) while the app is in the foreground; otherwise a
  tray notification per chat (`dtn_messages_channel`, lock screen shows only "New message", tap opens
  the chat). Names follow `PeerInfo.displayName()`; a group invite DM shows "Group invite · name",
  never its key. Broadcast messages are stored under `NodeId.BROADCAST` but the UI chat key is
  `BROADCAST_CHAT_ID`: the notifier maps it.
- Chat wallpaper (`chats/ChatWallpaper.kt`, Settings › Chat Appearance): 5 solid colours, a drawn
  doodle pattern, or a gallery photo copied (≤ 1600 px JPEG) into `filesDir` (DataStore
  `chat_theme` / `chat_wallpaper_path`), drawn with a 45 % dark scrim.
- WebRTC direct (`receiver/webrtc/WebRtcTransportAdapter.kt`, Settings › Internet direct, off by
  default; `io.getstream:stream-webrtc-android`, arm64-v8a + armeabi-v7a, R8 keep rules): opened
  only by a DM to an added contact over the relay (`MultiTransportManager` → `maybeConnect`, 1/min
  per contact, not with battery saver), ≤ 4 sessions (LRU), 10 min idle / 30 s stuck close. A
  verified session is a direct link: `TransportKind.WEBRTC`, `SendPath.WEBRTC` in `DIRECT_TIER`
  (after BLE / Wi-Fi Direct, before hub / relay), so custody, summaries and encounters work as on
  BLE; Sent on write, Delivered on receipt. `isRadioReachable` includes it; `isPhysicallyReachable`
  (route-query answers) does not. Native-library failure turns the setting off.
- TURN (`receiver/webrtc/TurnConfigManager.kt`, Settings › TURN server): each phone builds its
  RTCPeerConnection from its OWN provider (Default = Worker `/turn`, then the build's stop-gap
  Metered key, then public STUN; Metered; coturn static or shared-secret; JSON). Per-provider
  Keystore-encrypted slots, never in signaling. Falling back to the default is shown on the page.
- Build values (`app/build.gradle.kts`, from git-ignored `local.properties`): `dtn.workerHost`
  (Worker relay, App Links host, share links; empty = off), `dtn.defaultTurn*` (stop-gap only).
- Links (`crypto/DeepLinkParser.kt`, `crypto/LinkGenerator.kt`): one payload format (`dtnid:` /
  `dtngrp:`), carried as `https://<workerHost>/c#…` / `/g#…` or plain text; incoming links always go
  through a confirm screen; a shared contact never replaces or verifies keys
  (`ContactKeyManager.applySharedContactKeys`). Cloudflare side: `cloud/` (see its README).
- Feature status: `docs/FEATURES.md`.
- Background (Samsung / Doze): Network shows a "Keep running in the background" card while the app is
  battery-optimised (`BatteryOptimization`, permission `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`); relay
  connect attempts and publishes hold short timed wake locks (`WakeLocks`, never a session-long lock).
- Chat list = contacts with `is_added` (QR, Add, a DM either way); other announced phones are under
  Peers › Nearby devices › Discovered. Discover (broadcast announces) is off by default (toggle in
  Settings and on the Network page). DB is v5 (`AutoMigration 3→4`, `4→5` = `chat_messages.reply_to_id`
  / `reply_snippet`; instrumented `MigrationTest` under `app/src/androidTest`:
  `./gradlew.bat :app:connectedDebugAndroidTest`; it uninstalls the app, wiping its data).
  Unread times, blocked / deleted contacts and key-change state are DataStore (`SettingsRepository`), not Room.
- Delete contact: the chat is deleted, `is_added = 0` and our queued direct announces to them are
  dropped; the contact row (keys, routing stats) stays so relaying works, and a deleted-at time hides
  them from Discovered / Nearby until a newer announce. A later DM from them decrypts and re-adds them.
- Block (a node-id set in DataStore): their DMs are decrypted only to check they're genuine, the
  delivery receipt is still sent, then the DM is dropped (not stored, never auto-added). No direct
  announces go to them (queued ones dropped); their announces are ignored (still relayed) and their
  group / broadcast posts hidden. Relaying their bundles for others is unchanged (header-only).
  Unblock: Settings › Privacy › Blocked contacts or the contact page.
- Benchmarks (`learning/BenchmarkTracker`, in memory since app start / Reset): Handled = DM bundles
  this phone created or carried, once per id; Delivered = handled ones seen delivered (direct
  hand-over or end-to-end receipt), credited to the algorithm selected when the bundle was handled;
  rate = Delivered ÷ Handled; Forwards = DM forwards, credited to the algorithm that made them;
  overhead = forwards per delivery. DMs addressed to this phone and broadcast copies don't count.
- Diagnostics log (`service/DiagnosticsLog`): every app log line goes through it with a `DiagLevel` and a
  `DiagSource`; last 2,000 lines in memory, `filesDir/diagnostics_v1.log` rotating at 2 MB to one `.1`
  backup, one writer thread. Developer Tools › Console shows it (App source = the UI's lines). Ids,
  sizes and counts only — never message text, keys or invites.
- Export Data (`export/DataExportManager`, IO dispatcher): dataset switches + date range → one folder
  under `Android/data/com.dtn.mesh/files/exports/`; **Export all logs** = the diagnostics log (+ `.1`),
  engine console, message journal and this app's own logcat (`--pid`, ≤ 8 MB). Share zips a folder via
  the FileProvider. Nothing is uploaded and no export holds message text.
- Distance (BLE RSSI → metres) is only estimated for BLE encounters; Wi-Fi Direct / hub signal values
  are placeholders. Phones seen only through the hub roster don't train the routing tables.
- Hub Bluetooth beacons (firmware v4+; manufacturer data 0xFFFF + "DH", `receiver/HubBeacon.kt`) only
  feed LoRa hub › Available hubs and auto-join: never a GATT connect, an encounter or routing-table
  training. Join asks for that hub's exact SSID (`DTN-HUB-` + last 4 hex of its id); auto-join asks for
  the strongest hub heard in the last 15 s, else any `DTN-HUB-*`. v3 hubs still work, just unlisted.
- Group invites to picked members are ordinary encrypted DMs whose text is the invite; the chat shows
  a Join group card and joining is always a tap (`joinGroupFromInvite`, conflict rule kept). Group names
  are capped at 151 UTF-8 bytes so the invite fits one DM frame. Never log an invite (it holds the key).
- First launch shows only when no display name was saved at start. Link details are the link pages
  (`transport/{kind}`). The old tabbed `MainScreen` and its dialogs are gone (Oct 2026 redesign); new
  UI goes into the `ui/` packages above, from `docs/ui-redesign/` (architecture, visual design, plan).
- One identity per phone: the node id = SHA-256("DTN-node-v1|" + `ANDROID_ID` + "|" + package)
  (`crypto/NodeIdDerivation.kt`, never 0 / 0xFFFFFFFF). It survives a data clear / reinstall, the keys
  don't. So this phone's pre-clear identity has OUR node id: its circulating announces are dropped as
  echoes, and an announce carrying our own keys is ignored (no data-clear ghosts). Receivers switch keys
  only for a newer signed announce (`service/ContactRules.kt` `KeyChangeRules`); QR-verified contacts
  need Accept or a QR re-scan (DMs to them are `HELD` meanwhile). Only a phone without a usable
  ANDROID_ID (stored id, new after a clear) still ignores announces issued before `DtnIdentity.createdAtMs`.
- Never block the main thread on network I/O (the MQTT publish-on-disconnect ANR). Never log
  message text. Never commit `keystore/`, `keystore.properties` or `dist/`.
- Match the existing style of the file you edit. Prefer dedicated tools over shell for file edits.
- `docs/meshtastic-ref/**` is vendored reference — do not edit or build it.
