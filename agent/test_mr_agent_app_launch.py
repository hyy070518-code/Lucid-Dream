import unittest
from unittest import mock
import json

import mr_agent


class PackageValidationTest(unittest.TestCase):
    def test_valid_packages(self):
        for package in (
            "com.tencent.mm",
            "com.eg.android.AlipayGphone",
            "com.sankuai.meituan",
        ):
            with self.subTest(package=package):
                self.assertTrue(mr_agent.is_valid_package_name(package))

    def test_shell_payloads_are_rejected(self):
        for package in (
            "com.foo; rm -rf /",
            "com.foo && id",
            "$(id)",
            "`id`",
            "com.foo bar",
            "com.foo/com.foo.Secret",
            "comfoo",
            "",
        ):
            with self.subTest(package=package):
                self.assertFalse(mr_agent.is_valid_package_name(package))

    def test_launcher_component_must_belong_to_expected_package(self):
        self.assertTrue(
            mr_agent.is_valid_launcher_component(
                "com.tencent.mm/com.tencent.mm.ui.LauncherUI",
                "com.tencent.mm",
            )
        )
        self.assertFalse(
            mr_agent.is_valid_launcher_component(
                "com.other/com.other.SecretActivity",
                "com.tencent.mm",
            )
        )
        self.assertFalse(
            mr_agent.is_valid_launcher_component(
                "com.tencent.mm/com.tencent.mm.ui.LauncherUI;id",
                "com.tencent.mm",
            )
        )


class ActionCoordinateNormalizationTest(unittest.TestCase):
    def test_numeric_coordinates_remain_valid(self):
        result = mr_agent.normalize_action_payload(
            {"action": "tap", "x": 834, "y": 369}
        )
        self.assertEqual((834, 369), (result["x"], result["y"]))

    def test_numeric_strings_are_normalized(self):
        result = mr_agent.normalize_action_payload(
            {"action": "tap", "x": "834", "y": "369"}
        )
        self.assertEqual((834, 369), (result["x"], result["y"]))

    def test_coordinate_pair_recovers_missing_y(self):
        result = mr_agent.normalize_action_payload(
            {"action": "tap", "x": "834,369"}
        )
        self.assertEqual((834, 369), (result["x"], result["y"]))

    def test_explicit_y_wins_over_pair_y(self):
        result = mr_agent.normalize_action_payload(
            {"action": "tap", "x": "834,369", "y": 371}
        )
        self.assertEqual((834, 371), (result["x"], result["y"]))

    def test_malicious_coordinates_are_rejected(self):
        for value in ("834; id", "$(cmd)", "abc", "1,2,3"):
            with self.subTest(value=value):
                self.assertIsNone(
                    mr_agent.normalize_action_payload(
                        {"action": "tap", "x": value, "y": 369}
                    )
                )

    def test_out_of_bounds_coordinates_are_rejected(self):
        self.assertIsNone(
            mr_agent.normalize_action_payload(
                {"action": "tap", "x": 1001, "y": 369}
            )
        )

    def test_parse_action_applies_targeted_normalization(self):
        result = mr_agent.parse_action(
            '{"action":"tap","x":"834,369","reason":"test"}'
        )
        self.assertEqual((834, 369), (result["x"], result["y"]))


class TaskSafetyTest(unittest.TestCase):
    def test_opening_alipay_is_navigation_not_payment(self):
        result = mr_agent.evaluate_task_safety("打开支付宝")

        self.assertTrue(result["allowed"])
        self.assertEqual("pure_app_launch_navigation", result["matched_rule"])
        self.assertEqual("支付宝", result["launch_target"])

    def test_actual_payment_in_alipay_remains_blocked(self):
        result = mr_agent.evaluate_task_safety("在支付宝支付100元")

        self.assertFalse(result["allowed"])
        self.assertEqual("high_risk:payment_action", result["matched_rule"])

    def test_payment_password_remains_authentication_blocked(self):
        result = mr_agent.evaluate_task_safety("输入支付密码")

        self.assertFalse(result["allowed"])
        self.assertTrue(result["matched_rule"].startswith("authentication:"))

    def test_pure_launch_parser_rejects_follow_up_action(self):
        self.assertIsNone(
            mr_agent.extract_pure_app_launch_target("打开支付宝并支付100元")
        )
        self.assertEqual(
            "美团",
            mr_agent.extract_pure_app_launch_target("帮我打开美团"),
        )


class GenericInputTextSafetyTest(unittest.TestCase):
    def test_kugou_normal_search_text_is_allowed(self):
        result = mr_agent.evaluate_input_text_safety(
            "郭源潮",
            "com.kugou.android",
        )

        self.assertEqual("allowed", result["status"])

    def test_wechat_normal_text_regression_is_allowed(self):
        result = mr_agent.evaluate_input_text_safety(
            "你好",
            "com.tencent.mm",
        )

        self.assertEqual("allowed", result["status"])

    def test_control_plane_foregrounds_are_rejected_for_replan(self):
        for package in ("com.termux", "com.huyang.luciddream"):
            with self.subTest(package=package):
                result = mr_agent.evaluate_input_text_safety("郭源潮", package)
                self.assertEqual("rejected", result["status"])
                self.assertTrue(result["recoverable"])

    def test_authentication_text_is_blocked(self):
        for text in ("输入支付密码", "验证码123456", "OTP 654321", "PIN 1234"):
            with self.subTest(text=text):
                result = mr_agent.evaluate_input_text_safety(
                    text,
                    "com.kugou.android",
                )
                self.assertEqual("blocked", result["status"])


class RunAgentLaunchLifecycleTest(unittest.TestCase):
    def setUp(self):
        self.environment = mock.patch.dict(
            mr_agent.os.environ,
            {
                "MOBILERUN_TOKEN": "fake-portal-token",
                "ZHIPU_API_KEY": "fake-glm-key",
            },
        )
        self.environment.start()

    def tearDown(self):
        self.environment.stop()

    @staticmethod
    def successful_execution(label="美团"):
        return {
            "status": "success",
            "resolution": {
                "status": "resolved",
                "package": "com.sankuai.meituan",
                "label": label,
                "launcherActivity": "com.sankuai.meituan/.MainActivity",
            },
            "launch": {
                "status": "success",
                "package": "com.sankuai.meituan",
                "label": label,
                "launchMethod": "am_start",
                "verifiedForeground": True,
                "reason": "目标 App 已成为前台",
            },
        }

    @mock.patch.object(mr_agent.time, "sleep", return_value=None)
    @mock.patch.object(mr_agent, "get_screenshot")
    @mock.patch.object(mr_agent, "execute_launch_request")
    def test_control_plane_text_never_causes_wait_for_pure_launch(
        self,
        execute_launch,
        screenshot,
        _sleep,
    ):
        execute_launch.return_value = self.successful_execution()

        result = mr_agent.run_agent("打开美团")

        self.assertEqual("completed", result["status"])
        execute_launch.assert_called_once()
        screenshot.assert_not_called()

    @mock.patch.object(mr_agent.time, "sleep", return_value=None)
    @mock.patch.object(mr_agent, "execute_launch_request")
    def test_open_alipay_reaches_launch_without_payment_false_positive(
        self,
        execute_launch,
        _sleep,
    ):
        execution = self.successful_execution(label="支付宝")
        execution["resolution"]["package"] = "com.eg.android.AlipayGphone"
        execution["launch"]["package"] = "com.eg.android.AlipayGphone"
        execute_launch.return_value = execution

        result = mr_agent.run_agent("打开支付宝")

        self.assertEqual("completed", result["status"])
        execute_launch.assert_called_once()
        self.assertEqual(
            "支付宝",
            execute_launch.call_args.kwargs["app_name"],
        )

    @mock.patch.object(mr_agent, "execute_launch_request")
    def test_real_payment_is_blocked_before_launch(self, execute_launch):
        result = mr_agent.run_agent("在支付宝支付100元")

        self.assertEqual("blocked", result["status"])
        execute_launch.assert_not_called()

    @mock.patch.object(mr_agent, "execute_launch_request")
    def test_authentication_is_blocked_before_launch(self, execute_launch):
        result = mr_agent.run_agent("输入支付密码")

        self.assertEqual("blocked", result["status"])
        execute_launch.assert_not_called()

    @mock.patch.object(mr_agent.time, "sleep", return_value=None)
    @mock.patch.object(mr_agent, "get_screenshot", return_value="fake-image")
    @mock.patch.object(mr_agent, "call_glm")
    @mock.patch.object(mr_agent, "execute_launch_request")
    def test_launch_success_on_last_step_wins_over_max_steps(
        self,
        execute_launch,
        call_glm,
        _screenshot,
        _sleep,
    ):
        fast_path_failure = {
            "status": "failed",
            "resolution": {
                "status": "failed",
                "stage": "RESOLVE_LABEL",
                "code": "label_not_found",
                "reason": "fake fast-path miss",
            },
            "launch": None,
        }
        execute_launch.side_effect = [
            fast_path_failure,
            self.successful_execution(),
        ]
        call_glm.return_value = {
            "status": "success",
            "content": (
                '{"action":"launch_app","app":"美团",'
                '"reason":"打开目标 App"}'
            ),
        }

        with mock.patch.object(mr_agent, "MAX_STEPS", 1):
            result = mr_agent.run_agent("打开美团")

        self.assertEqual("completed", result["status"])
        self.assertNotEqual("max_steps", result["status"])


class RunAgentGenericInputTest(unittest.TestCase):
    def setUp(self):
        self.environment = mock.patch.dict(
            mr_agent.os.environ,
            {
                "MOBILERUN_TOKEN": "fake-portal-token",
                "ZHIPU_API_KEY": "fake-glm-key",
            },
        )
        self.environment.start()

    def tearDown(self):
        self.environment.stop()

    @staticmethod
    def action_result(payload):
        return {
            "status": "success",
            "content": json.dumps(payload, ensure_ascii=False),
        }

    @mock.patch.object(mr_agent.time, "sleep", return_value=None)
    @mock.patch.object(mr_agent, "get_screenshot", return_value="fake-image")
    @mock.patch.object(mr_agent, "portal_tap", return_value={"status": "success"})
    @mock.patch.object(mr_agent, "get_foreground_app", return_value="com.kugou.android")
    @mock.patch.object(mr_agent, "shizuku_input_text", return_value={"status": "success"})
    @mock.patch.object(mr_agent, "call_glm")
    def test_kugou_focused_input_uses_existing_transport(
        self,
        call_glm,
        input_text,
        _foreground,
        _tap,
        _screenshot,
        _sleep,
    ):
        call_glm.side_effect = [
            self.action_result({
                "action": "tap",
                "x": 500,
                "y": 500,
                "target": "搜索框",
                "target_type": "text_input",
            }),
            self.action_result({
                "action": "input_text",
                "text": "郭源潮",
                "reason": "输入歌曲名称",
            }),
            self.action_result({
                "action": "done",
                "reason": "离线测试完成",
            }),
        ]

        with mock.patch("builtins.print"):
            with mock.patch.object(mr_agent, "MAX_STEPS", 3):
                result = mr_agent.run_agent("在酷狗搜索框输入郭源潮")

        self.assertEqual("completed", result["status"])
        input_text.assert_called_once_with("郭源潮", clear=True)

    @mock.patch.object(mr_agent.time, "sleep", return_value=None)
    @mock.patch.object(mr_agent, "get_screenshot", return_value="fake-image")
    @mock.patch.object(mr_agent, "portal_tap", return_value={"status": "success"})
    @mock.patch.object(mr_agent, "get_foreground_app", return_value="com.termux")
    @mock.patch.object(mr_agent, "shizuku_input_text")
    @mock.patch.object(mr_agent, "call_glm")
    def test_control_plane_input_is_rejected_and_replanned(
        self,
        call_glm,
        input_text,
        _foreground,
        _tap,
        _screenshot,
        _sleep,
    ):
        call_glm.side_effect = [
            self.action_result({
                "action": "tap",
                "x": 500,
                "y": 500,
                "target": "输入框",
                "target_type": "text_input",
            }),
            self.action_result({
                "action": "input_text",
                "text": "不应写入控制面",
            }),
            self.action_result({
                "action": "done",
                "reason": "已重新规划",
            }),
        ]

        with mock.patch("builtins.print"):
            with mock.patch.object(mr_agent, "MAX_STEPS", 3):
                result = mr_agent.run_agent("检查当前页面后继续")

        self.assertEqual("completed", result["status"])
        input_text.assert_not_called()


class AndroidAppResolverTest(unittest.TestCase):
    CATALOG = [
        {"package": "com.tencent.mm", "label": "微信"},
        {"package": "com.eg.android.AlipayGphone", "label": "支付宝"},
        {"package": "com.sankuai.meituan", "label": "美团"},
        {"package": "com.android.settings", "label": "系统设置"},
    ]
    LAUNCHERS = {
        "com.tencent.mm": "com.tencent.mm/com.tencent.mm.ui.LauncherUI",
        "com.eg.android.AlipayGphone": (
            "com.eg.android.AlipayGphone/.AlipayLogin"
        ),
        "com.sankuai.meituan": "com.sankuai.meituan/.activity.MainActivity",
        "com.android.settings": "com.android.settings/.Settings",
    }

    def resolver(self, catalog=None, installed=None, enabled=None, launchers=None):
        catalog = self.CATALOG if catalog is None else catalog
        launchers = self.LAUNCHERS if launchers is None else launchers
        installed = (
            set(self.LAUNCHERS)
            if installed is None
            else set(installed)
        )
        enabled = installed if enabled is None else set(enabled)
        return mr_agent.AndroidAppResolver(
            package_catalog_loader=lambda: catalog,
            package_state_loader=lambda: (installed, enabled),
            launcher_resolver=lambda package: launchers.get(package),
        )

    def test_dynamic_label_resolution_for_wechat_alipay_and_meituan(self):
        resolver = self.resolver()
        cases = {
            "微信": "com.tencent.mm",
            "支付宝": "com.eg.android.AlipayGphone",
            "美团": "com.sankuai.meituan",
        }

        for label, expected_package in cases.items():
            with self.subTest(label=label):
                result = resolver.resolve(app_name=label)
                self.assertEqual("resolved", result["status"])
                self.assertEqual(expected_package, result["package"])
                self.assertEqual(
                    self.LAUNCHERS[expected_package],
                    result["launcherActivity"],
                )

    def test_unknown_app_has_clear_failure(self):
        result = self.resolver().resolve(app_name="不存在的 App")

        self.assertEqual("failed", result["status"])
        self.assertEqual("label_not_found", result["code"])

    def test_ambiguous_label_is_not_selected(self):
        catalog = [
            {"package": "com.example.one", "label": "同名应用"},
            {"package": "com.example.two", "label": "同名应用"},
        ]
        launchers = {
            "com.example.one": "com.example.one/.Main",
            "com.example.two": "com.example.two/.Main",
        }
        result = self.resolver(
            catalog=catalog,
            installed=set(launchers),
            launchers=launchers,
        ).resolve(app_name="同名应用")

        self.assertEqual("failed", result["status"])
        self.assertEqual("ambiguous_label", result["code"])

    def test_disabled_app_is_rejected(self):
        result = self.resolver(
            installed={"com.sankuai.meituan"},
            enabled=set(),
        ).resolve(app_name="美团")

        self.assertEqual("failed", result["status"])
        self.assertEqual("disabled", result["code"])

    def test_explicit_current_user_disabled_state_is_rejected(self):
        package = "com.ss.android.ugc.aweme.lite"
        resolver = mr_agent.AndroidAppResolver(
            package_catalog_loader=lambda: [
                {"package": package, "label": "抖音极速版"},
            ],
            package_state_loader=lambda: {
                "installed": {package},
                "disabled": {package},
                "disabled_query_known": True,
                "current_user": 0,
            },
            launcher_resolver=lambda _: f"{package}/.MainActivity",
        )

        result = resolver.resolve(app_name="抖音极速版")

        self.assertEqual("disabled", result["code"])
        self.assertEqual("CHECK_ENABLED", result["stage"])

    def test_unknown_enabled_hint_continues_to_launcher(self):
        package = "com.ss.android.ugc.aweme.lite"
        launcher_calls = []
        resolver = mr_agent.AndroidAppResolver(
            package_catalog_loader=lambda: [
                {"package": package, "label": "抖音极速版"},
            ],
            package_state_loader=lambda: {
                "installed": {package},
                "disabled": set(),
                "disabled_query_known": False,
                "current_user": 0,
            },
            launcher_resolver=lambda value: (
                launcher_calls.append(value)
                or f"{package}/.MainActivity"
            ),
        )

        result = resolver.resolve(app_name="抖音极速版")

        self.assertEqual("resolved", result["status"])
        self.assertEqual("unknown_continue_to_launcher", result["enabledState"])
        self.assertEqual([package], launcher_calls)

    @mock.patch.object(mr_agent, "run_rish_command")
    def test_package_state_queries_current_user_and_explicit_disabled(self, run_rish):
        run_rish.side_effect = [
            "10",
            "package:com.kugou.android\npackage:com.tencent.mm",
            "package:com.example.disabled",
        ]

        state = mr_agent.load_package_state_from_android()

        self.assertEqual(10, state["current_user"])
        self.assertIn("com.kugou.android", state["installed"])
        self.assertEqual({"com.example.disabled"}, state["disabled"])
        commands = [call.args[0] for call in run_rish.call_args_list]
        self.assertEqual(
            [
                "am get-current-user",
                "pm list packages --user 10",
                "pm list packages -d --user 10",
            ],
            commands,
        )

    def test_installed_app_without_launcher_is_rejected(self):
        result = self.resolver(
            installed={"com.sankuai.meituan"},
            launchers={},
        ).resolve(app_name="美团")

        self.assertEqual("failed", result["status"])
        self.assertEqual("no_launcher", result["code"])

    def test_package_must_also_be_installed(self):
        result = self.resolver(
            installed=set(),
            enabled=set(),
        ).resolve(package="com.sankuai.meituan", app_name="美团")

        self.assertEqual("failed", result["status"])
        self.assertEqual("not_installed", result["code"])

    def test_invalid_package_without_label_fails_before_any_device_query(self):
        queries = []
        resolver = mr_agent.AndroidAppResolver(
            package_catalog_loader=lambda: queries.append("catalog") or [],
            package_state_loader=lambda: queries.append("state") or (set(), set()),
            launcher_resolver=lambda package: queries.append("launcher"),
        )

        result = resolver.resolve(
            package="com.foo && id",
        )

        self.assertEqual("invalid_package", result["code"])
        self.assertEqual([], queries)

    def test_wrong_package_hint_falls_back_to_correct_label(self):
        catalog = [
            {"package": "com.ss.android.ugc.aweme.lite", "label": "抖音极速版"},
        ]
        launchers = {
            "com.ss.android.ugc.aweme.lite": (
                "com.ss.android.ugc.aweme.lite/.MainActivity"
            ),
        }
        result = self.resolver(
            catalog=catalog,
            installed=set(launchers),
            launchers=launchers,
        ).resolve(
            package="com.example.wrong",
            app_name="抖音极速版",
        )

        self.assertEqual("resolved", result["status"])
        self.assertEqual("com.ss.android.ugc.aweme.lite", result["package"])
        self.assertTrue(result["packageFallbackUsed"])
        self.assertEqual(
            "not_installed",
            result["packageHintFailure"]["code"],
        )

    def test_unicode_and_app_suffix_normalization(self):
        catalog = [
            {
                "package": "com.ss.android.ugc.aweme.lite",
                "label": " 抖音　极速版（ＡＰＰ） ",
            },
        ]
        launchers = {
            "com.ss.android.ugc.aweme.lite": (
                "com.ss.android.ugc.aweme.lite/.MainActivity"
            ),
        }
        result = self.resolver(
            catalog=catalog,
            installed=set(launchers),
            launchers=launchers,
        ).resolve(app_name="抖音极速版")

        self.assertEqual("resolved", result["status"])
        self.assertEqual("com.ss.android.ugc.aweme.lite", result["package"])

    def test_wechat_and_system_settings_regression(self):
        resolver = self.resolver()

        wechat = resolver.resolve(
            package="com.tencent.mm",
            app_name="微信",
        )
        settings = resolver.resolve(
            package="com.android.settings",
            app_name="系统设置",
        )

        self.assertEqual("resolved", wechat["status"])
        self.assertEqual("resolved", settings["status"])
        self.assertEqual("com.tencent.mm", wechat["package"])
        self.assertEqual("com.android.settings", settings["package"])

    def test_portal_package_shapes_are_parsed_without_static_map(self):
        payload = {
            "status": "success",
            "result": [
                {"packageName": "com.tencent.mm", "label": "微信"},
                {
                    "package": "com.eg.android.AlipayGphone",
                    "appName": "支付宝",
                },
                {"com.sankuai.meituan": "美团"},
            ],
        }

        entries = mr_agent._extract_portal_package_entries(payload)
        self.assertIn(
            {"package": "com.tencent.mm", "label": "微信"},
            entries,
        )
        self.assertIn(
            {
                "package": "com.eg.android.AlipayGphone",
                "label": "支付宝",
            },
            entries,
        )
        self.assertIn(
            {"package": "com.sankuai.meituan", "label": "美团"},
            entries,
        )


class LaunchFallbackTest(unittest.TestCase):
    RESOLVED = {
        "status": "resolved",
        "package": "com.sankuai.meituan",
        "label": "美团",
        "launcherActivity": "com.sankuai.meituan/.activity.MainActivity",
    }

    def test_am_start_success_requires_matching_foreground(self):
        calls = []

        def rish_launcher(package, launcher):
            calls.append((package, launcher))
            return "Starting"

        result = mr_agent.launch_resolved_app(
            self.RESOLVED,
            rish_launcher=rish_launcher,
            portal_launcher=lambda package: self.fail("Portal should not run"),
            foreground_getter=lambda: "com.sankuai.meituan",
            sleep_fn=lambda _: None,
            verification_attempts=1,
        )

        self.assertEqual("success", result["status"])
        self.assertEqual("am_start", result["launchMethod"])
        self.assertTrue(result["verifiedForeground"])
        self.assertEqual(
            [("com.sankuai.meituan", self.RESOLVED["launcherActivity"])],
            calls,
        )

    def test_am_start_failure_falls_back_to_monkey(self):
        calls = []

        def rish_launcher(package, launcher):
            calls.append((package, launcher))
            if launcher is not None:
                raise RuntimeError("am start failed")
            return "Events injected: 1"

        result = mr_agent.launch_resolved_app(
            self.RESOLVED,
            rish_launcher=rish_launcher,
            portal_launcher=lambda package: self.fail("Portal should not run"),
            foreground_getter=lambda: "com.sankuai.meituan",
            sleep_fn=lambda _: None,
            verification_attempts=1,
        )

        self.assertEqual("success", result["status"])
        self.assertEqual("monkey", result["launchMethod"])
        self.assertEqual(
            [
                ("com.sankuai.meituan", self.RESOLVED["launcherActivity"]),
                ("com.sankuai.meituan", None),
            ],
            calls,
        )

    def test_other_foreground_never_reports_success(self):
        portal_calls = []
        result = mr_agent.launch_resolved_app(
            self.RESOLVED,
            rish_launcher=lambda package, launcher: "executed",
            portal_launcher=lambda package: (
                portal_calls.append(package)
                or {"status": "success"}
            ),
            foreground_getter=lambda: "com.android.settings",
            sleep_fn=lambda _: None,
            verification_attempts=1,
        )

        self.assertEqual("failed", result["status"])
        self.assertFalse(result["verifiedForeground"])
        self.assertEqual(["com.sankuai.meituan"], portal_calls)
        self.assertEqual(3, len(result["attempts"]))

    def test_portal_is_final_fallback_and_still_requires_foreground(self):
        portal_calls = []

        def fail_rish(package, launcher):
            raise RuntimeError("rish launch failed")

        result = mr_agent.launch_resolved_app(
            self.RESOLVED,
            rish_launcher=fail_rish,
            portal_launcher=lambda package: (
                portal_calls.append(package)
                or {"status": "success"}
            ),
            foreground_getter=lambda: "com.sankuai.meituan",
            sleep_fn=lambda _: None,
            verification_attempts=1,
        )

        self.assertEqual("success", result["status"])
        self.assertEqual("mobilerun_portal", result["launchMethod"])
        self.assertTrue(result["verifiedForeground"])
        self.assertEqual(["com.sankuai.meituan"], portal_calls)


class GlmTruncationTest(unittest.TestCase):
    class FakeResponse:
        def __init__(self, payload):
            self.payload = payload

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, traceback):
            return False

        def read(self):
            return json.dumps(self.payload).encode("utf-8")

    @mock.patch.object(mr_agent, "get_foreground_app", return_value="com.kugou.android")
    @mock.patch.object(mr_agent.urllib.request, "urlopen")
    def test_finish_reason_length_is_classified_before_parser(
        self,
        urlopen,
        _foreground,
    ):
        urlopen.return_value = self.FakeResponse({
            "choices": [
                {
                    "finish_reason": "length",
                    "message": {"content": ""},
                },
            ],
        })

        result = mr_agent.call_glm(
            "fake-image",
            1,
            [],
            False,
            "在酷狗搜索歌曲",
        )

        self.assertEqual("truncated_response", result["status"])
        request = urlopen.call_args.args[0]
        payload = json.loads(request.data.decode("utf-8"))
        self.assertEqual(mr_agent.GLM_MAX_TOKENS, payload["max_tokens"])
        self.assertEqual(1024, payload["max_tokens"])

    @mock.patch.dict(
        mr_agent.os.environ,
        {
            "MOBILERUN_TOKEN": "fake-portal-token",
            "ZHIPU_API_KEY": "fake-glm-key",
        },
    )
    @mock.patch.object(mr_agent.time, "sleep", return_value=None)
    @mock.patch.object(mr_agent, "get_screenshot", return_value="fake-image")
    @mock.patch.object(mr_agent, "parse_action")
    @mock.patch.object(mr_agent, "call_glm")
    def test_truncation_uses_finite_special_retry_and_never_calls_parser(
        self,
        call_glm,
        parse_action,
        _screenshot,
        _sleep,
    ):
        call_glm.return_value = {
            "status": "truncated_response",
            "reason": "finish_reason=length, content_length=0",
        }

        with mock.patch.object(mr_agent, "MAX_STEPS", 1):
            result = mr_agent.run_agent("查看当前页面")

        self.assertEqual("failed", result["status"])
        self.assertIn("T. truncated_response", result["reason"])
        self.assertEqual(2, call_glm.call_count)
        parse_action.assert_not_called()
        second_retry = call_glm.call_args_list[1].kwargs["retry_feedback"]
        self.assertIn("T. truncated_response", second_retry)


if __name__ == "__main__":
    unittest.main()
