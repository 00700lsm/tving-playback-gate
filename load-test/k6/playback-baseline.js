import encoding from "k6/encoding";
import crypto from "k6/crypto";
import http from "k6/http";
import { check, sleep } from "k6";
import { Trend, Rate } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const JWT_SECRET = __ENV.JWT_SECRET || "playback-gate-local-secret-key-32bytes-min";
const MEMBER_ID_MIN = Number(__ENV.MEMBER_ID_MIN || 9);
const MEMBER_ID_MAX = Number(__ENV.MEMBER_ID_MAX || 2008);
const CONTENT_ID = Number(__ENV.CONTENT_ID || 1);

const startDuration = new Trend("playback_start_duration", true);
const startErrorRate = new Rate("playback_start_errors");

export const options = {
  summaryTrendStats: ["avg", "min", "med", "p(90)", "p(95)", "p(99)", "max"],
  scenarios: {
    baseline: {
      executor: "ramping-vus",
      startVUs: 0,
      stages: [
        { duration: "30s", target: 10 },
        { duration: "1m", target: 10 },
        { duration: "30s", target: 50 },
        { duration: "1m", target: 50 },
        { duration: "30s", target: 100 },
        { duration: "1m", target: 100 },
        { duration: "30s", target: 0 },
      ],
      gracefulRampDown: "10s",
    },
  },
  thresholds: {
    playback_start_errors: ["rate<0.05"],
    http_req_failed: ["rate<0.05"],
  },
};

function createAuthToken(memberId) {
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

function memberIdForVu() {
  const span = MEMBER_ID_MAX - MEMBER_ID_MIN + 1;
  return MEMBER_ID_MIN + ((__VU - 1 + __ITER * 97) % span);
}

export default function () {
  const memberId = memberIdForVu();
  const token = createAuthToken(memberId);
  const headers = {
    Authorization: `Bearer ${token}`,
    "Content-Type": "application/json",
  };

  const startRes = http.post(
    `${BASE_URL}/api/v1/playback/sessions`,
    JSON.stringify({
      contentId: CONTENT_ID,
      deviceId: `k6-${__VU}-${__ITER}`,
    }),
    { headers, tags: { name: "playback_start" } }
  );

  startDuration.add(startRes.timings.duration);
  const started = check(startRes, {
    "start 200": (res) => res.status === 200,
  });
  startErrorRate.add(!started);

  if (started) {
    const body = startRes.json();
    http.del(`${BASE_URL}/api/v1/playback/sessions/${body.sessionId}`, null, {
      headers,
      tags: { name: "playback_end" },
    });
  }

  sleep(0.1);
}

export function handleSummary(data) {
  return {
    stdout: textSummary(data),
  };
}

function textSummary(data) {
  const start = data.metrics.playback_start_duration;
  const httpReq = data.metrics.http_reqs;
  const errors = data.metrics.playback_start_errors;
  const lines = [
    "",
    "=== Playback Gate Baseline ===",
    `http_reqs: ${httpReq ? httpReq.values.count : "n/a"}`,
    `start p50: ${valueMs(start, "med")}`,
    `start p95: ${valueMs(start, "p(95)")}`,
    `start p99: ${valueMs(start, "p(99)")}`,
    `start error rate: ${errors ? (errors.values.rate * 100).toFixed(2) + "%" : "n/a"}`,
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
