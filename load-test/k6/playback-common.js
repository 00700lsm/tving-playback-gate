import encoding from "k6/encoding";
import crypto from "k6/crypto";
import http from "k6/http";
import { check } from "k6";
import { Trend, Rate } from "k6/metrics";

export const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
export const JWT_SECRET = __ENV.JWT_SECRET || "playback-gate-local-secret-key-32bytes-min";
export const MEMBER_ID_MIN = Number(__ENV.MEMBER_ID_MIN || 9);
export const MEMBER_ID_MAX = Number(__ENV.MEMBER_ID_MAX || 2008);
export const CONTENT_ID = Number(__ENV.CONTENT_ID || 1);

export const startDuration = new Trend("playback_start_duration", true);
export const startErrorRate = new Rate("playback_start_errors");

export function createAuthToken(memberId) {
  const header = encoding.b64encode(JSON.stringify({ alg: "HS256", typ: "JWT" }), "rawurl");
  const now = Math.floor(Date.now() / 1000);
  const payload = encoding.b64encode(
    JSON.stringify({
      sub: String(memberId),
      tokenType: "AUTH",
      iat: now,
      exp: now + 86400,
    }),
    "rawurl"
  );
  const unsigned = `${header}.${payload}`;
  const signature = crypto.hmac("sha256", JWT_SECRET, unsigned, "base64rawurl");
  return `${unsigned}.${signature}`;
}

export function memberIdForVu() {
  const span = MEMBER_ID_MAX - MEMBER_ID_MIN + 1;
  return MEMBER_ID_MIN + ((__VU - 1 + __ITER * 97) % span);
}

export function startAndEndPlayback() {
  const memberId = memberIdForVu();
  const token = createAuthToken(memberId);
  const headers = {
    Authorization: `Bearer ${token}`,
    "Content-Type": "application/json",
  };
  const params = { headers, timeout: "10s" };

  const startRes = http.post(
    `${BASE_URL}/api/v1/playback/sessions`,
    JSON.stringify({
      contentId: CONTENT_ID,
      deviceId: `k6-${__VU}-${__ITER}`,
    }),
    { ...params, tags: { name: "playback_start" } }
  );

  startDuration.add(startRes.timings.duration);
  const started = check(startRes, {
    "start 200": (res) => res.status === 200,
  });
  startErrorRate.add(!started);

  if (started) {
    const body = startRes.json();
    http.del(`${BASE_URL}/api/v1/playback/sessions/${body.sessionId}`, null, {
      ...params,
      tags: { name: "playback_end" },
    });
  }
}

export function textSummary(title, data) {
  const start = data.metrics.playback_start_duration;
  const httpReq = data.metrics.http_reqs;
  const errors = data.metrics.playback_start_errors;
  const iters = data.metrics.iterations;
  const dropped = data.metrics.dropped_iterations;
  const failed = data.metrics.http_req_failed;
  const lines = [
    "",
    `=== ${title} ===`,
    `iterations: ${iters && iters.values ? iters.values.count : "n/a"}`,
    `iteration rate: ${iters && iters.values && iters.values.rate !== undefined ? iters.values.rate.toFixed(1) : "n/a"} /s`,
    `dropped_iterations: ${dropped && dropped.values ? dropped.values.count : 0}`,
    `http_reqs: ${httpReq ? httpReq.values.count : "n/a"}`,
    `start p50: ${valueMs(start, "med")}`,
    `start p95: ${valueMs(start, "p(95)")}`,
    `start p99: ${valueMs(start, "p(99)")}`,
    `start error rate: ${errors ? (errors.values.rate * 100).toFixed(2) + "%" : "n/a"}`,
    `http_req_failed: ${failed && failed.values ? (failed.values.rate * 100).toFixed(3) + "%" : "n/a"}`,
    "",
  ];
  return lines.join("\n");
}

function valueMs(metric, key) {
  if (!metric || metric.values[key] === undefined) {
    return "n/a";
  }
  return `${metric.values[key].toFixed(2)} ms`;
}
