#!/usr/bin/env python3
"""Inspect the pre-send ChatGPT requirements response without sending a chat message.

This is an Android-side diagnostic, not a ChatGPT transport implementation.
It never prints credentials, response tokens, cookies, or conversation content.
"""

import datetime
import json
import os
from pathlib import Path
import urllib.error
import urllib.request


HOST = "chatgpt.com"
PATH = "/backend-api/sentinel/chat-requirements"


def now_utc():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def main():
    home = Path(os.environ.get("CODEX_HOME", Path.home() / ".codex"))
    auth = json.loads((home / "auth.json").read_text(encoding="utf-8"))
    access_token = auth["tokens"]["access_token"]
    body = json.dumps({"conversation_mode": "chat"}).encode("utf-8")
    request = urllib.request.Request(
        f"https://{HOST}{PATH}",
        data=body,
        headers={
            "Authorization": f"Bearer {access_token}",
            "Content-Type": "application/json",
            "User-Agent": "MobileCodex/requirements-diagnostic",
        },
        method="POST",
    )
    report = {
        "started_at_utc": now_utc(),
        "environment": "Android Python urllib (outside the mobile-codex UI)",
        "request": {"method": "POST", "host": HOST, "path": PATH},
        "conversation_post_attempted": False,
    }
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            data = json.load(response)
            report["response"] = {
                "status": response.status,
                "content_type": response.headers.get("Content-Type"),
                "request_id": response.headers.get("x-request-id")
                or response.headers.get("x-openai-request-id"),
                "cf_ray": response.headers.get("cf-ray"),
                "requirements": {
                    key: data.get(key, {}).get("required")
                    if isinstance(data.get(key), dict) else None
                    for key in ("turnstile", "proofofwork", "so")
                },
            }
            report["stop_reason"] = (
                "challenge_steps_not_implemented"
                if any(value is True for value in report["response"]["requirements"].values())
                else "conversation_transport_not_implemented"
            )
    except urllib.error.HTTPError as error:
        # Do not print the body: it can contain auth or challenge material.
        report["response"] = {
            "status": error.code,
            "content_type": error.headers.get("Content-Type"),
            "request_id": error.headers.get("x-request-id")
            or error.headers.get("x-openai-request-id"),
            "cf_ray": error.headers.get("cf-ray"),
        }
        report["stop_reason"] = "requirements_http_error"
    except Exception as error:
        report["client_error"] = {
            "type": type(error).__name__,
            "stage": "requirements_request",
        }
        report["stop_reason"] = "client_error_before_conversation_post"
    report["finished_at_utc"] = now_utc()
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
