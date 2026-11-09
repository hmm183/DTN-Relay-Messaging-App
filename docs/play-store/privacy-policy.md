# DTN Mesh Relay — Privacy Policy

*Effective Date: 31 October 2026*  
*Publisher: DTN Mesh Relay Team (Vrishank Raina, Raushan Shrivastwa, Vishwanath Tiwari, Priyanshu Upadhyay)*  
*Contact: dtnmesh.relay@gmail.com | Project Repository: https://github.com/hmm183/DTN-Relay-Messaging-App*

DTN Mesh Relay ("the app") is a decentralized, off-grid peer-to-peer (P2P) delay-tolerant messenger. Messages travel directly between Android devices, optional ESP32 LoRa hardware hubs, and an optional dual internet relay fallback. 

The application publisher runs **no data-collection servers** and receives **no personal data, telemetry, or analytics** from your device. There are no user accounts, third-party advertising SDKs, tracking frameworks, or proprietary crash-reporting services embedded in the app.

---

## 1. Data Stored Locally on Your Device

All critical data remains on your physical phone:

- **Identity & Keys:** A cryptographic key pair generated locally (X25519/Ed25519), your mesh Node ID, and an optional display name you choose. The Node ID is a deterministic hash of Android's app-specific device ID (`ANDROID_ID`) and the application package name (`com.dtn.mesh`), giving each device a consistent mesh address that survives app storage clears and reinstalls, resetting only upon a full factory reset of the phone.
- **Contacts & Safety Numbers:** Public keys, Node IDs, user-assigned contact names, safety verification status, and blocklists of nodes you meet or scan via QR code.
- **Message Store & Store-Carry-Forward Buffer:** Your private chats, queued outbound messages, and transient bundles carried on behalf of other mesh users (which are end-to-end encrypted; intermediate carrier nodes cannot read their contents).
- **Routing & Diagnostics Journal:** Encounter frequency logs, link quality history (RSSI/SNR), and localized routing statistics (PRoPHET / MaxProp / Double Q-learning Q-tables).
- **Custom Wallpapers:** Any wallpaper selected via the Android system photo picker or created on the in-app doodle canvas is stored solely in private app sandbox storage in cropped/downscaled form.

> **Data Isolation:** All local database storage is explicitly excluded from Android cloud backups (`android:allowBackup="false"`) and Android 12+ device-to-device migration (`dataExtractionRules`). Uninstalling the app permanently purges all local databases, message queues, and encryption keys.

---

## 2. Data Transmitted Off Your Device

The app only transmits data across physical radio interfaces (Bluetooth Low Energy, Wi-Fi Direct, LoRa Hub) or internet relays when actively participating in the mesh:

| Data Type | Recipients | End-to-End Encrypted? | Purpose |
|---|---|---|---|
| **Direct & Group Messages** | Recipient and intermediate carrier phones / LoRa hubs / relays | **Yes** (X25519 + AES-256-GCM) | User communication. Intermediate nodes and relay brokers cannot read message payloads. |
| **Broadcast Messages** ("Broadcast to All") | Any device in radio reach or connected to the relay | **No** (Public by design) | Public emergency announcements or open channel communication. |
| **Node Announce & Identity** | Nearby mesh peers, LoRa hubs, and relay broker | **No** (Signed public keys) | Necessary to exchange public keys and establish secure sessions. |
| **Packet Routing Headers** | Carrier nodes, LoRa hubs, and relay brokers | **No** (Routing metadata) | Contains message UUID, sender Node ID, destination Node ID, TTL, and hop count required for routing. |
| **Delivery Receipts (`✓✓`)** | Reverse-path carrier nodes back to the original sender | **No** (Message UUID only) | Acknowledges successful end-to-end bundle delivery. |
| **Online Presence & Display Name** | Connected relay broker and peers | **No** (Only when Internet Relay & Discover are enabled) | Displays online/offline reachability on internet fallback. |
| **Multi-Hop Route Queries (`who_sees`)** | Connected relay broker and peers | **No** (Target Node ID only) | Locates peers with radio connectivity to off-grid nodes. |
| **IP Addresses (Direct WebRTC)** | Directly connected peer and TURN server | **No** (Direct socket / ICE candidates) | Exchanged only when "Internet Direct" mode is explicitly enabled by the user. |

---

## 3. Third-Party Services (Optional Internet Mode)

When the optional internet relay is toggled on in Settings:

1. **MQTT Relay Broker:** By default, connects to the public broker `broker.hivemq.com` (operated by HiveMQ GmbH) or a custom MQTT broker defined by the user. HiveMQ processes network IP addresses and connection metadata according to its standard privacy policy. No plaintext of encrypted messages is ever visible to the broker.
2. **Cloudflare Worker Relay:** Connects to the DTN WebSocket relay (`dtnmesh.vrishankrainaphotos1.workers.dev` on Cloudflare, Inc.'s global network) for roster and message signaling. Cloudflare processes transport IP addresses ephemerally.
3. **TURN Server (WebRTC Direct):** If using Internet Direct, NAT traversal may utilize Metered, Inc. or custom user-configured TURN servers. Only encrypted streams and IP addresses pass through the TURN relay.

---

## 4. Android Device Permissions & Justification

- **Bluetooth (`BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`):** Required for P2P mesh discovery, GATT server hosting, and opportunistic message exchange with nearby phones. Scans use the `neverForLocation` flag.
- **Nearby Devices / Wi-Fi (`NEARBY_WIFI_DEVICES`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`):** Required for high-throughput Wi-Fi Direct transfers and for connecting to standalone ESP32 LoRa Hub SoftAPs (`DTN-HUB-*`).
- **Location (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`):** Required only on legacy Android versions (Android 12L / API 32 and older) by the Android OS framework to permit BLE scanning. **The app never reads, tracks, or transmits GPS coordinates or geographic location.**
- **Camera (`CAMERA`):** Optional. Used strictly to scan contact and group invite QR codes. No photos or video streams are recorded or stored.
- **Notifications (`POST_NOTIFICATIONS`):** Used to display the persistent Foreground Service status indicator and incoming message alerts.
- **Battery Optimization Exemption (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`):** Optional. Requested once so the store-and-forward engine and internet relay can maintain opportunistic background synchronization when the screen is turned off.
- **Internet (`INTERNET`, `ACCESS_NETWORK_STATE`):** Used solely for the optional MQTT and Cloudflare Worker internet relays.

---

## 5. Research Data & Diagnostics Export

Users can optionally export mesh performance and reinforcement learning diagnostics via **Settings › Developer Tools › Export Data**:
* Exported files (`encounters.csv`, `contacts.csv`, `decisions.csv`, `messages.csv`, `q_tables.json`) record topological timings, RSSI, hop counts, and Q-values.
* **No message text, private cryptographic keys, or conversation contents are ever written to research logs or diagnostics exports.**
* Export archives are saved directly to the app's sandboxed storage (`Android/data/com.dtn.mesh/files/exports/`) and leave your phone only if you explicitly choose to share the file via the Android Share Sheet.

---

## 6. Data Retention & User Deletion Rights

* **Carried Bundles:** Messages held in the store-and-forward buffer expire automatically after their TTL window (default: 4 hours) and are permanently purged.
* **User Control:** Users can at any time clear the forwarding queue, delete individual contacts, delete chats ("Delete for me"), or uninstall the app.
* **Complete Erasure:** Because the publishers maintain no centralized databases or user accounts, uninstalling the application or clearing its app storage in Android Settings completely and irreversibly erases all local data.

---

## 7. Children's Privacy

The application is not directed at children under the age of 13. We do not knowingly collect or solicit personal information from children.

---

## 8. Policy Updates

Any modifications to this Privacy Policy will be reflected with an updated effective date. The latest version is maintained within the application documentation and hosted on the project web landing page.

---

## 9. Contact & Inquiries

For technical questions, privacy inquiries, or source verification, contact:
* **Team Email:** dtnmesh.relay@gmail.com
* **Project Repository:** https://github.com/hmm183/DTN-Relay-Messaging-App
* **Development Team:** Vrishank Raina, Raushan Shrivastwa, Vishwanath Tiwari, Priyanshu Upadhyay
