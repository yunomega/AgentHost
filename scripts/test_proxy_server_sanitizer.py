#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import json
import sys
import unittest
from pathlib import Path


SCRIPT_PATH = Path(__file__).with_name("proxy_server.py")
MODULE_NAME = "proxy_server_sanitizer_under_test"
SPEC = importlib.util.spec_from_file_location(MODULE_NAME, SCRIPT_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"failed to load spec for {SCRIPT_PATH}")
PROXY_SERVER = importlib.util.module_from_spec(SPEC)
sys.modules[MODULE_NAME] = PROXY_SERVER
SPEC.loader.exec_module(PROXY_SERVER)


class ProxyServerSanitizerTest(unittest.TestCase):
    def test_direct_valid_output_is_tagged_and_scaled(self):
        sanitized = PROXY_SERVER.sanitize_model_output(
            '{"action":"SWIPE","x1":500,"y1":750,"x2":500,"y2":250,"durationMs":400}',
            {"width": 1080, "height": 1920},
            source_model="mock-model",
            source_mode="openai_compatible",
        )

        self.assertIsNotNone(sanitized)
        parsed = json.loads(sanitized.output_text)
        self.assertEqual("direct_valid", sanitized.sanitizer_path)
        self.assertEqual("direct_valid", parsed["_meta"]["sanitizerPath"])
        self.assertEqual("SWIPE", parsed["action"])
        self.assertEqual(540, parsed["x1"])
        self.assertEqual(1440, parsed["y1"])
        self.assertEqual(540, parsed["x2"])
        self.assertEqual(480, parsed["y2"])
        self.assertEqual(400, parsed["durationMs"])
        self.assertEqual("IMAGE_PIXEL", parsed["_meta"]["coordSpace"])

    def test_json_extract_output_is_tagged(self):
        sanitized = PROXY_SERVER.sanitize_model_output(
            'Here is the action:\n```json\n{"action":"DONE","reason":"task_complete"}\n```',
            {"width": 1080, "height": 1920},
            source_model="mock-model",
            source_mode="openai_compatible",
        )

        self.assertIsNotNone(sanitized)
        parsed = json.loads(sanitized.output_text)
        self.assertEqual("json_extract", sanitized.sanitizer_path)
        self.assertEqual("json_extract", parsed["_meta"]["sanitizerPath"])
        self.assertEqual("DONE", parsed["action"])
        self.assertEqual("task_complete", parsed["reason"])
        self.assertEqual("NONE", parsed["_meta"]["coordSpace"])

    def test_repair_click_output_with_missing_y_key_and_scale_thousandth(self):
        sanitized = PROXY_SERVER.sanitize_model_output(
            '{"action":"CLICK","x":259, 489}',
            {"width": 1080, "height": 1920},
            source_model="mock-model",
            source_mode="openai_compatible",
        )

        self.assertIsNotNone(sanitized)
        parsed = json.loads(sanitized.output_text)
        self.assertEqual("repair", sanitized.sanitizer_path)
        self.assertEqual("repair", parsed["_meta"]["sanitizerPath"])
        self.assertEqual("CLICK", parsed["action"])
        self.assertEqual(280, parsed["x"])
        self.assertEqual(939, parsed["y"])
        self.assertEqual("IMAGE_PIXEL", parsed["_meta"]["coordSpace"])

    def test_legacy_parse_output_is_tagged(self):
        sanitized = PROXY_SERVER.sanitize_model_output(
            "action: CLICK point: 12, 34",
            {"width": 1080, "height": 1920},
            source_model="mock-model",
            source_mode="openai_compatible",
        )

        self.assertIsNotNone(sanitized)
        parsed = json.loads(sanitized.output_text)
        self.assertEqual("legacy_parse", sanitized.sanitizer_path)
        self.assertEqual("legacy_parse", parsed["_meta"]["sanitizerPath"])
        self.assertEqual("CLICK", parsed["action"])
        self.assertEqual(13, parsed["x"])
        self.assertEqual(65, parsed["y"])
        self.assertEqual("IMAGE_PIXEL", parsed["_meta"]["coordSpace"])

    def test_leave_coordinates_unchanged_without_observation_size(self):
        sanitized = PROXY_SERVER.sanitize_model_output(
            '{"action":"CLICK","x":500,"y":500}',
            source_model="mock-model",
            source_mode="openai_compatible",
        )

        self.assertIsNotNone(sanitized)
        parsed = json.loads(sanitized.output_text)
        self.assertEqual({"action": "CLICK", "x": 500, "y": 500}, {k: parsed[k] for k in ("action", "x", "y")})
        self.assertEqual("direct_valid", sanitized.sanitizer_path)
        self.assertEqual("IMAGE_PIXEL", parsed["_meta"]["coordSpace"])

    def test_does_not_repair_non_click_broken_json(self):
        sanitized = PROXY_SERVER.sanitize_model_output(
            '{"action":"TYPE_TEXT","text":"abc", 489}',
            {"width": 1080, "height": 1920},
            source_model="mock-model",
            source_mode="openai_compatible",
        )

        self.assertIsNone(sanitized)


if __name__ == "__main__":
    unittest.main()
