import http.client
import os
import threading
import time
import unittest
from unittest import mock

import agent_server
import mr_agent


class AgentServerCancellationTest(unittest.TestCase):
    def create_server(self, run_agent_func=lambda task: {
        "status": "completed",
        "reason": "done",
    }):
        return agent_server.create_server(
            port=0,
            agent_token="test-token",
            run_agent_func=run_agent_func,
            task_history_limit=10,
        )

    def wait_for_status(self, server, task_id, expected, timeout=2):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            result = server.get_task_result(task_id)
            if result["status"] == expected:
                return result
            time.sleep(0.01)
        self.fail(f"task {task_id} did not reach {expected}")

    def test_queued_task_cancelled_before_agent_execution(self):
        calls = []
        server = self.create_server(lambda task: calls.append(task))
        try:
            with mock.patch.object(threading.Thread, "start"):
                task_id = server.start_task("queued task")
            requested = server.cancel_task(task_id)

            self.assertEqual("queued", requested["status"])
            self.assertTrue(requested["cancel_requested"])
            server._run_task_worker(task_id)
            self.assertEqual([], calls)
            self.assertEqual("cancelled", server.get_task_result(task_id)["status"])
        finally:
            server.server_close()

    def test_running_task_receives_cooperative_cancellation(self):
        observed = threading.Event()

        def cancellable_agent(task, cancel_event=None):
            observed.set()
            self.assertTrue(cancel_event.wait(2))
            return {
                "status": "cancelled",
                "reason": agent_server.CANCELLED_REASON,
            }

        server = self.create_server(cancellable_agent)
        try:
            task_id = server.start_task("running task")
            self.assertTrue(observed.wait(1))
            requested = server.cancel_task(task_id)

            self.assertTrue(requested["cancel_requested"])
            terminal = self.wait_for_status(server, task_id, "cancelled")
            self.assertEqual(agent_server.CANCELLED_REASON, terminal["reason"])
            self.assertTrue(server.run_lock.acquire(timeout=1))
            server.run_lock.release()
        finally:
            server.server_close()

    def test_repeated_cancel_is_safe_and_completed_is_not_rewritten(self):
        server = self.create_server()
        try:
            with mock.patch.object(threading.Thread, "start"):
                task_id = server.start_task("queued task")
            first = server.cancel_task(task_id)
            second = server.cancel_task(task_id)
            self.assertEqual(first, second)
            server._run_task_worker(task_id)

            completed_id = "completed-task"
            with server.tasks_lock:
                server.tasks[completed_id] = {
                    "task_id": completed_id,
                    "status": "completed",
                    "task": "done",
                    "created_at": server._now(),
                    "started_at": server._now(),
                    "finished_at": server._now(),
                    "reason": "already done",
                    "cancel_event": threading.Event(),
                }
            self.assertEqual(
                "completed",
                server.cancel_task(completed_id)["status"],
            )
        finally:
            server.server_close()

    def test_wrong_task_id_does_not_cancel_active_task(self):
        server = self.create_server()
        try:
            with mock.patch.object(threading.Thread, "start"):
                task_id = server.start_task("queued task")
            self.assertIsNone(server.cancel_task("wrong-id"))
            with server.tasks_lock:
                self.assertFalse(server.tasks[task_id]["cancel_event"].is_set())
            server.cancel_task(task_id)
            server._run_task_worker(task_id)
        finally:
            server.server_close()

    def test_cancel_endpoint_requires_authentication(self):
        server = self.create_server()
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            connection = http.client.HTTPConnection(*server.server_address, timeout=2)
            connection.request("POST", "/tasks/unknown/cancel")
            response = connection.getresponse()
            response.read()
            self.assertEqual(401, response.status)
            connection.close()

            connection = http.client.HTTPConnection(*server.server_address, timeout=2)
            connection.request(
                "POST",
                "/tasks/unknown/cancel",
                headers={"Authorization": "Bearer test-token"},
            )
            response = connection.getresponse()
            response.read()
            self.assertEqual(404, response.status)
            connection.close()
        finally:
            server.shutdown()
            server.server_close()
            thread.join(1)


class AgentLoopCancellationTest(unittest.TestCase):
    def test_cancel_after_model_response_prevents_tap(self):
        cancel_event = threading.Event()
        taps = []

        def model_response(*args, **kwargs):
            cancel_event.set()
            return {
                "status": "success",
                "content": '{"action":"tap","x":500,"y":500}',
            }

        with mock.patch.dict(
            os.environ,
            {"MOBILERUN_TOKEN": "portal", "ZHIPU_API_KEY": "glm"},
            clear=False,
        ), mock.patch.object(mr_agent, "START_DELAY", 0), mock.patch.object(
            mr_agent,
            "get_screenshot",
            return_value="image",
        ), mock.patch.object(mr_agent, "call_glm", side_effect=model_response), mock.patch.object(
            mr_agent,
            "portal_tap",
            side_effect=lambda x, y: taps.append((x, y)),
        ):
            result = mr_agent.run_agent("执行一个界面操作", cancel_event=cancel_event)

        self.assertEqual("cancelled", result["status"])
        self.assertEqual([], taps)

    def test_cancel_after_parse_but_before_dispatch_prevents_tap(self):
        cancel_event = threading.Event()
        taps = []

        def parsed_action(_content):
            cancel_event.set()
            return {"action": "tap", "x": 500, "y": 500}

        with mock.patch.dict(
            os.environ,
            {"MOBILERUN_TOKEN": "portal", "ZHIPU_API_KEY": "glm"},
            clear=False,
        ), mock.patch.object(mr_agent, "START_DELAY", 0), mock.patch.object(
            mr_agent,
            "get_screenshot",
            return_value="image",
        ), mock.patch.object(
            mr_agent,
            "call_glm",
            return_value={"status": "success", "content": "valid action"},
        ), mock.patch.object(mr_agent, "parse_action", side_effect=parsed_action), mock.patch.object(
            mr_agent,
            "portal_tap",
            side_effect=lambda x, y: taps.append((x, y)),
        ):
            result = mr_agent.run_agent("执行一个界面操作", cancel_event=cancel_event)

        self.assertEqual("cancelled", result["status"])
        self.assertEqual([], taps)

    def test_cancelled_before_next_step_executes_no_new_action(self):
        cancel_event = threading.Event()
        screenshot_count = 0

        def screenshot(step):
            nonlocal screenshot_count
            screenshot_count += 1
            return "image"

        def model_response(*args, **kwargs):
            return {
                "status": "success",
                "content": '{"action":"wait","seconds":10}',
            }

        timer = threading.Timer(0.05, cancel_event.set)
        timer.start()
        try:
            with mock.patch.dict(
                os.environ,
                {"MOBILERUN_TOKEN": "portal", "ZHIPU_API_KEY": "glm"},
                clear=False,
            ), mock.patch.object(mr_agent, "START_DELAY", 0), mock.patch.object(
                mr_agent,
                "get_screenshot",
                side_effect=screenshot,
            ), mock.patch.object(mr_agent, "call_glm", side_effect=model_response):
                result = mr_agent.run_agent("等待页面", cancel_event=cancel_event)
        finally:
            timer.cancel()

        self.assertEqual("cancelled", result["status"])
        self.assertEqual(1, screenshot_count)

    def test_interruptible_wait_does_not_wait_full_duration(self):
        cancel_event = threading.Event()
        timer = threading.Timer(0.05, cancel_event.set)
        started = time.monotonic()
        timer.start()
        try:
            completed = mr_agent.interruptible_wait(10, cancel_event)
        finally:
            timer.cancel()

        self.assertFalse(completed)
        self.assertLess(time.monotonic() - started, 1)

    def test_input_transport_finally_restores_ime_on_failure(self):
        calls = []

        def subprocess_result(command, **kwargs):
            calls.append(command[-1])
            if command[-1].startswith("settings get"):
                return mock.Mock(stdout="user.ime/.Keyboard\n", stderr="", returncode=0)
            if command[-1].startswith("content insert"):
                return mock.Mock(stdout="", stderr="input failed", returncode=1)
            return mock.Mock(stdout="", stderr="", returncode=0)

        with mock.patch.object(os.path, "exists", return_value=True), mock.patch.object(
            mr_agent.subprocess,
            "run",
            side_effect=subprocess_result,
        ), mock.patch.object(mr_agent.time, "sleep"):
            with self.assertRaises(RuntimeError):
                mr_agent.shizuku_input_text("test")

        self.assertTrue(calls[-1].startswith("ime set user.ime/.Keyboard"))

    def test_cancellation_during_input_still_restores_ime(self):
        cancel_event = threading.Event()
        commands = []

        def subprocess_result(command, **kwargs):
            shell_command = command[-1]
            commands.append(shell_command)
            if shell_command.startswith("settings get"):
                return mock.Mock(stdout="user.ime/.Keyboard\n", stderr="", returncode=0)
            if shell_command.startswith("content insert"):
                cancel_event.set()
            return mock.Mock(stdout="", stderr="", returncode=0)

        actions = [
            {
                "status": "success",
                "content": (
                    '{"action":"tap","x":500,"y":500,'
                    '"target":"搜索框","target_type":"text_input"}'
                ),
            },
            {
                "status": "success",
                "content": '{"action":"input_text","text":"test"}',
            },
        ]

        with mock.patch.dict(
            os.environ,
            {"MOBILERUN_TOKEN": "portal", "ZHIPU_API_KEY": "glm"},
            clear=False,
        ), mock.patch.object(mr_agent, "START_DELAY", 0), mock.patch.object(
            mr_agent,
            "get_screenshot",
            return_value="image",
        ), mock.patch.object(mr_agent, "call_glm", side_effect=actions), mock.patch.object(
            mr_agent,
            "get_foreground_app",
            return_value="com.kugou.android",
        ), mock.patch.object(mr_agent, "portal_tap", return_value={"status": "success"}), mock.patch.object(
            mr_agent,
            "interruptible_wait",
            side_effect=lambda seconds, event=None, interval=0.2: not (
                event is not None and event.is_set()
            ),
        ), mock.patch.object(os.path, "exists", return_value=True), mock.patch.object(
            mr_agent.subprocess,
            "run",
            side_effect=subprocess_result,
        ), mock.patch.object(mr_agent.time, "sleep"):
            result = mr_agent.run_agent("在搜索框输入test", cancel_event=cancel_event)

        self.assertEqual("cancelled", result["status"])
        self.assertTrue(commands[-1].startswith("ime set user.ime/.Keyboard"))


if __name__ == "__main__":
    unittest.main()
