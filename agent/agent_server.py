import hmac
import json
import os
import sys
import threading
import traceback
import uuid
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

import mr_agent


HOST = "127.0.0.1"
PORT = 8765
MAX_REQUEST_BODY_BYTES = 16 * 1024
MAX_TASK_CHARS = 8000
MAX_TASK_HISTORY = 100
TERMINAL_TASK_STATUSES = {
    "completed",
    "blocked",
    "failed",
    "max_steps",
}


class AgentHTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(
        self,
        server_address,
        request_handler_class,
        agent_token,
        run_agent_func,
        task_history_limit,
    ):
        super().__init__(server_address, request_handler_class)
        self.agent_token = agent_token
        self.run_agent_func = run_agent_func
        self.run_lock = threading.Lock()
        self.tasks_lock = threading.Lock()
        self.tasks = {}
        self.task_history_limit = task_history_limit

    @staticmethod
    def _now():
        return datetime.now(timezone.utc).isoformat()

    def execute_agent(self, task):
        result = self.run_agent_func(task)

        if not isinstance(result, dict):
            raise TypeError("run_agent must return a JSON object")

        status = result.get("status")
        reason = result.get("reason")

        if status not in TERMINAL_TASK_STATUSES:
            raise ValueError("run_agent returned an unsupported status")

        if not isinstance(reason, str):
            raise TypeError("run_agent reason must be a string")

        return {
            "status": status,
            "reason": reason,
        }

    def _cleanup_tasks_locked(self):
        finished_tasks = [
            record
            for record in self.tasks.values()
            if record["status"] in TERMINAL_TASK_STATUSES
        ]

        excess_count = len(finished_tasks) - self.task_history_limit

        if excess_count <= 0:
            return

        finished_tasks.sort(
            key=lambda record: (
                record["finished_at"] or "",
                record["created_at"],
                record["task_id"],
            )
        )

        for record in finished_tasks[:excess_count]:
            self.tasks.pop(record["task_id"], None)

    def _run_task_worker(self, task_id):
        try:
            with self.tasks_lock:
                record = self.tasks[task_id]
                record["status"] = "running"
                record["started_at"] = self._now()

            try:
                result = self.execute_agent(record["task"])
            except Exception:
                traceback.print_exc()
                result = {
                    "status": "failed",
                    "reason": "internal agent error",
                }

            with self.tasks_lock:
                record = self.tasks[task_id]
                record["status"] = result["status"]
                record["reason"] = result["reason"]
                record["finished_at"] = self._now()
                self._cleanup_tasks_locked()

        finally:
            self.run_lock.release()

    def start_task(self, task):
        if not self.run_lock.acquire(blocking=False):
            return None

        task_id = str(uuid.uuid4())
        record = {
            "task_id": task_id,
            "status": "queued",
            "task": task,
            "created_at": self._now(),
            "started_at": None,
            "finished_at": None,
            "reason": None,
        }

        with self.tasks_lock:
            self.tasks[task_id] = record

        worker = threading.Thread(
            target=self._run_task_worker,
            args=(task_id,),
            name=f"agent-task-{task_id}",
            daemon=True,
        )

        try:
            worker.start()
        except Exception:
            with self.tasks_lock:
                self.tasks.pop(task_id, None)
            self.run_lock.release()
            raise

        return task_id

    def get_task_result(self, task_id):
        with self.tasks_lock:
            record = self.tasks.get(task_id)

            if record is None:
                return None

            result = {
                "task_id": record["task_id"],
                "status": record["status"],
            }

            if record["status"] in TERMINAL_TASK_STATUSES:
                result["reason"] = record["reason"]

            return result


class AgentRequestHandler(BaseHTTPRequestHandler):
    server_version = "LucidAgentServer/0.2"
    sys_version = ""

    def _send_json(self, status_code, payload):
        body = json.dumps(
            payload,
            ensure_ascii=False,
        ).encode("utf-8")

        self.send_response(status_code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _request_path(self):
        return urlsplit(self.path).path

    def _task_id_from_path(self):
        path_parts = self._request_path().strip("/").split("/")

        if len(path_parts) != 2 or path_parts[0] != "tasks":
            return None

        return path_parts[1] or None

    def _is_authorized(self):
        authorization = self.headers.get("Authorization", "")
        prefix = "Bearer "

        if not authorization.startswith(prefix):
            return False

        supplied_token = authorization[len(prefix):]
        return hmac.compare_digest(
            supplied_token,
            self.server.agent_token,
        )

    def _read_json_object(self):
        if self.headers.get_content_type() != "application/json":
            return None, (
                415,
                {
                    "status": "failed",
                    "reason": "Content-Type must be application/json",
                },
            )

        content_length_text = self.headers.get("Content-Length")

        try:
            content_length = int(content_length_text)
        except (TypeError, ValueError):
            return None, (
                400,
                {
                    "status": "failed",
                    "reason": "invalid Content-Length",
                },
            )

        if content_length <= 0:
            return None, (
                400,
                {
                    "status": "failed",
                    "reason": "request body must not be empty",
                },
            )

        if content_length > MAX_REQUEST_BODY_BYTES:
            return None, (
                413,
                {
                    "status": "failed",
                    "reason": "request body too large",
                },
            )

        body = self.rfile.read(content_length)

        try:
            payload = json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            return None, (
                400,
                {
                    "status": "failed",
                    "reason": "request body must be valid UTF-8 JSON",
                },
            )

        if not isinstance(payload, dict):
            return None, (
                400,
                {
                    "status": "failed",
                    "reason": "request JSON must be an object",
                },
            )

        return payload, None

    def _read_task(self):
        payload, error = self._read_json_object()

        if error is not None:
            return None, error

        task = payload.get("task")

        if not isinstance(task, str) or not task.strip():
            return None, (
                400,
                {
                    "status": "failed",
                    "reason": "task must be a non-empty string",
                },
            )

        if len(task) > MAX_TASK_CHARS:
            return None, (
                400,
                {
                    "status": "failed",
                    "reason": (
                        f"task must be at most {MAX_TASK_CHARS} characters"
                    ),
                },
            )

        return task.strip(), None

    def _send_unauthorized(self):
        self._send_json(
            401,
            {
                "status": "failed",
                "reason": "unauthorized",
            },
        )

    def _send_busy(self):
        self._send_json(
            409,
            {
                "status": "busy",
                "reason": "another agent task is running",
            },
        )

    def do_GET(self):
        if self._request_path() == "/health":
            self._send_json(200, {"status": "ok"})
            return

        task_id = self._task_id_from_path()

        if task_id is not None:
            if not self._is_authorized():
                self._send_unauthorized()
                return

            result = self.server.get_task_result(task_id)

            if result is None:
                self._send_json(404, {"status": "not_found"})
                return

            self._send_json(200, result)
            return

        self._send_json(
            404,
            {
                "status": "failed",
                "reason": "not found",
            },
        )

    def do_POST(self):
        request_path = self._request_path()

        if request_path not in {"/run", "/tasks"}:
            self._send_json(
                404,
                {
                    "status": "failed",
                    "reason": "not found",
                },
            )
            return

        if not self._is_authorized():
            self._send_unauthorized()
            return

        task, error = self._read_task()

        if error is not None:
            status_code, error_payload = error
            self._send_json(status_code, error_payload)
            return

        if request_path == "/tasks":
            try:
                task_id = self.server.start_task(task)
            except Exception:
                traceback.print_exc()
                self._send_json(
                    500,
                    {
                        "status": "failed",
                        "reason": "internal server error",
                    },
                )
                return

            if task_id is None:
                self._send_busy()
                return

            self._send_json(
                202,
                {
                    "task_id": task_id,
                    "status": "queued",
                },
            )
            return

        if not self.server.run_lock.acquire(blocking=False):
            self._send_busy()
            return

        try:
            result = self.server.execute_agent(task)
            self._send_json(200, result)

        except Exception:
            traceback.print_exc()
            self._send_json(
                500,
                {
                    "status": "failed",
                    "reason": "internal server error",
                },
            )

        finally:
            self.server.run_lock.release()


def create_server(
    host=HOST,
    port=PORT,
    agent_token=None,
    run_agent_func=None,
    task_history_limit=MAX_TASK_HISTORY,
):
    token = (
        os.environ.get("LUCID_AGENT_TOKEN")
        if agent_token is None
        else agent_token
    )

    if not isinstance(token, str) or not token.strip():
        raise RuntimeError(
            "LUCID_AGENT_TOKEN is required and must not be empty"
        )

    if host != HOST:
        raise ValueError("Agent Server must bind to 127.0.0.1")

    if (
        not isinstance(task_history_limit, int)
        or isinstance(task_history_limit, bool)
        or task_history_limit <= 0
    ):
        raise ValueError("task_history_limit must be a positive integer")

    if run_agent_func is None:
        run_agent_func = mr_agent.run_agent

    return AgentHTTPServer(
        (host, port),
        AgentRequestHandler,
        token,
        run_agent_func,
        task_history_limit,
    )


def main():
    try:
        server = create_server()
    except (RuntimeError, ValueError, OSError) as error:
        print("Lucid Agent Server failed to start:", error)
        return 1

    address, port = server.server_address
    print(f"Lucid Agent Server listening on {address}:{port}")

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print()
        print("Lucid Agent Server stopping...")
    finally:
        server.server_close()

    return 0


if __name__ == "__main__":
    sys.exit(main())
