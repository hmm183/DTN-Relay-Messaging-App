# DTN Mesh Relay: feature checklist (2026-10-31)

✅ done and verified by unit tests + builds · 🟨 works but partial / can be better · ❌ not done

Verification: 457 unit tests pass; `assembleDebug` (35.7 MB) and R8 `assembleStaging` (21.5 MB) build;
the Worker bundles. **No feature below has been tested on two phones yet**: that is the next step.

## Messaging and delivery
| | Feature | Notes |
|---|---|---|
| ✅ | Sent ✓ / Delivered ✓✓ | A direct write = Sent; only the end-to-end receipt = Delivered; never downgraded (`markSentIfSending`); a relay publish stays Queued |
| ✅ | Messaging with Discover off | Direct announce before a DM (≤ 1/min), also over the relay `node/<hex>` |
| ✅ | DM before the sender's keys | Kept in memory and replayed when the keys arrive; signed "announce back" flag |
| ✅ | Replies | Quote + tap-to-scroll; travels inside the encrypted body (`ReplyCodec`), DB v5. Pre-update builds show the reply header as odd characters |
| ✅ | Delete for me | Chat row only; buffer row kept (dedup, carried copies, no unsend) |
| 🟨 | Notifications | Banner in app, tray otherwise, private on lock screen, invite keys hidden. No per-chat mute; every broadcast notifies |
| ✅ | Contact cards in chat | Add / Message; a shared card never replaces or verifies existing keys |
| ✅ | Group invites in chat | Unchanged from before |

## Routing
| | Feature | Notes |
|---|---|---|
| ✅ | Epidemic default, saved | Applied by the service itself (also when started without the UI) |
| ✅ | MaxProp secondary carriers | Lower-priority copy when the peer's own f(peer, dest) is above the floor |
| ❌ | PRoPHET exploratory spray fix | Plan item 8: never changed (only line endings) |
| 🟨 | Multi-hop A → relay → B → BLE → C | `who_sees` / `I_SEE` over both relays; answers are unauthenticated (bounded harm); not tested on devices |

## Internet
| | Feature | Notes |
|---|---|---|
| ✅ | Relay frame format | `[D7][01][num][wire]`, pinned by a golden test |
| 🟨 | Names in relay presence | Only with Discover on, only as a fallback label. Pre-update builds can't parse the named form: upgrade all phones together |
| ✅ | Broker username / password | Password Keystore-encrypted; no plain-TCP fallback when set |
| 🟨 | Second relay (Cloudflare Worker) | Code done, used in parallel with HiveMQ. Needs `wrangler deploy` + `dtn.workerHost`. Node ids unauthenticated (same as MQTT) |
| ✅ | Relay status | One indicator with a subtitle naming which relay is up |
| 🟨 | WebRTC direct (Settings › Internet direct) | Real data channels, signed + encrypted signaling, perfect negotiation, ≤ 4 sessions, 10 min idle. Experimental, off by default, untested on devices; a network switch drops the session until a relay re-negotiates |
| ✅ | Bring your own TURN | Default / Metered / coturn (static or shared secret) / JSON; per-provider encrypted slots; never sent to peers |
| 🟨 | Worker-minted TURN | Written, not deployed; Metered response field names to confirm with one curl (see `cloud/README.md`). The stop-gap default key is still in `local.properties` |

## Links and identity
| | Feature | Notes |
|---|---|---|
| ✅ | Link / code parser + Paste | `https://<host>/c#…`, `dtnid:`, `dtnid://`, percent-encoded; confirm screen before acting |
| ✅ | Share link (contact, group, own identity) | Falls back to the plain code without a Worker host; group-link warning |
| ✅ | Fallback web page | Self-hosted QR library, strict CSP, no referrer; working Open in app |
| 🟨 | App Links (tap to open) | Needs the deployed Worker, `dtn.workerHost`, and the second signing fingerprint in `assetlinks.json` |
| 🟨 | Name#tag handles + search | 4-hex tags are display-only and can collide; search must show full ids before acting |

## UI
| | Feature | Notes |
|---|---|---|
| ✅ | Swipe between tabs | One pager, one destination; Back → Network |
| ✅ | Badges | Chats with unread; our own Queued messages |
| ✅ | Wallpapers | 5 colours, doodle, gallery photo (private copy) |
| ✅ | Discover toggle on Network | |
| ✅ | Developer Tools in the top bar | |

## Platform and security
| | Feature | Notes |
|---|---|---|
| 🟨 | Samsung / Doze | Battery-exemption card + short wake locks. Play may question `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (text ready in `docs/play-store/play-console-forms.md`) |
| ✅ | DB v5 migration | `MigrationTest.migrate4To5…` written (instrumented: run on a device) |
| ✅ | No secrets in source | Build values from `local.properties` (git-ignored) |
| ✅ | Announce answers | Only to added contacts, or anyone while Discover is on |
| 🟨 | Unauthenticated relay metadata | Presence names, route answers, Worker node ids: bounded by design, documented |

## To do
| | Item |
|---|---|
| ❌ | Two-phone tests: Discover-off first DM, ✓ → ✓✓, A → B → C, swipe, notifications on Samsung, WebRTC up/down, links |
| ❌ | Deploy the Worker, set `dtn.workerHost`, add the second fingerprint, rotate the stop-gap TURN key |
| ❌ | Per-chat mute |
| ❌ | Delete the leftover `scratch/` folder (another tool's patch scripts) |
