#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import json
import sys
import unittest
from dataclasses import fields
from pathlib import Path


SCRIPT_PATH = Path(__file__).with_name("proxy_server.py")
MODULE_NAME = "proxy_server_under_test"
SPEC = importlib.util.spec_from_file_location(MODULE_NAME, SCRIPT_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"failed to load spec for {SCRIPT_PATH}")
PROXY_SERVER = importlib.util.module_from_spec(SPEC)
sys.modules[MODULE_NAME] = PROXY_SERVER
SPEC.loader.exec_module(PROXY_SERVER)


class FakeResponse:
    def __init__(self, status_code: int, body):
        self.status_code = status_code
        self._json_body = body if isinstance(body, (dict, list)) else None
        self._text = (
            json.dumps(body, ensure_ascii=False)
            if isinstance(body, (dict, list))
            else str(body)
        )

    @property
    def ok(self) -> bool:
        return 200 <= self.status_code < 300

    @property
    def text(self) -> str:
        return self._text

    def json(self):
        if self._json_body is None:
            raise json.JSONDecodeError("invalid json", self._text, 0)
        return self._json_body


class FakeSession:
    def __init__(self, responses: list[FakeResponse]):
        self._responses = list(responses)
        self.calls: list[dict[str, object]] = []

    def post(self, url, headers=None, json=None, timeout=None):
        self.calls.append(
            {
                "url": url,
                "headers": headers,
                "json": json,
                "timeout": timeout,
            }
        )
        if not self._responses:
            raise AssertionError("unexpected upstream call")
        return self._responses.pop(0)


class ProxyServerPlanTest(unittest.TestCase):
    def setUp(self):
        self.original_settings = PROXY_SERVER.SETTINGS
        self.original_session = PROXY_SERVER.SESSION
        self.original_adapter = PROXY_SERVER.PLANNER_ADAPTER
        self.client = PROXY_SERVER.APP.test_client()
        PROXY_SERVER.SETTINGS = self.make_settings()
        PROXY_SERVER.PLANNER_ADAPTER = PROXY_SERVER.OpenAiCompatibleAdapter()

    def tearDown(self):
        PROXY_SERVER.SETTINGS = self.original_settings
        PROXY_SERVER.SESSION = self.original_session
        PROXY_SERVER.PLANNER_ADAPTER = self.original_adapter

    def test_plan_injects_agent_control_and_returns_meta_wrapped_output(self):
        fake_session = FakeSession(
            [
                FakeResponse(
                    200,
                    {
                        "choices": [
                            {
                                "message": {
                                    "content": '{"action":"CLICK","x":500,"y":500}'
                                }
                            }
                        ]
                    },
                )
            ]
        )
        PROXY_SERVER.SESSION = fake_session

        response = self.client.post("/plan", json=self.make_payload())

        self.assertEqual(200, response.status_code)
        output = json.loads(response.get_json()["outputText"])
        self.assertEqual("CLICK", output["action"])
        self.assertEqual(540, output["x"])
        self.assertEqual(960, output["y"])
        self.assertEqual("IMAGE_PIXEL", output["_meta"]["coordSpace"])
        self.assertEqual("direct_valid", output["_meta"]["sanitizerPath"])
        self.assertEqual("openai_compatible", output["_meta"]["sourceMode"])
        self.assertEqual("qwen3-vl-plus", output["_meta"]["sourceModel"])
        self.assertEqual('{"action":"CLICK","x":500,"y":500}', output["_meta"]["rawModelAction"])

        upstream_payload = fake_session.calls[0]["json"]
        messages = upstream_payload["messages"]
        system_prompt = messages[0]["content"]
        user_text = messages[1]["content"][0]["text"]
        self.assertIn("Dynamic task context, current phase, capability limits, recent history, and failure hints appear in the user message.", system_prompt)
        self.assertNotIn("NEED_CLICK_TARGET", system_prompt)
        self.assertNotIn("ASK_USER, CLICK, DONE, SWIPE", system_prompt)
        self.assertIn("Current phase: NEED_CLICK_TARGET", user_text)
        self.assertIn("Target hint: 语燕输入法", user_text)
        self.assertIn("Capabilities:", user_text)
        self.assertIn("History:", user_text)
        self.assertIn("Recent control action: step=2 type=CLICK result=SUCCESS_NO_CHANGE", user_text)

    def test_done_only_prompt_constrains_output_and_meta_uses_none_coord_space(self):
        fake_session = FakeSession(
            [
                FakeResponse(
                    200,
                    {
                        "choices": [
                            {
                                "message": {
                                    "content": '{"action":"DONE","reason":"task_complete"}'
                                }
                            }
                        ]
                    },
                )
            ]
        )
        PROXY_SERVER.SESSION = fake_session

        response = self.client.post("/plan", json=self.make_payload(done_only=True))

        self.assertEqual(200, response.status_code)
        output = json.loads(response.get_json()["outputText"])
        self.assertEqual("DONE", output["action"])
        self.assertEqual("task_complete", output["reason"])
        self.assertEqual("NONE", output["_meta"]["coordSpace"])

        system_prompt = fake_session.calls[0]["json"]["messages"][0]["content"]
        user_text = fake_session.calls[0]["json"]["messages"][1]["content"][0]["text"]
        self.assertNotIn("Only return DONE or ASK_USER.", system_prompt)
        self.assertIn("Phase guidance: The system has already marked the target as completed. Only return DONE or ASK_USER.", user_text)

    def test_android_legacy_prompt_fields_are_not_forwarded_to_upstream_prompt(self):
        fake_session = FakeSession(
            [
                FakeResponse(
                    200,
                    {
                        "choices": [
                            {
                                "message": {
                                    "content": '{"action":"DONE","reason":"task_complete"}'
                                }
                            }
                        ]
                    },
                )
            ]
        )
        PROXY_SERVER.SESSION = fake_session

        response = self.client.post(
            "/plan",
            json=self.make_payload(
                system_prompt="LEGACY_SYSTEM_DO_NOT_FORWARD",
                user_prompt="LEGACY_USER_DO_NOT_FORWARD",
            ),
        )

        self.assertEqual(200, response.status_code)
        messages = fake_session.calls[0]["json"]["messages"]
        system_prompt = messages[0]["content"]
        user_text = messages[1]["content"][0]["text"]
        self.assertNotIn("LEGACY_SYSTEM_DO_NOT_FORWARD", system_prompt)
        self.assertNotIn("LEGACY_USER_DO_NOT_FORWARD", system_prompt)
        self.assertNotIn("LEGACY_SYSTEM_DO_NOT_FORWARD", user_text)
        self.assertNotIn("LEGACY_USER_DO_NOT_FORWARD", user_text)

    def test_ask_user_chinese_output_is_sanitized_as_valid_json(self):
        sanitized = PROXY_SERVER.sanitize_model_output(
            '{"action":"ASK_USER","question":"请提供要输入的搜索内容。"}',
            {"width": 1080, "height": 1920},
            source_model="qwen3-vl-plus",
            source_mode="openai_compatible",
        )

        self.assertIsNotNone(sanitized)
        parsed = json.loads(sanitized.output_text)
        self.assertEqual("ASK_USER", parsed["action"])
        self.assertEqual("请提供要输入的搜索内容。", parsed["question"])
        self.assertEqual("NONE", parsed["_meta"]["coordSpace"])
        self.assertEqual("direct_valid", sanitized.sanitizer_path)
        self.assertEqual("direct_valid", parsed["_meta"]["sanitizerPath"])
        self.assertEqual(
            '{"action":"ASK_USER","question":"请提供要输入的搜索内容。"}',
            parsed["_meta"]["rawModelAction"],
        )

    def test_sanitize_model_output_repairs_legacy_text_and_attaches_meta(self):
        sanitized = PROXY_SERVER.sanitize_model_output(
            "action: CLICK point: 12, 34",
            {"width": 1080, "height": 1920},
            source_model="mock-model",
            source_mode="openai_compatible",
        )

        self.assertIsNotNone(sanitized)
        parsed = json.loads(sanitized.output_text)
        self.assertEqual({"action": "CLICK", "x": 13, "y": 65}, {k: parsed[k] for k in ("action", "x", "y")})
        self.assertEqual("IMAGE_PIXEL", parsed["_meta"]["coordSpace"])
        self.assertEqual("legacy_parse", sanitized.sanitizer_path)
        self.assertEqual("legacy_parse", parsed["_meta"]["sanitizerPath"])
        self.assertEqual("mock-model", parsed["_meta"]["sourceModel"])
        self.assertEqual("openai_compatible", parsed["_meta"]["sourceMode"])
        self.assertEqual("action: CLICK point: 12, 34", parsed["_meta"]["rawModelAction"])

    def test_upstream_error_is_returned_as_gateway_failure(self):
        PROXY_SERVER.SESSION = FakeSession(
            [FakeResponse(503, {"error": {"code": "UPSTREAM_FAIL", "message": "try later"}})]
        )

        response = self.client.post("/plan", json=self.make_payload())

        self.assertEqual(502, response.status_code)
        body = response.get_json()
        self.assertEqual("UPSTREAM_FAIL", body["code"])
        self.assertEqual("try later", body["message"])

    def make_settings(self, **overrides):
        values = {
            field.name: getattr(self.original_settings, field.name)
            for field in fields(PROXY_SERVER.Settings)
        }
        values.update(overrides)
        values.setdefault("proxy_host", "127.0.0.1")
        values.setdefault("proxy_port", 8080)
        values.setdefault("upstream_base_url", "https://example.com/v1")
        values.setdefault("upstream_chat_path", "/chat/completions")
        values.setdefault("upstream_model", "qwen3-vl-plus")
        values.setdefault("upstream_timeout_seconds", 30.0)
        values.setdefault("upstream_api_key", "dummy")
        values.setdefault("log_body_chars", 400)
        values.setdefault("log_base64_head_chars", 16)
        return PROXY_SERVER.Settings(**values)

    def make_payload(self, *, done_only: bool = False, system_prompt: str = "", user_prompt: str = "打开设置里的应用选项，再点击语燕输入法"):
        return {
            "sessionId": "agenthost-session-1",
            "step": 3,
            "maxResponseChars": 512,
            "systemPrompt": system_prompt,
            "userPrompt": user_prompt,
            "context": {
                "sessionId": "agenthost-session-1",
                "command": "打开设置里的应用选项，再点击语燕输入法",
                "vdDisplayId": 2,
                "sessionTaskId": 101,
                "step": 3,
                "capabilitySummary": "CLICK, SWIPE, WAIT, ASK_USER, DONE",
                "capabilityProfile": {
                    "captureSupported": True,
                    "clickSupported": True,
                    "swipeSupported": True,
                    "longPressSupported": False,
                    "backSupported": True,
                    "homeSupported": False,
                    "textSupportLevel": "ASCII_BASIC",
                    "askUserSupported": True,
                    "doneSupported": True,
                    "waitSupported": True,
                },
                "observation": {
                    "status": "CAPTURED",
                    "base64Png": "ZmFrZS1pbWFnZQ==",
                    "width": 1080,
                    "height": 1920,
                    "code": None,
                    "message": None,
                },
                "recentActions": [
                    {"step": 1, "actionType": "CLICK", "code": "OK", "summary": "tap 应用"},
                    {"step": 2, "actionType": "CLICK", "code": "OK", "summary": "tap 空白"},
                ],
                "agentControl": {
                    "globalTask": "打开设置里的应用选项，再点击语燕输入法",
                    "currentPhase": "DONE_ONLY" if done_only else "NEED_CLICK_TARGET",
                    "parentHint": "设置里的应用选项",
                    "targetHint": "语燕输入法",
                    "allowedActions": ["ASK_USER", "DONE"] if done_only else ["ASK_USER", "CLICK", "DONE", "SWIPE"],
                    "blockedActions": ["BACK", "CLICK", "HOME", "SWIPE"] if done_only else ["BACK", "HOME"],
                    "recentActions": [
                        {
                            "step": 2,
                            "actionType": "CLICK",
                            "resultCategory": "SUCCESS_NO_CHANGE",
                            "summary": "tap 空白，没有变化",
                            "approxPoint": {"x": 511, "y": 1022},
                            "phaseAfter": "NEED_CLICK_TARGET",
                        }
                    ],
                    "lastResult": "SUCCESS_NO_CHANGE",
                    "completionState": "SATISFIED" if done_only else "NOT_SATISFIED",
                    "doneOnly": done_only,
                },
                "lastFailureCode": None,
                "lastUserReply": None,
            },
        }


if __name__ == "__main__":
    unittest.main()
