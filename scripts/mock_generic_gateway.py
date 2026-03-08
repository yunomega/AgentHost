#!/usr/bin/env python3

"""Minimal Generic Gateway mock server for AgentHost model planning.

Examples:
  python3 scripts/mock_generic_gateway.py
  python3 scripts/mock_generic_gateway.py --host 0.0.0.0 --port 8080
  curl -sS -X POST 'http://127.0.0.1:8080/plan?mode=click' \
    -H 'Content-Type: application/json' \
    -d '{"sessionId":"demo","step":1,"context":{"capabilityProfile":{"clickSupported":true}}}'
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from datetime import datetime, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import parse_qs, urlparse


MODES = ("click", "ask_user", "home", "timeout", "invalid")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Minimal Generic Gateway mock server for AgentHost."
    )
    parser.add_argument("--host", default="127.0.0.1", help="Bind host")
    parser.add_argument("--port", type=int, default=8080, help="Bind port")
    parser.add_argument(
        "--default-mode",
        choices=MODES,
        default="click",
        help="Mode used when /plan is called without ?mode=...",
    )
    parser.add_argument(
        "--sleep-seconds",
        type=float,
        default=15.0,
        help="Sleep duration used by timeout mode",
    )
    parser.add_argument(
        "--truncate-base64",
        type=int,
        default=32,
        help="Number of leading base64 chars to print before adding a tail summary",
    )
    return parser.parse_args()


def utc_timestamp() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def log_line(message: str) -> None:
    print(f"[{utc_timestamp()}] {message}", flush=True)


def truncate_text(value: Any, max_chars: int = 160) -> Any:
    if not isinstance(value, str):
        return value
    if len(value) <= max_chars:
        return value
    return f"{value[:max_chars]}…"


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
        return {"count": len(actions), "last": truncate_text(last_entry)}
    return {
        "count": len(actions),
        "last": {
            "step": last_entry.get("step"),
            "actionType": last_entry.get("actionType"),
            "code": last_entry.get("code"),
            "summary": truncate_text(last_entry.get("summary"), max_chars=80),
        },
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

    return {
        "sessionId": payload.get("sessionId"),
        "step": payload.get("step"),
        "maxResponseChars": payload.get("maxResponseChars"),
        "context": {
            "sessionId": context.get("sessionId"),
            "vdDisplayId": context.get("vdDisplayId"),
            "sessionTaskId": context.get("sessionTaskId"),
            "step": context.get("step"),
            "capabilityProfile": context.get("capabilityProfile"),
            "capabilitySummary": truncate_text(
                context.get("capabilitySummary"), max_chars=120
            ),
            "observation": {
                "status": observation.get("status"),
                "width": observation.get("width"),
                "height": observation.get("height"),
                "code": observation.get("code"),
                "message": truncate_text(observation.get("message"), max_chars=120),
                "base64Png": summarize_base64(
                    observation.get("base64Png"), base64_head_chars
                ),
            },
            "recentActions": summarize_recent_actions(context.get("recentActions")),
            "lastFailureCode": context.get("lastFailureCode"),
            "lastUserReply": truncate_text(context.get("lastUserReply"), max_chars=80),
        },
    }


def json_response(status: int, body: dict[str, Any]) -> tuple[int, bytes]:
    raw = json.dumps(body, ensure_ascii=False).encode("utf-8")
    return status, raw


def build_mode_response(mode: str) -> tuple[int, bytes]:
    if mode == "click":
        return json_response(
            HTTPStatus.OK,
            {"outputText": json.dumps({"action": "CLICK", "x": 540, "y": 960})},
        )
    if mode == "ask_user":
        return json_response(
            HTTPStatus.OK,
            {
                "outputText": json.dumps(
                    {
                        "action": "ASK_USER",
                        "question": "Please provide the verification code.",
                    }
                )
            },
        )
    if mode == "home":
        return json_response(
            HTTPStatus.OK,
            {"outputText": json.dumps({"action": "HOME"})},
        )
    if mode == "invalid":
        return json_response(
            HTTPStatus.OK,
            {"outputText": json.dumps([{"action": "CLICK", "x": 540, "y": 960}])},
        )
    if mode == "timeout":
        raise RuntimeError("timeout mode should be handled before building the response")
    return json_response(
        HTTPStatus.BAD_REQUEST,
        {"code": "BAD_MODE", "message": f"unsupported mode '{mode}'"},
    )


def build_mode_response_for_payload(mode: str, payload: Any) -> tuple[int, bytes]:
    if mode == "ask_user":
        last_user_reply = None
        if isinstance(payload, dict):
            context = payload.get("context")
            if isinstance(context, dict):
                candidate = context.get("lastUserReply")
                if isinstance(candidate, str):
                    last_user_reply = candidate.strip()
        if last_user_reply:
            return json_response(
                HTTPStatus.OK,
                {
                    "outputText": json.dumps(
                        {
                            "action": "DONE",
                            "reason": "ask_user_reply_received",
                        }
                    )
                },
            )
    return build_mode_response(mode)


class MockGatewayHandler(BaseHTTPRequestHandler):
    server_version = "AgentHostMockGateway/1.0"

    def log_message(self, format: str, *args: Any) -> None:
        log_line(f"http access remote={self.address_string()} {format % args}")

    def do_POST(self) -> None:
        parsed_url = urlparse(self.path)
        if parsed_url.path != "/plan":
            self.write_response(
                *json_response(
                    HTTPStatus.NOT_FOUND,
                    {"code": "NOT_FOUND", "message": "only POST /plan is supported"},
                )
            )
            return

        content_length_header = self.headers.get("Content-Length", "0")
        try:
            content_length = int(content_length_header)
        except ValueError:
            content_length = 0
        raw_body = self.rfile.read(max(content_length, 0))
        body_text = raw_body.decode("utf-8", errors="replace")
        query = parse_qs(parsed_url.query)
        mode = query.get("mode", [self.server.default_mode])[0]

        log_line(
            "incoming request "
            f"remote={self.client_address[0]} method=POST path={parsed_url.path} "
            f"mode={mode} contentLength={content_length}"
        )

        try:
            payload = json.loads(body_text)
        except json.JSONDecodeError as exc:
            log_line(
                "request body invalid_json "
                f"error={exc.msg} body={truncate_text(body_text, max_chars=240)}"
            )
            self.write_response(
                *json_response(
                    HTTPStatus.BAD_REQUEST,
                    {
                        "code": "BAD_REQUEST",
                        "message": "request body must be valid JSON",
                    },
                )
            )
            return

        summary = build_request_summary(payload, self.server.truncate_base64)
        log_line(f"request summary {json.dumps(summary, ensure_ascii=False)}")

        if mode == "timeout":
            log_line(f"timeout mode sleeping seconds={self.server.sleep_seconds}")
            time.sleep(self.server.sleep_seconds)
            self.write_response(
                *json_response(
                    HTTPStatus.OK,
                    {"outputText": json.dumps({"action": "CLICK", "x": 540, "y": 960})},
                )
            )
            return

        status, raw = build_mode_response_for_payload(mode, payload)
        log_line(f"responding mode={mode} status={status} body={raw.decode('utf-8')}")
        self.write_response(status, raw)

    def write_response(self, status: int, body: bytes) -> None:
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


class MockGatewayServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(
        self,
        server_address: tuple[str, int],
        handler_class: type[BaseHTTPRequestHandler],
        default_mode: str,
        sleep_seconds: float,
        truncate_base64: int,
    ) -> None:
        super().__init__(server_address, handler_class)
        self.default_mode = default_mode
        self.sleep_seconds = sleep_seconds
        self.truncate_base64 = truncate_base64


def main() -> int:
    args = parse_args()
    server = MockGatewayServer(
        server_address=(args.host, args.port),
        handler_class=MockGatewayHandler,
        default_mode=args.default_mode,
        sleep_seconds=args.sleep_seconds,
        truncate_base64=args.truncate_base64,
    )
    log_line(
        "listening "
        f"url=http://{args.host}:{args.port}/plan defaultMode={args.default_mode} "
        f"modes={','.join(MODES)}"
    )
    log_line(
        "example endpoints "
        f"click=http://{args.host}:{args.port}/plan?mode=click "
        f"ask_user=http://{args.host}:{args.port}/plan?mode=ask_user "
        f"home=http://{args.host}:{args.port}/plan?mode=home "
        f"timeout=http://{args.host}:{args.port}/plan?mode=timeout "
        f"invalid=http://{args.host}:{args.port}/plan?mode=invalid"
    )
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        log_line("shutting down on keyboard interrupt")
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
