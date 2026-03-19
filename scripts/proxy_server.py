#!/usr/bin/env python3

"""Local proxy gateway for AgentHost -> OpenAI-compatible upstream API.

Usage:
  pip install flask requests
  python3 scripts/proxy_server.py

Environment variables:
  PROXY_HOST=0.0.0.0
  PROXY_PORT=8080
  UPSTREAM_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
  UPSTREAM_CHAT_PATH=/chat/completions
  UPSTREAM_MODEL=qwen3-vl-plus
  UPSTREAM_TIMEOUT_SECONDS=30
  UPSTREAM_API_KEY=
  DASHSCOPE_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
  DASHSCOPE_CHAT_PATH=/chat/completions
  DASHSCOPE_MODEL=qwen3-vl-plus
  DASHSCOPE_API_KEY=
  PROXY_LOG_BODY_CHARS=400
  PROXY_LOG_BASE64_HEAD_CHARS=32
"""

from __future__ import annotations

import json
import os
import re
from dataclasses import dataclass
from datetime import datetime, timezone
from http import HTTPStatus
from typing import Any
from urllib.parse import urljoin

import requests
from flask import Flask, jsonify, request


@dataclass(frozen=True)
class Settings:
    proxy_host: str
    proxy_port: int
    upstream_base_url: str
    upstream_chat_path: str
    upstream_model: str
    upstream_timeout_seconds: float
    upstream_api_key: str
    log_body_chars: int
    log_base64_head_chars: int

    @property
    def upstream_chat_url(self) -> str:
        base = self.upstream_base_url.rstrip("/") + "/"
        path = self.upstream_chat_path.lstrip("/")
        return urljoin(base, path)


@dataclass(frozen=True)
class GatewayError:
    status: int
    code: str
    message: str
    details: str | None = None
    upstream_status: int | None = None
    raw_response_snippet: str | None = None

    def to_response(self):
        body: dict[str, Any] = {
            "code": self.code,
            "message": self.message,
        }
        if self.details:
            body["details"] = self.details
        if self.upstream_status is not None:
            body["upstreamStatus"] = self.upstream_status
        if self.raw_response_snippet:
            body["rawResponseSnippet"] = self.raw_response_snippet
        return jsonify(body), self.status


def get_env_int(name: str, default: int, minimum: int) -> int:
    raw = os.getenv(name, str(default)).strip()
    try:
        value = int(raw)
    except ValueError:
        value = default
    return max(value, minimum)


def get_env_float(name: str, default: float, minimum: float) -> float:
    raw = os.getenv(name, str(default)).strip()
    try:
        value = float(raw)
    except ValueError:
        value = default
    return max(value, minimum)


def get_first_env(*names: str, default: str) -> str:
    for name in names:
        value = os.getenv(name, "").strip()
        if value:
            return value
    return default


def get_env_float_alias(names: tuple[str, ...], default: float, minimum: float) -> float:
    for name in names:
        raw = os.getenv(name, "").strip()
        if not raw:
            continue
        try:
            return max(float(raw), minimum)
        except ValueError:
            continue
    return max(default, minimum)


def load_settings() -> Settings:
    return Settings(
        proxy_host=os.getenv("PROXY_HOST", "0.0.0.0").strip() or "0.0.0.0",
        proxy_port=get_env_int("PROXY_PORT", 8080, 1),
        upstream_base_url=get_first_env(
            "UPSTREAM_BASE_URL",
            "DASHSCOPE_BASE_URL",
            default="https://dashscope.aliyuncs.com/compatible-mode/v1",
        ),
        upstream_chat_path=get_first_env(
            "UPSTREAM_CHAT_PATH",
            "DASHSCOPE_CHAT_PATH",
            default="/chat/completions",
        ),
        upstream_model=get_first_env(
            "UPSTREAM_MODEL",
            "DASHSCOPE_MODEL",
            default="qwen3-vl-plus",
        ),
        upstream_timeout_seconds=get_env_float_alias(
            ("UPSTREAM_TIMEOUT_SECONDS", "DASHSCOPE_TIMEOUT_SECONDS"),
            30.0,
            1.0,
        ),
        upstream_api_key=get_first_env(
            "UPSTREAM_API_KEY",
            "DASHSCOPE_API_KEY",
            default="",
        ),
        log_body_chars=get_env_int("PROXY_LOG_BODY_CHARS", 400, 64),
        log_base64_head_chars=get_env_int("PROXY_LOG_BASE64_HEAD_CHARS", 32, 0),
    )


SETTINGS = load_settings()
APP = Flask(__name__)
SESSION = requests.Session()


def utc_timestamp() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def log_line(message: str) -> None:
    print(f"[{utc_timestamp()}] {message}", flush=True)


def truncate_text(value: Any, max_chars: int) -> Any:
    if not isinstance(value, str):
        return value
    if len(value) <= max_chars:
        return value
    return value[:max_chars] + "…"


def summarize_base64(value: Any, head_chars: int) -> Any:
    if value is None:
        return None
    if not isinstance(value, str):
        return {"type": type(value).__name__}
    if not value:
        return {"length": 0, "preview": ""}
    head = max(head_chars, 0)
    tail = min(16, len(value))
    if len(value) <= head + tail + 3:
        preview = value
    else:
        preview = f"{value[:head]}...{value[-tail:]}"
    return {"length": len(value), "preview": preview}


def summarize_recent_actions(actions: Any) -> dict[str, Any]:
    if not isinstance(actions, list):
        return {"count": 0, "last": None}
    last_entry = actions[-1] if actions else None
    if not isinstance(last_entry, dict):
        return {"count": len(actions), "last": truncate_text(last_entry, 120)}
    return {
        "count": len(actions),
        "last": {
            "step": last_entry.get("step"),
            "actionType": last_entry.get("actionType"),
            "code": last_entry.get("code"),
            "summary": truncate_text(last_entry.get("summary"), 120),
        },
    }


def summarize_capability_profile(capability_profile: Any, max_chars: int) -> dict[str, Any]:
    if not isinstance(capability_profile, dict):
        return {"present": False}
    return {
        "present": True,
        "profileKeys": sorted(capability_profile.keys()),
        "profileJson": truncate_text(compact_json(capability_profile), max_chars),
    }


def summarize_agent_control(agent_control: Any) -> dict[str, Any]:
    if not isinstance(agent_control, dict):
        return {"present": False}
    recent_actions = agent_control.get("recentActions")
    if not isinstance(recent_actions, list):
        recent_actions = []
    last = recent_actions[-1] if recent_actions else None
    return {
        "present": True,
        "currentPhase": agent_control.get("currentPhase"),
        "allowedActions": agent_control.get("allowedActions") if isinstance(agent_control.get("allowedActions"), list) else [],
        "blockedActions": agent_control.get("blockedActions") if isinstance(agent_control.get("blockedActions"), list) else [],
        "lastResult": agent_control.get("lastResult"),
        "completionState": agent_control.get("completionState"),
        "doneOnly": bool(agent_control.get("doneOnly")),
        "recentActionCount": len(recent_actions),
        "recentLast": truncate_text(compact_json(last), 200) if isinstance(last, dict) else truncate_text(last, 120),
    }


def build_request_summary(payload: Any, base64_head_chars: int) -> dict[str, Any]:
    if not isinstance(payload, dict):
        return {"payloadType": type(payload).__name__}

    context = payload.get("context")
    if not isinstance(context, dict):
        context = {}
    observation = context.get("observation")
    if not isinstance(observation, dict):
        observation = {}
    agent_control = context.get("agentControl")

    return {
        "sessionId": payload.get("sessionId"),
        "step": payload.get("step"),
        "userPrompt": truncate_text(payload.get("userPrompt"), 180),
        "context": {
            "command": truncate_text(context.get("command"), 180),
            "step": context.get("step"),
            "capabilitySummary": truncate_text(context.get("capabilitySummary"), 180),
            "capabilityProfile": summarize_capability_profile(
                context.get("capabilityProfile"),
                240,
            ),
            "observation": {
                "status": observation.get("status"),
                "width": observation.get("width"),
                "height": observation.get("height"),
                "code": observation.get("code"),
                "message": truncate_text(observation.get("message"), 120),
                "base64Png": summarize_base64(
                    observation.get("base64Png"), base64_head_chars
                ),
            },
            "recentActions": summarize_recent_actions(context.get("recentActions")),
            "agentControl": summarize_agent_control(agent_control),
            "lastFailureCode": context.get("lastFailureCode"),
            "lastUserReply": truncate_text(context.get("lastUserReply"), 80),
        },
    }


def compact_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def build_capability_hint(
    capability_profile: dict[str, Any],
) -> tuple[list[str], list[str], list[str]]:
    allowed: list[str] = []
    blocked: list[str] = []
    unknown: list[str] = []

    mapping = [
        ("clickSupported", "CLICK"),
        ("longPressSupported", "LONG_PRESS"),
        ("swipeSupported", "SWIPE"),
        ("backSupported", "BACK"),
        ("homeSupported", "HOME"),
        ("askUserSupported", "ASK_USER"),
        ("doneSupported", "DONE"),
        ("waitSupported", "WAIT"),
    ]
    for key, action in mapping:
        value = capability_profile.get(key, None)
        if value is True:
            allowed.append(action)
        elif value is False:
            blocked.append(action)
        else:
            unknown.append(action)

    text_level_raw = capability_profile.get("textSupportLevel")
    text_level = str(text_level_raw or "").strip().upper()
    if text_level and text_level != "NONE":
        allowed.append("TYPE_TEXT")
    elif text_level == "NONE":
        blocked.append("TYPE_TEXT")
    else:
        unknown.append("TYPE_TEXT")

    return allowed, blocked, unknown


def normalize_image_data_url(value: str) -> str:
    stripped = value.strip()
    if stripped.startswith("data:image/"):
        return stripped
    return f"data:image/png;base64,{stripped}"


def build_system_prompt(payload: dict[str, Any]) -> str:
    context = payload.get("context") if isinstance(payload.get("context"), dict) else {}
    capability_profile = (
        context.get("capabilityProfile")
        if isinstance(context.get("capabilityProfile"), dict)
        else {}
    )
    agent_control = (
        context.get("agentControl")
        if isinstance(context.get("agentControl"), dict)
        else {}
    )
    allowed_actions, blocked_actions, unknown_actions = build_capability_hint(
        capability_profile
    )

    lines = [
        "You are a GUI Agent acting for Android AgentHost.",
        "Return exactly one raw JSON object and nothing else.",
        "Do not output markdown, code fences, explanations, natural language, or extra text.",
        "Do not output arrays, multiple actions, or actions/steps containers.",
        "The JSON object must be directly parseable by a strict Android single-action parser.",
        "For CLICK, LONG_PRESS, and SWIPE, output coordinates in thousandths of the screenshot size (0-1000), not absolute pixels.",
        "Supported action names in this protocol are: CLICK, LONG_PRESS, SWIPE, TYPE_TEXT, BACK, HOME, WAIT, ASK_USER, DONE.",
        "Use the current screenshot as the main source of truth.",
        "If the requested destination, page, toggle, or list item is already open or clearly visible, return DONE immediately.",
        "Do not backtrack, reopen search, or navigate to a broader page when the screenshot already shows the target.",
        "Do not loop on the same goal: avoid repeating the same or nearby CLICK unless the screenshot clearly justifies it.",
        "Only use TYPE_TEXT when text entry is clearly required and the target is not already visible on screen.",
        f"Allowed actions for this request: {', '.join(allowed_actions) if allowed_actions else 'NONE'}.",
        f"Blocked or unsupported actions for this request: {', '.join(blocked_actions) if blocked_actions else 'NONE'}.",
        f"Unknown or unspecified actions for this request: {', '.join(unknown_actions) if unknown_actions else 'NONE'}.",
        f"Capability summary: {context.get('capabilitySummary') or 'n/a'}",
        f"Capability profile JSON: {compact_json(capability_profile) if capability_profile else '{}'}",
        'Example valid output: {"action":"CLICK","x":500,"y":500}',
        'Example valid output: {"action":"SWIPE","x1":500,"y1":800,"x2":500,"y2":300,"durationMs":400}',
        'Example valid output: {"action":"TYPE_TEXT","text":"hello123"}',
        'Example valid output: {"action":"ASK_USER","question":"Please provide the verification code."}',
        'Example valid output: {"action":"DONE","reason":"task_complete"}',
    ]

    control_phase = str(agent_control.get("currentPhase") or "").strip()
    if control_phase:
        lines.append(f"Current control phase: {control_phase}.")
    control_allowed = agent_control.get("allowedActions")
    if isinstance(control_allowed, list) and control_allowed:
        lines.append(f"System-allowed actions now: {', '.join(str(item) for item in control_allowed)}.")
    control_blocked = agent_control.get("blockedActions")
    if isinstance(control_blocked, list) and control_blocked:
        lines.append(f"System-blocked actions now: {', '.join(str(item) for item in control_blocked)}.")
    if agent_control.get("doneOnly") is True:
        lines.append("The system has already marked the target as completed. Only return DONE or ASK_USER.")
    elif control_phase == "NEED_OPEN_PARENT":
        lines.append("Focus on opening the parent destination first. Avoid exploratory backtracking.")
    elif control_phase == "NEED_CLICK_TARGET":
        lines.append("Focus on clicking the target item once or scrolling to find it. Avoid BACK and HOME.")
    elif control_phase == "TARGET_CLICKED_PENDING_SETTLE":
        lines.append("The target may already be complete. Prefer DONE, ASK_USER, or WAIT. Do not keep clicking.")

    text_level = str(capability_profile.get("textSupportLevel") or "").strip().upper()
    if text_level == "ASCII_BASIC":
        lines.append(
            "TYPE_TEXT is limited to printable ASCII only (U+0020-U+007E plus tab/newline/carriage return)."
        )
        lines.append(
            "Do not output Chinese or any other non-ASCII characters in TYPE_TEXT.text."
        )
        lines.append(
            "If non-ASCII text would be needed, choose another allowed action instead of TYPE_TEXT."
        )

    last_failure_code = context.get("lastFailureCode")
    if last_failure_code:
        lines.append(f"Last failure code: {last_failure_code}")
    last_user_reply = context.get("lastUserReply")
    if last_user_reply:
        lines.append(f"Last user reply: {last_user_reply}")

    return "\n".join(lines)


def build_user_text(payload: dict[str, Any]) -> str:
    context = payload.get("context") if isinstance(payload.get("context"), dict) else {}
    observation = (
        context.get("observation") if isinstance(context.get("observation"), dict) else {}
    )
    recent_actions = context.get("recentActions") if isinstance(context.get("recentActions"), list) else []
    agent_control = (
        context.get("agentControl") if isinstance(context.get("agentControl"), dict) else {}
    )

    lines = [
        f"User command: {context.get('command') or payload.get('userPrompt') or ''}",
        f"Step: {payload.get('step')}",
        f"SessionId: {payload.get('sessionId')}",
        f"Observation status: {observation.get('status') or 'UNKNOWN'}",
    ]

    width = observation.get("width")
    height = observation.get("height")
    if width not in (None, -1) and height not in (None, -1):
        lines.append(f"Observation size: {width}x{height}")

    if observation.get("code"):
        lines.append(f"Observation code: {observation.get('code')}")
    if observation.get("message"):
        lines.append(f"Observation message: {observation.get('message')}")
    if agent_control.get("currentPhase"):
        lines.append(f"Current phase: {agent_control.get('currentPhase')}")
    if agent_control.get("parentHint"):
        lines.append(f"Parent hint: {agent_control.get('parentHint')}")
    if agent_control.get("targetHint"):
        lines.append(f"Target hint: {agent_control.get('targetHint')}")
    if agent_control.get("lastResult"):
        lines.append(f"Last result: {agent_control.get('lastResult')}")
    if agent_control.get("completionState"):
        lines.append(f"Completion state: {agent_control.get('completionState')}")
    allowed_actions = agent_control.get("allowedActions")
    if isinstance(allowed_actions, list) and allowed_actions:
        lines.append(f"Allowed actions now: {', '.join(str(item) for item in allowed_actions)}")
    blocked_actions = agent_control.get("blockedActions")
    if isinstance(blocked_actions, list) and blocked_actions:
        lines.append(f"Blocked actions now: {', '.join(str(item) for item in blocked_actions)}")
    if agent_control.get("doneOnly") is True:
        lines.append("DONE_ONLY is active: do not continue clicking or swiping.")

    if recent_actions:
        lines.append(f"Recent action count: {len(recent_actions)}")
        for action in recent_actions[-3:]:
            if not isinstance(action, dict):
                continue
            lines.append(
                "Recent action: "
                f"step={action.get('step')} type={action.get('actionType')} "
                f"code={action.get('code')} summary={action.get('summary')}"
            )

    control_recent_actions = agent_control.get("recentActions")
    if isinstance(control_recent_actions, list) and control_recent_actions:
        lines.append(f"Recent control action count: {len(control_recent_actions)}")
        for action in control_recent_actions[-4:]:
            if not isinstance(action, dict):
                continue
            point = action.get("approxPoint")
            point_summary = ""
            if isinstance(point, dict):
                point_summary = f" point=({point.get('x')},{point.get('y')})"
            lines.append(
                "Recent control action: "
                f"step={action.get('step')} type={action.get('actionType')} "
                f"result={action.get('resultCategory')} phase={action.get('phaseAfter')}{point_summary} "
                f"summary={action.get('summary')}"
            )
    if context.get("lastFailureCode"):
        lines.append(f"Last failure code: {context.get('lastFailureCode')}")
    if context.get("lastUserReply"):
        lines.append(f"Last user reply: {context.get('lastUserReply')}")

    lines.append(
        "Choose the single best next action based on the screenshot and capabilities."
    )
    return "\n".join(lines)


def build_upstream_request(payload: dict[str, Any], settings: Settings) -> dict[str, Any]:
    context = payload.get("context") if isinstance(payload.get("context"), dict) else {}
    observation = (
        context.get("observation") if isinstance(context.get("observation"), dict) else {}
    )
    base64_png = observation.get("base64Png") if isinstance(observation.get("base64Png"), str) else None

    user_content: list[dict[str, Any]] = [
        {
            "type": "text",
            "text": build_user_text(payload),
        }
    ]
    if base64_png:
        user_content.append(
            {
                "type": "image_url",
                "image_url": {
                    "url": normalize_image_data_url(base64_png),
                },
            }
        )

    return {
        "model": settings.upstream_model,
        "messages": [
            {
                "role": "system",
                "content": build_system_prompt(payload),
            },
            {
                "role": "user",
                "content": user_content,
            },
        ],
        "temperature": 0,
        "stream": False,
    }


def build_upstream_request_summary(upstream_payload: dict[str, Any], settings: Settings) -> dict[str, Any]:
    messages = upstream_payload.get("messages") if isinstance(upstream_payload.get("messages"), list) else []
    system_content = ""
    user_text = ""
    has_image = False
    for message in messages:
        if not isinstance(message, dict):
            continue
        role = message.get("role")
        content = message.get("content")
        if role == "system" and isinstance(content, str):
            system_content = content
        elif role == "user" and isinstance(content, list):
            for item in content:
                if not isinstance(item, dict):
                    continue
                if item.get("type") == "text":
                    user_text = str(item.get("text") or "")
                elif item.get("type") == "image_url":
                    has_image = True
    return {
        "model": upstream_payload.get("model"),
        "hasImage": has_image,
        "systemPrompt": truncate_text(system_content, settings.log_body_chars),
        "userText": truncate_text(user_text, settings.log_body_chars),
    }


def extract_text_from_upstream_json(data: dict[str, Any]) -> str | None:
    choices = data.get("choices")
    if not isinstance(choices, list) or not choices:
        return None
    first = choices[0]
    if not isinstance(first, dict):
        return None

    message = first.get("message")
    content: Any = None
    if isinstance(message, dict):
        content = message.get("content")
    elif "text" in first:
        content = first.get("text")

    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts: list[str] = []
        for item in content:
            if isinstance(item, str):
                parts.append(item)
                continue
            if not isinstance(item, dict):
                continue
            text = item.get("text")
            if isinstance(text, str):
                parts.append(text)
                continue
            alt = item.get("content")
            if isinstance(alt, str):
                parts.append(alt)
        joined = "\n".join(part for part in parts if part.strip())
        return joined or None
    return None


def extract_error_from_upstream_json(data: Any) -> tuple[str, str, str | None] | None:
    if not isinstance(data, dict):
        return None
    error = data.get("error")
    if not isinstance(error, dict):
        return None
    code = str(error.get("code") or "MODEL_UNAVAILABLE")
    message = str(error.get("message") or error.get("type") or "upstream returned non-success status")
    details = str(error.get("type")).strip() if error.get("type") is not None else None
    return code, message, details or None


def strip_markdown_fences(text: str) -> str:
    stripped = re.sub(r"```(?:json)?", "", text, flags=re.IGNORECASE)
    return stripped.strip()


def extract_first_json_object(text: str) -> str | None:
    index = 0
    text_length = len(text)
    while index < text_length:
        start = text.find("{", index)
        if start == -1:
            return None

        depth = 0
        in_string = False
        escape = False
        for end in range(start, text_length):
            ch = text[end]
            if in_string:
                if escape:
                    escape = False
                elif ch == "\\":
                    escape = True
                elif ch == '"':
                    in_string = False
                continue

            if ch == '"':
                in_string = True
            elif ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    candidate = text[start : end + 1]
                    try:
                        parsed = json.loads(candidate)
                    except json.JSONDecodeError:
                        index = start + 1
                        break
                    if isinstance(parsed, dict):
                        return compact_json(parsed)
                    index = start + 1
                    break
        else:
            return None
    return None


def extract_legacy_action_object(text: str) -> str | None:
    compact = re.sub(r"\s+", " ", text.strip())
    if not compact:
        return None

    click_match = re.fullmatch(
        r"action\s*:\s*CLICK\s+point\s*:\s*(-?\d+)\s*,\s*(-?\d+)",
        compact,
        flags=re.IGNORECASE,
    )
    if click_match:
        x = int(click_match.group(1))
        y = int(click_match.group(2))
        return compact_json({"action": "CLICK", "x": x, "y": y})

    wait_match = re.fullmatch(
        r"action\s*:\s*WAIT\s+value\s*:\s*(-?\d+)",
        compact,
        flags=re.IGNORECASE,
    )
    if wait_match:
        seconds = int(wait_match.group(1))
        duration_ms = max(0, seconds) * 1000
        return compact_json({"action": "WAIT", "durationMs": duration_ms})

    type_text_match = re.fullmatch(
        r"action\s*:\s*TYPE_TEXT\s+value\s*:\s*(.+)",
        compact,
        flags=re.IGNORECASE,
    )
    if type_text_match:
        text_value = type_text_match.group(1).strip()
        if not text_value:
            return None
        return compact_json({"action": "TYPE_TEXT", "text": text_value})

    return None


def extract_repaired_action_object(text: str) -> str | None:
    click_missing_y = re.search(
        r'\{\s*"action"\s*:\s*"CLICK"\s*,\s*"x"\s*:\s*(-?\d+)\s*,\s*(-?\d+)\s*\}',
        text,
        flags=re.IGNORECASE,
    )
    if click_missing_y:
        return compact_json(
            {
                "action": "CLICK",
                "x": int(click_missing_y.group(1)),
                "y": int(click_missing_y.group(2)),
            }
        )
    return None


def parse_positive_int(value: Any) -> int | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value if value > 0 else None
    if isinstance(value, float):
        integer = int(value)
        return integer if value == integer and integer > 0 else None
    return None


def parse_numeric_coordinate(value: Any) -> float | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return float(value)
    return None


def scale_thousandth_coordinate(value: Any, size: int) -> int | None:
    numeric = parse_numeric_coordinate(value)
    if numeric is None or size <= 0:
        return None
    scaled = round((numeric * size) / 1000.0)
    return max(0, min(int(scaled), size - 1))


def normalize_action_coordinates(
    action_object: dict[str, Any], observation: dict[str, Any] | None
) -> dict[str, Any]:
    if not isinstance(observation, dict):
        return action_object
    width = parse_positive_int(observation.get("width"))
    height = parse_positive_int(observation.get("height"))
    if width is None or height is None:
        return action_object

    action_name = str(action_object.get("action") or "").strip().upper()
    normalized = dict(action_object)

    if action_name in {"CLICK", "LONG_PRESS"}:
        scaled_x = scale_thousandth_coordinate(normalized.get("x"), width)
        scaled_y = scale_thousandth_coordinate(normalized.get("y"), height)
        if scaled_x is not None and scaled_y is not None:
            normalized["x"] = scaled_x
            normalized["y"] = scaled_y
        return normalized

    if action_name == "SWIPE":
        scaled_x1 = scale_thousandth_coordinate(normalized.get("x1"), width)
        scaled_y1 = scale_thousandth_coordinate(normalized.get("y1"), height)
        scaled_x2 = scale_thousandth_coordinate(normalized.get("x2"), width)
        scaled_y2 = scale_thousandth_coordinate(normalized.get("y2"), height)
        if None not in (scaled_x1, scaled_y1, scaled_x2, scaled_y2):
            normalized["x1"] = scaled_x1
            normalized["y1"] = scaled_y1
            normalized["x2"] = scaled_x2
            normalized["y2"] = scaled_y2
        return normalized

    return normalized


def normalize_sanitized_action(
    json_object: str,
    observation: dict[str, Any] | None,
    *,
    raw_model_action: str | None,
    source_model: str,
    source_mode: str,
) -> str:
    try:
        parsed = json.loads(json_object)
    except json.JSONDecodeError:
        return json_object
    if not isinstance(parsed, dict):
        return json_object
    normalized = normalize_action_coordinates(parsed, observation)
    action_name = str(normalized.get("action") or "").strip().upper()
    coord_space = "NONE"
    if action_name in {"CLICK", "LONG_PRESS", "SWIPE"}:
        coord_space = "IMAGE_PIXEL"
    existing_meta = normalized.get("_meta")
    meta = dict(existing_meta) if isinstance(existing_meta, dict) else {}
    meta.update(
        {
            "coordSpace": coord_space,
            "sourceModel": source_model,
            "sourceMode": source_mode,
            "rawModelAction": raw_model_action or json_object,
        }
    )
    normalized["_meta"] = meta
    return compact_json(normalized)


def sanitize_model_output(
    raw_text: str,
    observation: dict[str, Any] | None = None,
    *,
    source_model: str,
    source_mode: str,
) -> str | None:
    cleaned = strip_markdown_fences(raw_text)
    json_object = extract_first_json_object(cleaned)
    if json_object:
        return normalize_sanitized_action(
            json_object,
            observation,
            raw_model_action=raw_text,
            source_model=source_model,
            source_mode=source_mode,
        )
    repaired_object = extract_repaired_action_object(cleaned)
    if repaired_object:
        return normalize_sanitized_action(
            repaired_object,
            observation,
            raw_model_action=raw_text,
            source_model=source_model,
            source_mode=source_mode,
        )
    legacy_object = extract_legacy_action_object(cleaned)
    if legacy_object:
        return normalize_sanitized_action(
            legacy_object,
            observation,
            raw_model_action=raw_text,
            source_model=source_model,
            source_mode=source_mode,
        )
    return None


class UpstreamAdapter:
    name = "base"

    def build_upstream_request(self, payload: dict[str, Any], settings: Settings) -> dict[str, Any]:
        raise NotImplementedError

    def build_upstream_request_summary(
        self, upstream_payload: dict[str, Any], settings: Settings
    ) -> dict[str, Any]:
        raise NotImplementedError

    def extract_model_text(self, upstream_json: dict[str, Any]) -> str | None:
        raise NotImplementedError

    def sanitize_output(
        self, model_text: str, payload: dict[str, Any], settings: Settings
    ) -> str | None:
        raise NotImplementedError


class OpenAiCompatibleAdapter(UpstreamAdapter):
    name = "openai_compatible"

    def build_upstream_request(self, payload: dict[str, Any], settings: Settings) -> dict[str, Any]:
        return build_upstream_request(payload, settings)

    def build_upstream_request_summary(
        self, upstream_payload: dict[str, Any], settings: Settings
    ) -> dict[str, Any]:
        return build_upstream_request_summary(upstream_payload, settings)

    def extract_model_text(self, upstream_json: dict[str, Any]) -> str | None:
        return extract_text_from_upstream_json(upstream_json)

    def sanitize_output(
        self, model_text: str, payload: dict[str, Any], settings: Settings
    ) -> str | None:
        context = payload.get("context") if isinstance(payload.get("context"), dict) else {}
        observation = (
            context.get("observation") if isinstance(context.get("observation"), dict) else {}
        )
        return sanitize_model_output(
            model_text,
            observation,
            source_model=settings.upstream_model,
            source_mode=self.name,
        )


PLANNER_ADAPTER: UpstreamAdapter = OpenAiCompatibleAdapter()


def build_error_response(
    status: int,
    code: str,
    message: str,
    *,
    details: str | None = None,
    upstream_status: int | None = None,
    raw_response_snippet: str | None = None,
):
    error = GatewayError(
        status=status,
        code=code,
        message=message,
        details=details,
        upstream_status=upstream_status,
        raw_response_snippet=raw_response_snippet,
    )
    return error.to_response()


def build_no_json_response(raw_text: str, upstream_status: int | None) -> Any:
    return build_error_response(
        status=HTTPStatus.BAD_GATEWAY,
        code="MODEL_OUTPUT_INVALID",
        message="upstream response did not contain a valid JSON object",
        upstream_status=upstream_status,
        raw_response_snippet=truncate_text(raw_text, SETTINGS.log_body_chars),
    )


def post_to_upstream(upstream_payload: dict[str, Any], settings: Settings) -> requests.Response:
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json",
    }
    if settings.upstream_api_key:
        headers["Authorization"] = f"Bearer {settings.upstream_api_key}"
    return SESSION.post(
        settings.upstream_chat_url,
        headers=headers,
        json=upstream_payload,
        timeout=settings.upstream_timeout_seconds,
    )


@APP.get("/healthz")
def healthz():
    summary = {
        "ok": True,
        "adapter": PLANNER_ADAPTER.name,
        "proxyHost": SETTINGS.proxy_host,
        "proxyPort": SETTINGS.proxy_port,
        "upstreamBaseUrl": SETTINGS.upstream_base_url,
        "upstreamChatUrl": SETTINGS.upstream_chat_url,
        "model": SETTINGS.upstream_model,
        "timeoutSeconds": SETTINGS.upstream_timeout_seconds,
        "apiKeyConfigured": bool(SETTINGS.upstream_api_key),
    }
    return jsonify(summary), HTTPStatus.OK


@APP.post("/plan")
def plan():
    payload = request.get_json(silent=True)
    if not isinstance(payload, dict):
        return build_error_response(
            status=HTTPStatus.BAD_REQUEST,
            code="BAD_REQUEST",
            message="request body must be a JSON object",
        )

    request_summary = build_request_summary(payload, SETTINGS.log_base64_head_chars)
    log_line(f"request summary {compact_json(request_summary)}")

    upstream_payload = PLANNER_ADAPTER.build_upstream_request(payload, SETTINGS)
    upstream_summary = PLANNER_ADAPTER.build_upstream_request_summary(upstream_payload, SETTINGS)
    log_line(f"upstream request summary {compact_json(upstream_summary)}")

    try:
        upstream_response = post_to_upstream(upstream_payload, SETTINGS)
    except requests.Timeout:
        log_line("upstream request failed code=MODEL_TIMEOUT details=timed out")
        return build_error_response(
            status=HTTPStatus.GATEWAY_TIMEOUT,
            code="MODEL_TIMEOUT",
            message="upstream request timed out",
        )
    except requests.ConnectionError as exc:
        log_line(
            f"upstream request failed code=MODEL_UNAVAILABLE details={truncate_text(str(exc), SETTINGS.log_body_chars)}"
        )
        return build_error_response(
            status=HTTPStatus.BAD_GATEWAY,
            code="MODEL_UNAVAILABLE",
            message="failed to connect to upstream model server",
            details=truncate_text(str(exc), SETTINGS.log_body_chars),
        )
    except requests.RequestException as exc:
        log_line(
            f"upstream request failed code=MODEL_UNAVAILABLE details={truncate_text(str(exc), SETTINGS.log_body_chars)}"
        )
        return build_error_response(
            status=HTTPStatus.SERVICE_UNAVAILABLE,
            code="MODEL_UNAVAILABLE",
            message="upstream request failed",
            details=truncate_text(str(exc), SETTINGS.log_body_chars),
        )

    raw_upstream_text = upstream_response.text or ""
    log_line(
        "upstream response "
        f"status={upstream_response.status_code} raw={truncate_text(raw_upstream_text, SETTINGS.log_body_chars)}"
    )

    upstream_json: dict[str, Any] | None = None
    try:
        parsed_json = upstream_response.json()
    except json.JSONDecodeError:
        parsed_json = None

    if parsed_json is not None and isinstance(parsed_json, dict):
        upstream_json = parsed_json
    elif upstream_response.ok:
        return build_error_response(
            status=HTTPStatus.BAD_GATEWAY,
            code="MODEL_UNAVAILABLE",
            message="upstream response was not valid JSON",
            raw_response_snippet=truncate_text(raw_upstream_text, SETTINGS.log_body_chars),
        )

    upstream_error = extract_error_from_upstream_json(upstream_json)

    if not upstream_response.ok:
        return build_error_response(
            status=HTTPStatus.BAD_GATEWAY,
            code=upstream_error[0] if upstream_error else "MODEL_UNAVAILABLE",
            message=upstream_error[1] if upstream_error else "upstream returned non-success status",
            details=upstream_error[2] if upstream_error else None,
            upstream_status=upstream_response.status_code,
            raw_response_snippet=truncate_text(raw_upstream_text, SETTINGS.log_body_chars),
        )

    if not isinstance(upstream_json, dict):
        return build_error_response(
            status=HTTPStatus.BAD_GATEWAY,
            code="MODEL_UNAVAILABLE",
            message="upstream response must be a JSON object",
            raw_response_snippet=truncate_text(raw_upstream_text, SETTINGS.log_body_chars),
        )

    model_text = PLANNER_ADAPTER.extract_model_text(upstream_json)
    if not model_text:
        return build_no_json_response(raw_upstream_text, upstream_response.status_code)

    log_line(
        f"upstream model text {truncate_text(model_text, SETTINGS.log_body_chars)}"
    )
    sanitized = PLANNER_ADAPTER.sanitize_output(model_text, payload, SETTINGS)
    if not sanitized:
        return build_no_json_response(model_text, upstream_response.status_code)

    log_line(f"sanitized outputText {truncate_text(sanitized, SETTINGS.log_body_chars)}")
    return jsonify({"outputText": sanitized}), HTTPStatus.OK


def main() -> int:
    config_summary = {
        "adapter": PLANNER_ADAPTER.name,
        "proxyHost": SETTINGS.proxy_host,
        "proxyPort": SETTINGS.proxy_port,
        "upstreamBaseUrl": SETTINGS.upstream_base_url,
        "upstreamChatUrl": SETTINGS.upstream_chat_url,
        "model": SETTINGS.upstream_model,
        "timeoutSeconds": SETTINGS.upstream_timeout_seconds,
        "apiKeyConfigured": bool(SETTINGS.upstream_api_key),
    }
    log_line(f"starting proxy {compact_json(config_summary)}")
    APP.run(host=SETTINGS.proxy_host, port=SETTINGS.proxy_port, debug=False)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
