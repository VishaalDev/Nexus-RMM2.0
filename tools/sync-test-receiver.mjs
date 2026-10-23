#!/usr/bin/env node
/**
 * Nexus-RMM sync TEST RECEIVER - a development aid, NOT part of the product.
 *
 * Nexus-RMM has no built-in remote server and never pretends to have one.
 * This tiny, dependency-free script implements the documented contract
 * (docs/sync.md) so you can see real end-to-end synchronization on one
 * machine. It stores what it receives in a JSON-lines file and acknowledges
 * ONLY records it has actually written.
 *
 *   node tools/sync-test-receiver.mjs [--port 9090] [--token SECRET]
 *        [--out received.jsonl] [--fail-first N] [--ack-fraction 0.5]
 *
 *   --token         require "Authorization: Bearer <token>" (401 otherwise)
 *   --fail-first N  answer HTTP 503 to the first N requests (simulate an outage)
 *   --ack-fraction  acknowledge only this share of each batch (simulate partial success)
 *
 * Then start the backend with:
 *   NEXUS_SYNC_DESTINATION_URL=http://localhost:9090/ingest  (and NEXUS_SYNC_AUTH_TOKEN if --token)
 */
import http from "node:http";
import fs from "node:fs";

const args = process.argv.slice(2);
const opt = (name, fallback) => {
  const i = args.indexOf(`--${name}`);
  return i >= 0 && args[i + 1] !== undefined ? args[i + 1] : fallback;
};
const port = Number(opt("port", 9090));
const token = opt("token", null);
const outFile = opt("out", "received.jsonl");
let failFirst = Number(opt("fail-first", 0));
const ackFraction = Number(opt("ack-fraction", 1));

// Idempotency: remember every recordId already stored (also across restarts of this script).
const seen = new Set();
if (fs.existsSync(outFile)) {
  for (const line of fs.readFileSync(outFile, "utf8").split("\n")) {
    if (line.trim()) seen.add(JSON.parse(line).recordId);
  }
}

const server = http.createServer((req, res) => {
  const send = (status, body) => {
    res.writeHead(status, { "Content-Type": "application/json" });
    res.end(JSON.stringify(body));
  };
  if (req.method !== "POST" || req.url !== "/ingest") return send(404, { error: "POST /ingest only" });
  let raw = "";
  req.on("data", (c) => (raw += c));
  req.on("end", () => {
    if (token && req.headers.authorization !== `Bearer ${token}`) return send(401, { error: "bad token" });
    if (failFirst > 0) {
      failFirst -= 1;
      console.log("simulated outage: 503");
      return send(503, { error: "simulated outage" });
    }
    let batch;
    try {
      batch = JSON.parse(raw);
    } catch {
      return send(400, { error: "invalid JSON" });
    }
    if (batch.schemaVersion !== 1 || !Array.isArray(batch.records)) return send(422, { error: "unsupported payload" });

    const limit = Math.max(0, Math.ceil(batch.records.length * ackFraction));
    const acknowledged = [];
    const rejected = [];
    batch.records.forEach((rec, index) => {
      if (index >= limit) return; // not stored, not acknowledged -> sender retries it later
      if (typeof rec.recordId !== "string" || !rec.payload) {
        rejected.push({ recordId: String(rec.recordId), reason: "malformed record" });
        return;
      }
      if (!seen.has(rec.recordId)) {
        fs.appendFileSync(outFile, JSON.stringify({ recordId: rec.recordId, type: rec.type, payload: rec.payload }) + "\n");
        seen.add(rec.recordId);
      }
      acknowledged.push(rec.recordId);
    });
    console.log(`batch ${batch.batchId}: ${batch.records.length} received, ${acknowledged.length} acknowledged, ${seen.size} stored in total`);
    send(200, { schemaVersion: 1, batchId: batch.batchId, acknowledged, rejected });
  });
});

server.listen(port, "127.0.0.1", () => console.log(`Test receiver listening on http://127.0.0.1:${port}/ingest -> ${outFile}`));
