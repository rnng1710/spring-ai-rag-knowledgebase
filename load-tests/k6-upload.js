import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Trend } from "k6/metrics";

const baseUrl = (__ENV.BASE_URL || "http://localhost:8080").replace(/\/$/, "");
const username = __ENV.USERNAME || "admin";
const password = __ENV.PASSWORD || "admin";
const uploadFiles = (__ENV.UPLOAD_FILES || "")
  .split(";")
  .map((item) => item.trim())
  .filter((item) => item.length > 0);

if (uploadFiles.length === 0) {
  throw new Error("UPLOAD_FILES must contain at least one file path separated by ';'");
}

function basename(filePath) {
  const normalized = filePath.replace(/\\/g, "/");
  const segments = normalized.split("/");
  return segments[segments.length - 1];
}

const openedFiles = uploadFiles.map((filePath) => ({
  path: filePath,
  name: basename(filePath),
  content: open(filePath, "b"),
}));

const uploadLatency = new Trend("upload_latency_ms");
const healthLatency = new Trend("health_latency_ms");
const upload5xx = new Counter("upload_http_5xx");
const healthFailures = new Counter("health_failures");

export const options = {
  scenarios: {
    uploads: {
      executor: "shared-iterations",
      vus: Number(__ENV.VUS || 5),
      iterations: Number(__ENV.ITERATIONS || openedFiles.length),
      exec: "uploadScenario",
      maxDuration: __ENV.MAX_DURATION || "10m",
    },
    health: {
      executor: "constant-arrival-rate",
      rate: Number(__ENV.HEALTH_RATE || 5),
      timeUnit: "1s",
      duration: __ENV.HEALTH_DURATION || "3m",
      preAllocatedVUs: Number(__ENV.HEALTH_PREALLOCATED_VUS || 1),
      maxVUs: Number(__ENV.HEALTH_MAX_VUS || 4),
      exec: "healthScenario",
      startTime: __ENV.HEALTH_START_TIME || "0s",
    },
  },
  thresholds: {
    "http_req_failed{scenario:uploads}": ["rate<0.05"],
    "http_req_duration{scenario:uploads}": ["p(95)<10000"],
    "http_req_duration{scenario:health}": ["p(95)<1000"],
    "checks{scenario:health}": ["rate>0.99"],
  },
};

export function setup() {
  const response = http.post(
    `${baseUrl}/api/v1/auth/login`,
    JSON.stringify({ username, password }),
    { headers: { "Content-Type": "application/json" } }
  );

  const ok = check(response, {
    "login returned HTTP 200": (r) => r.status === 200,
    "login returned token envelope": (r) => {
      const body = r.json();
      return body && body.code === 0 && body.data && body.data.access_token;
    },
  });

  if (!ok) {
    throw new Error(`Login failed: status=${response.status}, body=${response.body}`);
  }

  return {
    accessToken: response.json().data.access_token,
    runPrefix: __ENV.FILE_NAME_PREFIX || `k6-${Date.now()}`,
  };
}

export function uploadScenario(data) {
  const index = (__ITER + ((__VU - 1) * 997)) % openedFiles.length;
  const file = openedFiles[index];
  const virtualName = `${data.runPrefix}-${__VU}-${__ITER}-${file.name}`;

  const payload = {
    file: http.file(file.content, file.name, "application/pdf"),
    fileName: virtualName,
    overwrite: "false",
  };

  const response = http.post(`${baseUrl}/api/v1/docs/upload`, payload, {
    headers: {
      Authorization: `Bearer ${data.accessToken}`,
    },
    timeout: __ENV.UPLOAD_TIMEOUT || "15m",
  });

  uploadLatency.add(response.timings.duration);
  if (response.status >= 500) {
    upload5xx.add(1);
  }

  check(response, {
    "upload returned HTTP 200": (r) => r.status === 200,
    "upload returned success envelope": (r) => {
      const body = r.json();
      return body && body.code === 0 && body.success === true;
    },
  });
}

export function healthScenario() {
  const response = http.get(`${baseUrl}/actuator/health`, {
    timeout: __ENV.HEALTH_TIMEOUT || "10s",
  });

  healthLatency.add(response.timings.duration);
  const ok = check(response, {
    "health is HTTP 200": (r) => r.status === 200,
  });

  if (!ok) {
    healthFailures.add(1);
  }

  sleep(Number(__ENV.HEALTH_SLEEP_SECONDS || 0));
}
