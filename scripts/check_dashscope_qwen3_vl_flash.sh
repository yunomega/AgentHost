#!/usr/bin/env bash

set -euo pipefail

BASE_URL="${UPSTREAM_BASE_URL:-${BIGMODEL_BASE_URL:-${DASHSCOPE_BASE_URL:-https://open.bigmodel.cn/api/paas/v4}}}"
MODEL="${UPSTREAM_MODEL:-${BIGMODEL_MODEL:-${DASHSCOPE_MODEL:-glm-4.6v}}}"
HTTPS_IMAGE_URL="${BIGMODEL_HTTPS_IMAGE_URL:-${DASHSCOPE_HTTPS_IMAGE_URL:-https://dashscope.oss-cn-beijing.aliyuncs.com/images/dog_and_girl.jpeg}}"
CONNECT_TIMEOUT_SECONDS="${UPSTREAM_CONNECT_TIMEOUT_SECONDS:-${BIGMODEL_CONNECT_TIMEOUT_SECONDS:-${DASHSCOPE_CONNECT_TIMEOUT_SECONDS:-10}}}"
MAX_TIME_SECONDS="${UPSTREAM_MAX_TIME_SECONDS:-${BIGMODEL_MAX_TIME_SECONDS:-${DASHSCOPE_MAX_TIME_SECONDS:-60}}}"
API_KEY="${UPSTREAM_API_KEY:-${BIGMODEL_API_KEY:-${DASHSCOPE_API_KEY:-}}}"

if [[ -z "${API_KEY:-}" ]]; then
  echo "[FAIL] one of UPSTREAM_API_KEY/BIGMODEL_API_KEY/DASHSCOPE_API_KEY must be set"
  exit 2
fi

RUN_DIR="$(mktemp -d "/tmp/bigmodel-glm-4.6v.XXXXXX")"

log() {
  printf '[INFO] %s\n' "$*"
}

fail() {
  printf '[FAIL] %s\n' "$*" >&2
  exit 1
}

generate_data_url() {
  python3 - <<'PY'
import base64
import binascii
import struct
import zlib

width = 16
height = 16
row = b"\x00" + (b"\xff\x00\x00" * width)
raw = row * height

def chunk(tag: bytes, data: bytes) -> bytes:
    return (
        struct.pack(">I", len(data))
        + tag
        + data
        + struct.pack(">I", binascii.crc32(tag + data) & 0xFFFFFFFF)
    )

png = b"".join(
    [
        b"\x89PNG\r\n\x1a\n",
        chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)),
        chunk(b"IDAT", zlib.compress(raw, 9)),
        chunk(b"IEND", b""),
    ]
)
print("data:image/png;base64," + base64.b64encode(png).decode("ascii"))
PY
}

DATA_URL_IMAGE="$(generate_data_url)"

write_request_file() {
  local request_file="$1"
  local prompt="$2"
  local image_url="${3:-}"

  if [[ -n "$image_url" ]]; then
    python3 - "$request_file" "$MODEL" "$prompt" "$image_url" <<'PY'
import json
import sys

request_file, model, prompt, image_url = sys.argv[1:5]
payload = {
    "model": model,
    "messages": [
        {
            "role": "user",
            "content": [
                {"type": "text", "text": prompt},
                {"type": "image_url", "image_url": {"url": image_url}},
            ],
        }
    ],
    "stream": False,
    "temperature": 0,
}
with open(request_file, "w", encoding="utf-8") as fh:
    json.dump(payload, fh, ensure_ascii=False, indent=2)
PY
  else
    python3 - "$request_file" "$MODEL" "$prompt" <<'PY'
import json
import sys

request_file, model, prompt = sys.argv[1:4]
payload = {
    "model": model,
    "messages": [
        {
            "role": "user",
            "content": prompt,
        }
    ],
    "stream": False,
    "temperature": 0,
}
with open(request_file, "w", encoding="utf-8") as fh:
    json.dump(payload, fh, ensure_ascii=False, indent=2)
PY
  fi
}

print_summary() {
  local probe_name="$1"
  local http_status="$2"
  local response_file="$3"
  local curl_stderr_file="$4"
  python3 - "$probe_name" "$http_status" "$response_file" "$curl_stderr_file" <<'PY'
import json
import pathlib
import sys

probe_name, http_status, response_file, curl_stderr_file = sys.argv[1:5]
response_path = pathlib.Path(response_file)
stderr_path = pathlib.Path(curl_stderr_file)
raw = response_path.read_text(encoding="utf-8", errors="replace")
stderr_text = stderr_path.read_text(encoding="utf-8", errors="replace").strip()

print(f"probe={probe_name}")
print(f"http_status={http_status}")
print(f"response_file={response_path}")
print(f"curl_stderr_file={stderr_path}")

if stderr_text:
    print(f"curl_stderr={stderr_text}")

try:
    data = json.loads(raw)
except json.JSONDecodeError as exc:
    print("http_or_sdk_success=false")
    print("parse_success=false")
    print(f"parse_error={exc}")
    snippet = raw[:800].replace("\n", "\\n")
    print(f"raw_response_snippet={snippet}")
    sys.exit(1)

print("parse_success=true")

error = data.get("error")
if isinstance(error, dict):
    print("http_or_sdk_success=false")
    print(f"model={data.get('model') or ''}")
    print(f"error.code={error.get('code') or ''}")
    print(f"error.message={error.get('message') or ''}")
    print(f"error.type={error.get('type') or ''}")
    sys.exit(1)

choices = data.get("choices")
if not isinstance(choices, list) or not choices:
    print("http_or_sdk_success=false")
    print(f"model={data.get('model') or ''}")
    print("error.code=CHOICES_MISSING")
    print("error.message=response missing choices[0]")
    sys.exit(1)

choice = choices[0] if isinstance(choices[0], dict) else {}
message = choice.get("message") if isinstance(choice.get("message"), dict) else {}
content = message.get("content")
text_parts = []
if isinstance(content, str):
    text_parts.append(content)
elif isinstance(content, list):
    for item in content:
        if isinstance(item, str):
            text_parts.append(item)
            continue
        if not isinstance(item, dict):
            continue
        text = item.get("text")
        if isinstance(text, str):
            text_parts.append(text)
        elif isinstance(item.get("content"), str):
            text_parts.append(item["content"])

first_text = " ".join(part.strip() for part in text_parts if part and part.strip())
usage = data.get("usage")
usage_text = json.dumps(usage, ensure_ascii=False, separators=(",", ":")) if usage is not None else ""

print("http_or_sdk_success=true")
print(f"model={data.get('model') or ''}")
print(f"finish_reason={choice.get('finish_reason') or ''}")
print(f"usage={usage_text}")
print(f"first_text={first_text}")
PY
}

run_probe() {
  local probe_id="$1"
  local probe_name="$2"
  local prompt="$3"
  local image_url="${4:-}"
  local request_file="${RUN_DIR}/${probe_id}.request.json"
  local response_file="${RUN_DIR}/${probe_id}.response.json"
  local curl_stderr_file="${RUN_DIR}/${probe_id}.curl.stderr.log"

  write_request_file "$request_file" "$prompt" "$image_url"

  log "running probe=${probe_name}"
  local http_status
  set +e
  http_status="$(
    curl \
      --silent \
      --show-error \
      --connect-timeout "${CONNECT_TIMEOUT_SECONDS}" \
      --max-time "${MAX_TIME_SECONDS}" \
      --output "${response_file}" \
      --write-out '%{http_code}' \
      -X POST \
      -H "Content-Type: application/json" \
      -H "Authorization: Bearer ${API_KEY}" \
      --data @"${request_file}" \
      "${BASE_URL%/}/chat/completions" \
      2>"${curl_stderr_file}"
  )"
  local curl_exit=$?
  set -e

  if [[ ${curl_exit} -ne 0 ]]; then
    echo "probe=${probe_name}"
    echo "http_or_sdk_success=false"
    echo "curl_exit=${curl_exit}"
    echo "response_file=${response_file}"
    echo "curl_stderr_file=${curl_stderr_file}"
    if [[ -s "${curl_stderr_file}" ]]; then
      echo "curl_stderr=$(tr '\n' ' ' <"${curl_stderr_file}")"
    fi
    fail "curl failed for probe=${probe_name}; artifacts kept under ${RUN_DIR}"
  fi

  if ! print_summary "$probe_name" "$http_status" "$response_file" "$curl_stderr_file"; then
    fail "probe=${probe_name} failed; artifacts kept under ${RUN_DIR}"
  fi
  printf '\n'
}

log "artifacts_dir=${RUN_DIR}"
log "base_url=${BASE_URL}"
log "model=${MODEL}"
log "https_image_url=${HTTPS_IMAGE_URL}"

run_probe \
  "01_text_only" \
  "text-only" \
  "Reply with exactly one short line: TEXT_OK"

run_probe \
  "02_https_image" \
  "https-image" \
  "Describe the image in one short sentence." \
  "${HTTPS_IMAGE_URL}"

run_probe \
  "03_data_url_image" \
  "data-url-image" \
  "Describe the image in one short sentence." \
  "${DATA_URL_IMAGE}"

log "all probes succeeded"
log "artifacts_dir=${RUN_DIR}"
