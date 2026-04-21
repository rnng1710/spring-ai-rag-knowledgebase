import base64
import csv
import json
import os
import random
import time
import uuid
from collections import defaultdict
from pathlib import Path

from locust import HttpUser, between, task


ROOT = Path(__file__).resolve().parent
DATA_DIR = ROOT / "data"

BASE_URL = os.getenv("BASE_URL", "http://localhost:8080").rstrip("/")
USERNAME = os.getenv("USERNAME", "admin")
PASSWORD = os.getenv("PASSWORD", "admin")
ADMIN_USERNAME = os.getenv("ADMIN_USERNAME", USERNAME)
ADMIN_PASSWORD = os.getenv("ADMIN_PASSWORD", PASSWORD)
MODEL_ID = os.getenv("MODEL_ID", "ollama")
LOCUST_RUN_ID = os.getenv("LOCUST_RUN_ID", f"locust-{int(time.time())}")
QUESTION_SEED = int(os.getenv("QUESTION_SEED", "20260416"))
QUESTION_FILE = Path(os.getenv("QUESTION_FILE", DATA_DIR / "questions.csv"))
UPLOAD_FILE_LIST = Path(os.getenv("UPLOAD_FILE_LIST", DATA_DIR / "upload-files.example.txt"))

CHAT_TIMEOUT_SECONDS = float(os.getenv("CHAT_TIMEOUT_SECONDS", "180"))
DOC_POLL_INTERVAL_SECONDS = float(os.getenv("DOC_POLL_INTERVAL_SECONDS", "2"))
DOC_POLL_TIMEOUT_SECONDS = float(os.getenv("DOC_POLL_TIMEOUT_SECONDS", "300"))


def _metric(env, request_type, name, response_time_ms, failed=False, response_length=0):
    env.events.request.fire(
        request_type=request_type,
        name=name,
        response_time=response_time_ms,
        response_length=response_length,
        exception=RuntimeError(name) if failed else None,
    )


def _parse_ratio(raw):
    result = {"rag": 80, "agent": 20}
    if not raw:
        return result
    parsed = {}
    for item in raw.split(","):
        if "=" not in item:
            continue
        key, value = item.split("=", 1)
        key = key.strip().lower()
        try:
            parsed[key] = max(0, int(value.strip()))
        except ValueError:
            continue
    return parsed or result


def _weighted_choice(weights, rng):
    total = sum(weights.values())
    if total <= 0:
        return "rag"
    point = rng.uniform(0, total)
    cursor = 0
    for key, weight in weights.items():
        cursor += weight
        if point <= cursor:
            return key
    return next(iter(weights))


def _load_questions(path):
    if not path.exists():
        return [
            {
                "question_id": "sample-rag-short-001",
                "mode": "rag",
                "bucket": "short",
                "tags": "",
                "expected_answer_length": "short",
                "user_input": "请根据知识库回答这个问题。",
            },
            {
                "question_id": "sample-agent-long-001",
                "mode": "agent",
                "bucket": "long",
                "tags": "",
                "expected_answer_length": "long",
                "user_input": "请综合知识库内容给出完整说明。",
            },
        ]

    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        return [
            row for row in csv.DictReader(handle)
            if row.get("user_input") and row.get("mode")
        ]


class QuestionBank:
    def __init__(self, questions, seed):
        self.rng = random.Random(seed)
        self.pools = defaultdict(list)
        self.offsets = defaultdict(int)

        for row in questions:
            mode = row.get("mode", "rag").strip().lower()
            bucket = row.get("bucket", "default").strip().lower()
            self.pools[(mode, bucket)].append(row)

        for pool in self.pools.values():
            self.rng.shuffle(pool)

        self.buckets_by_mode = defaultdict(list)
        for mode, bucket in self.pools:
            self.buckets_by_mode[mode].append(bucket)
        for buckets in self.buckets_by_mode.values():
            buckets.sort()

    def next(self, mode):
        mode = mode.strip().lower()
        buckets = self.buckets_by_mode.get(mode)
        if not buckets:
            buckets = self.buckets_by_mode.get("rag") or []
        if not buckets:
            raise RuntimeError("No questions available")

        bucket_index = self.offsets[(mode, "__bucket__")] % len(buckets)
        bucket = buckets[bucket_index]
        self.offsets[(mode, "__bucket__")] += 1

        pool = self.pools[(mode, bucket)]
        index = self.offsets[(mode, bucket)] % len(pool)
        self.offsets[(mode, bucket)] += 1
        return pool[index]


QUESTION_BANK = QuestionBank(_load_questions(QUESTION_FILE), QUESTION_SEED)
MODE_RATIO = _parse_ratio(os.getenv("CHAT_MODE_RATIO", "rag=80,agent=20"))
MODEL_RATIO = _parse_ratio(os.getenv("MODEL_RATIO", f"{MODEL_ID}=100"))
CHAT_RNG = random.Random(QUESTION_SEED + 1)
UPLOAD_RNG = random.Random(QUESTION_SEED + 2)


def select_model_id(mode):
    explicit = os.getenv(f"MODEL_ID_{mode.upper()}")
    if explicit:
        return explicit
    return _weighted_choice(MODEL_RATIO, CHAT_RNG)


class AuthMixin:
    username = USERNAME
    password = PASSWORD

    def on_start(self):
        self._login_at = 0
        self.access_token = None
        self.login()

    def auth_headers(self, extra=None):
        self.ensure_login()
        headers = {
            "Authorization": f"Bearer {self.access_token}",
            "X-Locust-Run-Id": LOCUST_RUN_ID,
        }
        if extra:
            headers.update(extra)
        return headers

    def ensure_login(self):
        if not self.access_token or time.time() - self._login_at > 2700:
            self.login()

    def login(self):
        with self.client.post(
            "/api/v1/auth/login",
            json={"username": self.username, "password": self.password},
            name="/api/v1/auth/login",
            catch_response=True,
        ) as response:
            if response.status_code != 200:
                response.failure(f"login http {response.status_code}")
                return
            try:
                body = response.json()
                self.access_token = body["data"]["access_token"]
                self._login_at = time.time()
                response.success()
            except (KeyError, TypeError, ValueError) as exc:
                response.failure(f"login token parse failed: {exc}")


class ChatUser(AuthMixin, HttpUser):
    host = BASE_URL
    wait_time = between(
        float(os.getenv("CHAT_WAIT_MIN_SECONDS", "1")),
        float(os.getenv("CHAT_WAIT_MAX_SECONDS", "3")),
    )
    weight = int(os.getenv("CHAT_USER_WEIGHT", "8"))

    @task
    def chat_sse(self):
        mode = _weighted_choice(MODE_RATIO, CHAT_RNG)
        question = QUESTION_BANK.next(mode)
        conversation_id = f"{LOCUST_RUN_ID}-{mode}-{uuid.uuid4().hex[:12]}"
        msg_id = f"msg-{uuid.uuid4().hex[:12]}"
        tags = [
            item.strip()
            for item in (question.get("tags") or "").split("|")
            if item.strip()
        ]
        payload = {
            "userInput": question["user_input"],
            "tags": tags,
            "modelId": select_model_id(mode),
            "mode": mode,
            "msgId": msg_id,
        }
        headers = self.auth_headers({
            "Content-Type": "application/json",
            "Accept": "text/event-stream",
            "X-Question-Id": question.get("question_id", ""),
            "X-Question-Bucket": question.get("bucket", ""),
        })

        start = time.perf_counter()
        first_event_seen = False
        done_seen = False
        error_seen = False
        event_name = None
        data_lines = []
        bytes_seen = 0

        with self.client.post(
            "/api/v1/chat",
            params={"conversationId": conversation_id},
            json=payload,
            headers=headers,
            stream=True,
            timeout=CHAT_TIMEOUT_SECONDS,
            name="/api/v1/chat [sse]",
            catch_response=True,
        ) as response:
            accept_ms = (time.perf_counter() - start) * 1000
            accept_failed = response.status_code != 200
            _metric(self.environment, "CHAT_METRIC", "request_accept_latency", accept_ms, accept_failed)
            if accept_failed:
                response.failure(f"chat http {response.status_code}")
                _metric(self.environment, "CHAT_RATE", "sse_error_event_rate", 0, True)
                _metric(self.environment, "CHAT_RATE", "stream_aborted_rate", 0, True)
                return

            try:
                for raw_line in response.iter_lines(decode_unicode=True, chunk_size=1):
                    if raw_line is None:
                        continue
                    bytes_seen += len(raw_line)
                    line = raw_line.rstrip("\r")

                    if not line:
                        if event_name or data_lines:
                            current_event = event_name or "message"
                            if not first_event_seen:
                                first_event_ms = (time.perf_counter() - start) * 1000
                                _metric(self.environment, "CHAT_METRIC", "first_event_latency", first_event_ms)
                                first_event_seen = True
                            if current_event == "error":
                                error_seen = True
                            if current_event == "done":
                                done_seen = True
                                break
                        event_name = None
                        data_lines = []
                        continue

                    if line.startswith(":"):
                        continue
                    if line.startswith("event:"):
                        event_name = line.split(":", 1)[1].strip()
                    elif line.startswith("data:"):
                        data_lines.append(line.split(":", 1)[1].strip())

                complete_ms = (time.perf_counter() - start) * 1000
                aborted = not done_seen
                _metric(self.environment, "CHAT_METRIC", "stream_complete_latency", complete_ms, aborted)
                _metric(self.environment, "CHAT_RATE", "sse_error_event_rate", 0, error_seen)
                _metric(self.environment, "CHAT_RATE", "stream_aborted_rate", 0, aborted)

                if error_seen:
                    response.failure("sse error event")
                elif aborted:
                    response.failure("sse stream aborted before done")
                else:
                    response.success()
            except Exception as exc:
                complete_ms = (time.perf_counter() - start) * 1000
                _metric(self.environment, "CHAT_METRIC", "stream_complete_latency", complete_ms, True)
                _metric(self.environment, "CHAT_RATE", "sse_error_event_rate", 0, error_seen)
                _metric(self.environment, "CHAT_RATE", "stream_aborted_rate", 0, True)
                response.failure(f"sse read failed: {exc}")
            finally:
                if not first_event_seen and response.status_code == 200:
                    first_event_ms = (time.perf_counter() - start) * 1000
                    _metric(self.environment, "CHAT_METRIC", "first_event_latency", first_event_ms, True)
                if bytes_seen:
                    _metric(self.environment, "CHAT_METRIC", "stream_bytes", 0, False, bytes_seen)


def _load_upload_files():
    raw = os.getenv("UPLOAD_FILES", "")
    candidates = [item.strip() for item in raw.split(";") if item.strip()]
    if not candidates and UPLOAD_FILE_LIST.exists():
        candidates = [
            line.strip()
            for line in UPLOAD_FILE_LIST.read_text(encoding="utf-8").splitlines()
            if line.strip() and not line.strip().startswith("#")
        ]
    return [Path(item) for item in candidates]


UPLOAD_FILES = _load_upload_files()


class AdminUploadUser(AuthMixin, HttpUser):
    host = BASE_URL
    username = ADMIN_USERNAME
    password = ADMIN_PASSWORD
    wait_time = between(
        float(os.getenv("UPLOAD_WAIT_MIN_SECONDS", "3")),
        float(os.getenv("UPLOAD_WAIT_MAX_SECONDS", "8")),
    )
    weight = int(os.getenv("UPLOAD_USER_WEIGHT", "2"))

    @task
    def upload_and_poll(self):
        if not UPLOAD_FILES:
            _metric(self.environment, "UPLOAD_RATE", "upload_files_configured", 0, True)
            time.sleep(5)
            return

        source = UPLOAD_RNG.choice(UPLOAD_FILES)
        if not source.exists():
            _metric(self.environment, "UPLOAD_RATE", "upload_source_exists", 0, True)
            return

        virtual_name = f"{LOCUST_RUN_ID}-{uuid.uuid4().hex[:8]}-{source.name}"
        start = time.perf_counter()
        doc_uuid = None
        with source.open("rb") as handle:
            files = {
                "file": (source.name, handle, "application/octet-stream"),
            }
            data = {
                "fileName": virtual_name,
                "overwrite": "false",
            }
            with self.client.post(
                "/api/v1/docs/upload",
                files=files,
                data=data,
                headers=self.auth_headers(),
                timeout=float(os.getenv("UPLOAD_TIMEOUT_SECONDS", "900")),
                name="/api/v1/docs/upload",
                catch_response=True,
            ) as response:
                accept_ms = (time.perf_counter() - start) * 1000
                failed = response.status_code != 200
                try:
                    body = response.json()
                    failed = failed or body.get("code") != 0 or not body.get("success", False)
                    doc_uuid = (body.get("data") or {}).get("docUuid")
                except (TypeError, ValueError):
                    failed = True
                _metric(self.environment, "UPLOAD_METRIC", "upload_accept_latency", accept_ms, failed)
                if failed:
                    response.failure(f"upload failed status={response.status_code}")
                    return
                response.success()

        if doc_uuid:
            self.poll_document_status(doc_uuid, start)

    def poll_document_status(self, doc_uuid, upload_start):
        status_started_at = time.perf_counter()
        observed_status = None
        observed_durations = defaultdict(float)
        deadline = time.perf_counter() + DOC_POLL_TIMEOUT_SECONDS
        final_status = None

        while time.perf_counter() < deadline:
            status = self.fetch_document_status(doc_uuid)
            now = time.perf_counter()
            if status:
                if observed_status and status != observed_status:
                    observed_durations[observed_status] += now - status_started_at
                    status_started_at = now
                observed_status = status
                if status in {"COMPLETED", "FAILED"}:
                    final_status = status
                    observed_durations[status] += now - status_started_at
                    break
            time.sleep(DOC_POLL_INTERVAL_SECONDS)

        complete_ms = (time.perf_counter() - upload_start) * 1000
        failed = final_status != "COMPLETED"
        _metric(self.environment, "UPLOAD_METRIC", "etl_complete_latency", complete_ms, failed)
        _metric(self.environment, "UPLOAD_RATE", "etl_final_failure_rate", 0, failed)
        for status, duration_seconds in observed_durations.items():
            _metric(
                self.environment,
                "UPLOAD_METRIC",
                f"external_stage_observed_duration:{status}",
                duration_seconds * 1000,
            )

    def fetch_document_status(self, doc_uuid):
        with self.client.get(
            "/api/v1/docs",
            params={"page": 1, "size": 50},
            headers=self.auth_headers(),
            name="/api/v1/docs [poll]",
            catch_response=True,
        ) as response:
            if response.status_code != 200:
                response.failure(f"docs poll http {response.status_code}")
                return None
            try:
                body = response.json()
                records = ((body.get("data") or {}).get("records") or [])
                for record in records:
                    if record.get("docUuid") == doc_uuid:
                        response.success()
                        return record.get("status")
                response.success()
                return None
            except (TypeError, ValueError) as exc:
                response.failure(f"docs poll parse failed: {exc}")
                return None


def langfuse_basic_auth(public_key, secret_key):
    token = base64.b64encode(f"{public_key}:{secret_key}".encode("utf-8")).decode("ascii")
    return f"Basic {token}"
