/**
 * Cloudflare Worker + Durable Object for DTN Mesh Relay (Layer A), WebRTC Signaling,
 * and Server-Side Expiring TURN Credential Minting with Rate Limiting.
 */

/** Bundles are <= 223 B wire + headers; anything bigger is not ours. */
const MAX_FRAME_BYTES = 16 * 1024; // a signed + encrypted full SDP can reach ~6 KB
const MAX_SOCKETS_PER_NODE = 3;

export interface Env {
  RELAY_ROOM: DurableObjectNamespace;
  ASSETS: Fetcher;
  METERED_SECRET_KEY?: string;
  METERED_DOMAIN?: string;
}

const PUBLIC_STUN = [{ urls: "stun:stun.cloudflare.com:3478" }, { urls: "stun:stun.relay.metered.ca:80" }];
const TURN_TTL_SEC = 4 * 3600;

/**
 * Metered "Create TURN Credential": POST https://<app>.metered.live/api/v1/turn/credential?secretKey=…
 * with {expiryInSeconds, label}; the response carries username + password. Verify once with:
 *   curl -X POST "https://<app>.metered.live/api/v1/turn/credential?secretKey=<SECRET>" \
 *        -H "Content-Type: application/json" -d '{"expiryInSeconds":600,"label":"test"}'
 */
async function mintMetered(env: Env, nodeHex: string): Promise<CachedTurn | null> {
  const resp = await fetch(
    `https://${env.METERED_DOMAIN}/api/v1/turn/credential?secretKey=${encodeURIComponent(env.METERED_SECRET_KEY!)}`,
    { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ expiryInSeconds: TURN_TTL_SEC, label: `dtn-${nodeHex}` }) },
  );
  if (!resp.ok) return null;
  const c: any = await resp.json();
  const u = c?.username, p = c?.password;
  if (typeof u !== "string" || typeof p !== "string") return null;
  const host = "global.relay.metered.ca";
  return {
    expiresAtMs: Date.now() + TURN_TTL_SEC * 1000,
    iceServers: [
      { urls: "stun:stun.relay.metered.ca:80" },
      { urls: `turn:${host}:80`, username: u, credential: p },
      { urls: `turn:${host}:80?transport=tcp`, username: u, credential: p },
      { urls: `turn:${host}:443`, username: u, credential: p },
      { urls: `turns:${host}:443?transport=tcp`, username: u, credential: p },
    ],
  };
}
interface CachedTurn {
  iceServers: any[];
  expiresAtMs: number;
}

export class RelayRoom implements DurableObject {
  private state: DurableObjectState;
  private env: Env;
  private turnCache: Map<string, CachedTurn> = new Map();
  private ipRequests: Map<string, number[]> = new Map();
  private nodeRequests: Map<string, number[]> = new Map();

  constructor(state: DurableObjectState, env: Env) {
    this.state = state;
    this.env = env;
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);

    // 1. WebSocket Relay Endpoint
    if (url.pathname === "/ws") {
      const upgrade = request.headers.get("Upgrade");
      if (!upgrade || upgrade.toLowerCase() !== "websocket") {
        return new Response("Expected Upgrade: websocket", { status: 426 });
      }

      const nodeHex = url.searchParams.get("node")?.toLowerCase();
      if (!nodeHex || !/^[0-9a-f]{8}$/.test(nodeHex)) {
        return new Response("Invalid node hex", { status: 400 });
      }

      // At most MAX_SOCKETS_PER_NODE sockets claim one node id: the oldest is closed.
      const existing = this.state.getWebSockets(nodeHex);
      for (let i = 0; i <= existing.length - MAX_SOCKETS_PER_NODE; i++) {
        try { existing[i].close(4000, "replaced by a newer connection"); } catch (_) {}
      }

      const pair = new WebSocketPair();
      const [client, server] = Object.values(pair);

      // Hibernation API: server socket is tagged with the node's hex ID
      this.state.acceptWebSocket(server, [nodeHex]);

      // Notify others of this node joining
      this.broadcastPresence(nodeHex, true, server);

      // Send the connecting node the current list of online nodes
      const onlineNodes = this.getOnlineNodes();
      const roster = JSON.stringify({ type: "roster", nodes: onlineNodes });
      server.send(roster);

      return new Response(null, { status: 101, webSocket: client });
    }

    // 2. Server-side TURN minting (the Metered SECRET key never leaves Cloudflare)
    if (url.pathname === "/turn") {
      const nodeHex = url.searchParams.get("node")?.toLowerCase() ?? "";
      const json = (body: unknown, status = 200, extra: Record<string, string> = {}) =>
        new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", "Cache-Control": "no-store", ...extra } });
      if (!/^[0-9a-f]{8}$/.test(nodeHex)) return json({ error: "node required" }, 400);
      // Only a phone currently connected to this relay may mint (cheap gate against drive-by
      // quota abuse; node ids are not authenticated, so this is a speed bump, not auth).
      if (this.state.getWebSockets(nodeHex).length === 0) return json({ error: "connect to the relay first" }, 403);
      const ip = request.headers.get("CF-Connecting-IP") || "unknown";
      if (!this.checkTurnRateLimit(ip, nodeHex)) return json({ error: "rate limited" }, 429, { "Retry-After": "60" });

      const now = Date.now();
      const key = "turn:" + nodeHex;
      const cached = await this.state.storage.get<CachedTurn>(key);
      // Reuse until 30 min before expiry: a NEW Metered credential can take ~2 min to work everywhere.
      if (cached && cached.expiresAtMs - now > 30 * 60_000) return json({ status: "ok", iceServers: cached.iceServers });

      if (this.env.METERED_SECRET_KEY && this.env.METERED_DOMAIN) {
        const minted = await mintMetered(this.env, nodeHex).catch(() => null);
        if (minted) {
          await this.state.storage.put(key, minted);
          return json({ status: "ok", iceServers: minted.iceServers });
        }
        if (cached && cached.expiresAtMs > now) return json({ status: "ok", iceServers: cached.iceServers });
      }
      return json({ status: "ok", iceServers: PUBLIC_STUN });
    }
    return new Response("Not found", { status: 404 });
  }

  private checkTurnRateLimit(ip: string, nodeHex?: string): boolean {
    const now = Date.now();
    const windowMs = 60_000;

    // IP rate limit: max 10 requests per minute
    if (this.ipRequests.size > 5000) this.ipRequests.clear(); // bound memory
    if (this.nodeRequests.size > 5000) this.nodeRequests.clear();
    const ipTimestamps = (this.ipRequests.get(ip) || []).filter(t => now - t < windowMs);
    if (ipTimestamps.length >= 10) return false;
    ipTimestamps.push(now);
    this.ipRequests.set(ip, ipTimestamps);

    // Node rate limit: max 5 requests per minute
    if (nodeHex) {
      const nodeTimestamps = (this.nodeRequests.get(nodeHex) || []).filter(t => now - t < windowMs);
      if (nodeTimestamps.length >= 5) return false;
      nodeTimestamps.push(now);
      this.nodeRequests.set(nodeHex, nodeTimestamps);
    }

    return true;
  }

  async webSocketMessage(ws: WebSocket, message: ArrayBuffer | string): Promise<void> {
    if (typeof message === "string") {
      try {
        const parsed = JSON.parse(message);
        if (parsed.type === "ping") {
          ws.send(JSON.stringify({ type: "pong" }));
        }
      } catch (_) {}
      return;
    }

    // Binary framing:
    // Byte 0: Kind (0 = Node Direct, 1 = Broadcast, 2 = Presence, 3 = Route Query, 4 = Route Reply, 5 = WebRTC Signal)
    // Bytes 1..8: Target Node Hex (for Direct, Route Reply, WebRTC)
    // Remaining bytes: DTN Wire Payload ([0xD7][0x01][4B publisher][payload...])
    const bytes = new Uint8Array(message);
    if (bytes.length < 1 || bytes.length > MAX_FRAME_BYTES) return;

    const kind = bytes[0];
    const senderHex = this.state.getTags(ws)[0];
    if (!senderHex) return;
    const payloadOff = kind === 1 || kind === 2 ? 1 : 9;
    if (kind === 5) {
      if (bytes.length < 17) return;
      bytes.set(new TextEncoder().encode(senderHex), 9);
    } else if (bytes.length >= payloadOff + 6 && bytes[payloadOff] === 0xd7 && bytes[payloadOff + 1] === 0x01) {
      const num = parseInt(senderHex, 16) >>> 0;
      bytes[payloadOff + 2] = (num >>> 24) & 0xff;
      bytes[payloadOff + 3] = (num >>> 16) & 0xff;
      bytes[payloadOff + 4] = (num >>> 8) & 0xff;
      bytes[payloadOff + 5] = num & 0xff;
    }
    message = bytes.buffer;

    switch (kind) {
      case 0: // Node Direct
      case 4: // Route Reply
      case 5: // WebRTC Signaling
        if (bytes.length < 9) return;
        const targetHex = new TextDecoder().decode(bytes.slice(1, 9)).toLowerCase();
        const targets = this.state.getWebSockets(targetHex);
        for (const target of targets) {
          try { target.send(message); } catch (_) {}
        }
        break;

      case 1: // Broadcast
      case 2: // Presence
      case 3: // Route Query
        for (const socket of this.state.getWebSockets()) {
          if (socket !== ws) {
            try { socket.send(message); } catch (_) {}
          }
        }
        break;
    }
  }

  async webSocketClose(ws: WebSocket, code: number, reason: string, wasClean: boolean): Promise<void> {
    const tags = this.state.getTags(ws);
    const nodeHex = tags[0];
    if (nodeHex) {
      const remaining = this.state.getWebSockets(nodeHex);
      if (remaining.length <= 1) {
        this.broadcastPresence(nodeHex, false, ws);
      }
    }
    ws.close(code, reason);
  }

  async webSocketError(ws: WebSocket, error: unknown): Promise<void> {
    ws.close(1011, "WebSocket error");
  }

  private broadcastPresence(nodeHex: String, online: boolean, excludeWs?: WebSocket): void {
    const presenceMsg = JSON.stringify({ type: "presence", node: nodeHex, online });
    for (const socket of this.state.getWebSockets()) {
      if (socket !== excludeWs) {
        try { socket.send(presenceMsg); } catch (_) {}
      }
    }
  }

  private getOnlineNodes(): string[] {
    const nodes = new Set<string>();
    for (const ws of this.state.getWebSockets()) {
      const tags = this.state.getTags(ws);
      if (tags[0]) nodes.add(tags[0]);
    }
    return Array.from(nodes);
  }
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    // 1. WebSocket Relay Endpoint
    if (url.pathname === "/relay") {
      const id = env.RELAY_ROOM.idFromName("global");
      const stub = env.RELAY_ROOM.get(id);
      url.pathname = "/ws";
      return stub.fetch(new Request(url.toString(), request));
    }

    // 2. Rate-limited TURN credentials via Durable Object
    if (url.pathname === "/turn") {
      const id = env.RELAY_ROOM.idFromName("global");
      const stub = env.RELAY_ROOM.get(id);
      return stub.fetch(request);
    }

    // 3. Static Assets (.well-known/assetlinks.json, index.html, /c, /g)
    if (url.pathname === "/c" || url.pathname === "/g") {
      // "/" not "/index.html": Workers assets redirect /index.html -> / (html_handling).
      url.pathname = "/";
      return env.ASSETS.fetch(new Request(url.toString(), request));
    }

    return env.ASSETS.fetch(request);
  }
};
