from __future__ import annotations

import copy
import email.message
import io
import json
import socket
import urllib.error
from datetime import UTC, datetime
from pathlib import Path

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

DATA = Path(__file__).parent / "data"

# Fake secrets: distinctive values so the tests can assert they never leak
FAKE_TOKEN = "tok-SECRET-0123456789abcdef"
NOW = datetime(2026, 10, 20, 6, 0, 0, tzinfo=UTC)


@pytest.fixture(autouse=True)
def no_network(monkeypatch):
    """Any real socket connection fails the test."""
    def guard(*args, **kwargs):
        raise AssertionError("appel reseau interdit dans les tests")
    monkeypatch.setattr(socket.socket, "connect", guard)
    monkeypatch.setattr(socket, "create_connection", guard)


@pytest.fixture(scope="session")
def api_payload() -> dict:
    return json.loads((DATA / "matches_sample.json").read_text(encoding="utf-8"))


@pytest.fixture
def raw_matches(api_payload) -> list[dict]:
    return copy.deepcopy(api_payload["matches"])


@pytest.fixture
def private_key() -> Ed25519PrivateKey:
    return Ed25519PrivateKey.generate()


class FakeResponse:
    def __init__(self, body: bytes, status: int = 200):
        self._buf = io.BytesIO(body)
        self.status = status

    def read(self, n: int = -1) -> bytes:
        return self._buf.read(n)

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


def http_error(code: int, headers: dict | None = None) -> urllib.error.HTTPError:
    msg = email.message.Message()
    for k, v in (headers or {}).items():
        msg[k] = v
    return urllib.error.HTTPError("https://api.football-data.org/v4/matches", code, "error", msg, io.BytesIO(b"{}"))


class FakeOpener:
    """Replays a scripted sequence of responses / exceptions and records the requests."""

    def __init__(self, *steps):
        self.steps = list(steps)
        self.requests = []

    def __call__(self, req, timeout=None):
        self.requests.append((req, timeout))
        step = self.steps.pop(0)
        if isinstance(step, BaseException):
            raise step
        return step
