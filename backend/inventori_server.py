#!/usr/bin/env python3
"""Inventori V2.2 secure AI backend.

Standard-library-only service:
- keeps OPENAI_API_KEY server-side
- issues install tokens signed with HMAC
- meters successful scans per UTC calendar month
- sends no image to disk
- uses GPT-6 Luna vision through the Responses API
"""

from __future__ import annotations

import base64
import dataclasses
import datetime as dt
import hashlib
import hmac
import json
import os
import re
import sqlite3
import sys
import threading
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

INSTALL_ID_RE = re.compile(r"^[A-Za-z0-9._:-]{8,128}$")
WORKSPACE_TYPES = {"home", "moving", "business"}
MAX_BODY_BYTES = 10 * 1024 * 1024


@dataclasses.dataclass(frozen=True)
class Settings:
    host: str = "127.0.0.1"
    port: int = 18766
    db_path: str = "inventori-ai.sqlite3"
    openai_api_key: str = ""
    token_secret: str = ""
    model: str = "gpt-6-luna"
    image_detail: str = "high"
    monthly_scan_limit: int = 10
    input_price_per_million: float = 0.10
    output_price_per_million: float = 0.50
    mock_openai: bool = False

    @classmethod
    def from_env(cls) -> "Settings":
        return cls(
            host=os.getenv("INVENTORI_HOST", "127.0.0.1"),
            port=int(os.getenv("INVENTORI_PORT", "18766")),
            db_path=os.getenv("INVENTORI_DB_PATH", "inventori-ai.sqlite3"),
            openai_api_key=os.getenv("OPENAI_API_KEY", ""),
            token_secret=os.getenv("INVENTORI_TOKEN_SECRET", ""),
            model=os.getenv("INVENTORI_AI_MODEL", "gpt-6-luna"),
            image_detail=os.getenv("INVENTORI_IMAGE_DETAIL", "high"),
            monthly_scan_limit=int(os.getenv("INVENTORI_MONTHLY_SCAN_LIMIT", "10")),
            input_price_per_million=float(os.getenv("INVENTORI_INPUT_PRICE_PER_MILLION", "0.10")),
            output_price_per_million=float(os.getenv("INVENTORI_OUTPUT_PRICE_PER_MILLION", "0.50")),
            mock_openai=os.getenv("INVENTORI_MOCK_OPENAI", "0") == "1",
        )


class UsageStore:
    def __init__(self, db_path: str):
        self.db_path = db_path
        self._init_lock = threading.Lock()
        self._initialized = False

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.db_path, timeout=10)
        connection.row_factory = sqlite3.Row
        return connection

    def ensure_schema(self) -> None:
        if self._initialized:
            return
        with self._init_lock:
            if self._initialized:
                return
            with self._connect() as db:
                db.executescript(
                    """
                    CREATE TABLE IF NOT EXISTS usage (
                        install_id TEXT NOT NULL,
                        period TEXT NOT NULL,
                        successful_scans INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (install_id, period)
                    );
                    CREATE TABLE IF NOT EXISTS request_log (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        install_id TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        success INTEGER NOT NULL,
                        charged INTEGER NOT NULL,
                        item_count INTEGER NOT NULL DEFAULT 0,
                        input_tokens INTEGER NOT NULL DEFAULT 0,
                        output_tokens INTEGER NOT NULL DEFAULT 0,
                        estimated_cost_usd REAL NOT NULL DEFAULT 0,
                        model TEXT NOT NULL DEFAULT '',
                        error_code TEXT NOT NULL DEFAULT ''
                    );
                    """
                )
            self._initialized = True

    @staticmethod
    def period_key(now: dt.datetime | None = None) -> str:
        now = now or dt.datetime.now(dt.timezone.utc)
        return now.strftime("%Y-%m")

    def used(self, install_id: str, period: str) -> int:
        self.ensure_schema()
        with self._connect() as db:
            row = db.execute(
                "SELECT successful_scans FROM usage WHERE install_id=? AND period=?",
                (install_id, period),
            ).fetchone()
            return int(row["successful_scans"]) if row else 0

    def charge_if_available(self, install_id: str, period: str, limit: int) -> int | None:
        self.ensure_schema()
        with self._connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute(
                "SELECT successful_scans FROM usage WHERE install_id=? AND period=?",
                (install_id, period),
            ).fetchone()
            used = int(row["successful_scans"]) if row else 0
            if used >= limit:
                db.rollback()
                return None
            new_used = used + 1
            db.execute(
                """
                INSERT INTO usage(install_id, period, successful_scans)
                VALUES(?, ?, ?)
                ON CONFLICT(install_id, period)
                DO UPDATE SET successful_scans=excluded.successful_scans
                """,
                (install_id, period, new_used),
            )
            db.commit()
            return new_used

    def log(
        self,
        install_id: str,
        *,
        success: bool,
        charged: bool,
        item_count: int = 0,
        input_tokens: int = 0,
        output_tokens: int = 0,
        estimated_cost_usd: float = 0.0,
        model: str = "",
        error_code: str = "",
    ) -> None:
        self.ensure_schema()
        with self._connect() as db:
            db.execute(
                """
                INSERT INTO request_log(
                    install_id, created_at, success, charged, item_count,
                    input_tokens, output_tokens, estimated_cost_usd, model, error_code
                ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    install_id,
                    dt.datetime.now(dt.timezone.utc).isoformat(),
                    int(success),
                    int(charged),
                    item_count,
                    input_tokens,
                    output_tokens,
                    estimated_cost_usd,
                    model,
                    error_code,
                ),
            )


class OpenAIInventoryClient:
    def __init__(self, settings: Settings):
        self.settings = settings

    def detect(self, image_data_url: str, expected_items: int, workspace_type: str) -> tuple[list[dict[str, Any]], dict[str, Any]]:
        if self.settings.mock_openai:
            count = 2 if expected_items > 1 else 1
            items = [
                {
                    "name": "Mock detected item " + str(index + 1),
                    "category": "Test",
                    "brand": "",
                    "model": "",
                    "serial_number": "",
                    "quantity": 1,
                    "confidence": 0.99,
                }
                for index in range(count)
            ]
            return items, {"input_tokens": 1200, "output_tokens": 80, "model": self.settings.model}

        if not self.settings.openai_api_key:
            raise RuntimeError("OPENAI_API_KEY is not configured")

        prompt = (
            "Identify movable inventory items visible in this photo for an inventory app. "
            "Do not include walls, floors, ceilings, windows, built-in cabinetry, or vague background regions. "
            "Do not invent a brand, model, or serial number: use an empty string unless it is clearly visible "
            "or strongly identifiable from the product itself. Combine visually identical repeated objects into "
            "one item with quantity. "
        )
        if expected_items <= 1:
            prompt += "Return only the single primary inventory object. "
        else:
            prompt += "Return up to 20 distinct inventory-worthy objects. "
        prompt += (
            "If no suitable inventory object is visible, return an empty items array. "
            "Workspace context: " + workspace_type + "."
        )

        schema = {
            "type": "object",
            "properties": {
                "items": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "properties": {
                            "name": {"type": "string"},
                            "category": {"type": "string"},
                            "brand": {"type": "string"},
                            "model": {"type": "string"},
                            "serial_number": {"type": "string"},
                            "quantity": {"type": "integer", "minimum": 1, "maximum": 999},
                            "confidence": {"type": "number", "minimum": 0, "maximum": 1},
                        },
                        "required": [
                            "name", "category", "brand", "model",
                            "serial_number", "quantity", "confidence",
                        ],
                        "additionalProperties": False,
                    },
                    "maxItems": 20,
                }
            },
            "required": ["items"],
            "additionalProperties": False,
        }

        payload = {
            "model": self.settings.model,
            "reasoning": {"effort": "none"},
            "store": False,
            "input": [
                {
                    "role": "user",
                    "content": [
                        {"type": "input_text", "text": prompt},
                        {
                            "type": "input_image",
                            "image_url": image_data_url,
                            "detail": self.settings.image_detail,
                        },
                    ],
                }
            ],
            "text": {
                "format": {
                    "type": "json_schema",
                    "name": "inventori_item_detection",
                    "strict": True,
                    "schema": schema,
                }
            },
            "max_output_tokens": 1800,
        }
        request = urllib.request.Request(
            "https://api.openai.com/v1/responses",
            data=json.dumps(payload).encode("utf-8"),
            headers={
                "Authorization": "Bearer " + self.settings.openai_api_key,
                "Content-Type": "application/json",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=70) as response:
                raw = json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            details = error.read().decode("utf-8", errors="replace")
            raise RuntimeError("OpenAI HTTP " + str(error.code) + ": " + details[:500]) from error

        text = extract_output_text(raw)
        parsed = json.loads(text)
        items = parsed.get("items", [])
        if not isinstance(items, list):
            raise RuntimeError("OpenAI response did not contain an items array")
        usage = raw.get("usage") or {}
        return items, {
            "input_tokens": int(usage.get("input_tokens") or 0),
            "output_tokens": int(usage.get("output_tokens") or 0),
            "total_tokens": int(usage.get("total_tokens") or 0),
            "model": str(raw.get("model") or self.settings.model),
            "request_id": str(raw.get("id") or ""),
        }


def extract_output_text(raw: dict[str, Any]) -> str:
    direct = raw.get("output_text")
    if isinstance(direct, str) and direct:
        return direct
    for output in raw.get("output") or []:
        if not isinstance(output, dict):
            continue
        for content in output.get("content") or []:
            if isinstance(content, dict) and content.get("type") == "output_text":
                text = content.get("text")
                if isinstance(text, str) and text:
                    return text
    raise RuntimeError("OpenAI response contained no output_text")


class InventoriService:
    def __init__(self, settings: Settings):
        self.settings = settings
        self.store = UsageStore(settings.db_path)
        self.client = OpenAIInventoryClient(settings)

    def _token(self, install_id: str) -> str:
        if not self.settings.token_secret:
            raise RuntimeError("INVENTORI_TOKEN_SECRET is not configured")
        return hmac.new(
            self.settings.token_secret.encode("utf-8"),
            install_id.encode("utf-8"),
            hashlib.sha256,
        ).hexdigest()

    @staticmethod
    def validate_install_id(install_id: str) -> None:
        if not INSTALL_ID_RE.fullmatch(install_id):
            raise ValueError("Invalid install_id")

    def register(self, install_id: str) -> dict[str, Any]:
        self.validate_install_id(install_id)
        period = self.store.period_key()
        used = self.store.used(install_id, period)
        limit = self.settings.monthly_scan_limit
        return {
            "ok": True,
            "install_token": self._token(install_id),
            "period": period,
            "scans_used": used,
            "scans_limit": limit,
            "scans_remaining": max(0, limit - used),
        }

    def analyze(self, payload: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        install_id = str(payload.get("install_id") or "")
        self.validate_install_id(install_id)
        supplied_token = str(payload.get("install_token") or "")
        if not hmac.compare_digest(supplied_token, self._token(install_id)):
            return 401, {"ok": False, "error": "Invalid install token"}

        image_data_url = str(payload.get("image_data_url") or "")
        if not image_data_url.startswith("data:image/") or ";base64," not in image_data_url:
            return 400, {"ok": False, "error": "image_data_url must be a base64 image data URL"}
        try:
            encoded = image_data_url.split(",", 1)[1]
            decoded_size = len(base64.b64decode(encoded, validate=True))
        except Exception:
            return 400, {"ok": False, "error": "Invalid base64 image"}
        if decoded_size <= 0 or decoded_size > 7 * 1024 * 1024:
            return 413, {"ok": False, "error": "Image must be between 1 byte and 7 MiB"}

        expected_items = max(1, min(20, int(payload.get("expected_items") or 1)))
        workspace_type = str(payload.get("workspace_type") or "home")
        if workspace_type not in WORKSPACE_TYPES:
            workspace_type = "home"

        period = self.store.period_key()
        limit = self.settings.monthly_scan_limit
        used_before = self.store.used(install_id, period)
        if used_before >= limit:
            return 429, {
                "ok": False,
                "error": "Monthly AI scan limit reached",
                "period": period,
                "scans_used": used_before,
                "scans_limit": limit,
                "scans_remaining": 0,
            }

        try:
            items, usage = self.client.detect(image_data_url, expected_items, workspace_type)
        except Exception as error:
            self.store.log(install_id, success=False, charged=False, error_code="provider_error")
            return 502, {"ok": False, "error": "AI provider error: " + str(error)[:500]}

        input_tokens = int(usage.get("input_tokens") or 0)
        output_tokens = int(usage.get("output_tokens") or 0)
        estimated_cost = (
            input_tokens * self.settings.input_price_per_million
            + output_tokens * self.settings.output_price_per_million
        ) / 1_000_000.0

        charged = len(items) > 0
        used_after = used_before
        if charged:
            charged_count = self.store.charge_if_available(install_id, period, limit)
            if charged_count is None:
                return 429, {
                    "ok": False,
                    "error": "Monthly AI scan limit reached",
                    "period": period,
                    "scans_used": limit,
                    "scans_limit": limit,
                    "scans_remaining": 0,
                }
            used_after = charged_count

        self.store.log(
            install_id,
            success=True,
            charged=charged,
            item_count=len(items),
            input_tokens=input_tokens,
            output_tokens=output_tokens,
            estimated_cost_usd=estimated_cost,
            model=str(usage.get("model") or self.settings.model),
        )
        return 200, {
            "ok": True,
            "charged": charged,
            "period": period,
            "scans_used": used_after,
            "scans_limit": limit,
            "scans_remaining": max(0, limit - used_after),
            "model": str(usage.get("model") or self.settings.model),
            "items": items,
            "usage": {
                "input_tokens": input_tokens,
                "output_tokens": output_tokens,
                "total_tokens": int(usage.get("total_tokens") or (input_tokens + output_tokens)),
                "estimated_cost_usd": round(estimated_cost, 8),
            },
        }


class Handler(BaseHTTPRequestHandler):
    service: InventoriService

    def _json(self, status: int, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:
        if self.path == "/health":
            self._json(200, {"ok": True, "service": "inventori-ai", "model": self.service.settings.model})
        else:
            self._json(404, {"ok": False, "error": "Not found"})

    def do_POST(self) -> None:
        try:
            size = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            size = 0
        if size <= 0 or size > MAX_BODY_BYTES:
            self._json(413, {"ok": False, "error": "Request body too large or missing"})
            return
        try:
            payload = json.loads(self.rfile.read(size).decode("utf-8"))
            if not isinstance(payload, dict):
                raise ValueError("JSON object required")
        except Exception:
            self._json(400, {"ok": False, "error": "Invalid JSON"})
            return

        try:
            if self.path == "/v1/register":
                self._json(200, self.service.register(str(payload.get("install_id") or "")))
            elif self.path == "/v1/analyze":
                status, result = self.service.analyze(payload)
                self._json(status, result)
            else:
                self._json(404, {"ok": False, "error": "Not found"})
        except ValueError as error:
            self._json(400, {"ok": False, "error": str(error)})
        except Exception as error:
            self._json(500, {"ok": False, "error": "Server error: " + str(error)[:300]})

    def log_message(self, fmt: str, *args: Any) -> None:
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))


def main() -> None:
    settings = Settings.from_env()
    if not settings.token_secret:
        raise SystemExit("INVENTORI_TOKEN_SECRET is required")
    if not settings.openai_api_key and not settings.mock_openai:
        raise SystemExit("OPENAI_API_KEY is required unless INVENTORI_MOCK_OPENAI=1")
    service = InventoriService(settings)
    service.store.ensure_schema()
    Handler.service = service
    server = ThreadingHTTPServer((settings.host, settings.port), Handler)
    print("Inventori AI backend listening on %s:%d" % (settings.host, settings.port))
    server.serve_forever()


if __name__ == "__main__":
    main()
