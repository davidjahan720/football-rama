"""End-to-end CLI tests (network mocked via client._urlopen)."""
import base64
import json
import logging

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

from football_rama_publisher import client
from football_rama_publisher.__main__ import EXIT_CONFIG, EXIT_FAILURE, EXIT_OK, main
from football_rama_publisher.signing import load_public_key, public_key_b64, verify

from conftest import FAKE_TOKEN, FakeOpener, FakeResponse, http_error


@pytest.fixture
def keys(private_key):
    return base64.b64encode(private_key.private_bytes_raw()).decode(), public_key_b64(private_key)


@pytest.fixture
def env(monkeypatch, keys):
    monkeypatch.setenv("FOOTBALL_DATA_TOKEN", FAKE_TOKEN)
    monkeypatch.setenv("FIXTURES_SIGNING_KEY", keys[0])
    monkeypatch.setattr(client, "_urlopen", FakeOpener())  # replaced per test
    return keys


def serve(monkeypatch, *steps):
    opener = FakeOpener(*steps)
    monkeypatch.setattr(client, "_urlopen", opener)
    monkeypatch.setattr(client.time, "sleep", lambda s: None)
    return opener


def assert_no_secret(text: str, keys):
    assert FAKE_TOKEN not in text
    assert keys[0] not in text


def test_publish_ok_with_verify_key(env, monkeypatch, tmp_path, api_payload, capsys, caplog):
    caplog.set_level(logging.DEBUG)
    serve(monkeypatch, FakeResponse(json.dumps(api_payload).encode()))
    out = tmp_path / "dist"
    assert main(["publish", "--out", str(out), "--verify-key", env[1]]) == EXIT_OK
    data = (out / "fixtures.json").read_bytes()
    sig = (out / "fixtures.json.sig").read_bytes()
    assert verify(data, sig, load_public_key(env[1]))
    doc = json.loads(data.decode("utf-8"))
    assert doc["schema"] == 1
    assert sorted(p.name for p in out.iterdir()) == ["fixtures.json", "fixtures.json.sig"]  # no .tmp left
    captured = capsys.readouterr()
    assert_no_secret(captured.out + captured.err + caplog.text, env)


def test_zero_matches_publishes_valid_file(env, monkeypatch, tmp_path):
    serve(monkeypatch, FakeResponse(b'{"filters": {}, "resultSet": {"count": 0}, "matches": []}'))
    out = tmp_path / "dist"
    assert main(["publish", "--out", str(out), "--verify-key", env[1]]) == EXIT_OK
    assert json.loads((out / "fixtures.json").read_bytes())["matches"] == []


@pytest.mark.parametrize("step", [http_error(500), http_error(403), http_error(429), FakeResponse(b"oops")])
def test_api_failure_writes_nothing(env, monkeypatch, tmp_path, step, capsys, caplog):
    caplog.set_level(logging.DEBUG)
    steps = [step] * 3 if isinstance(step, Exception) else [step]
    serve(monkeypatch, *steps)
    out = tmp_path / "dist"
    assert main(["publish", "--out", str(out)]) == EXIT_FAILURE
    assert not out.exists()
    captured = capsys.readouterr()
    assert_no_secret(captured.out + captured.err + caplog.text, env)


def test_api_failure_keeps_previous_files_untouched(env, monkeypatch, tmp_path, api_payload):
    out = tmp_path / "dist"
    serve(monkeypatch, FakeResponse(json.dumps(api_payload).encode()))
    assert main(["publish", "--out", str(out)]) == EXIT_OK
    before = {p.name: p.read_bytes() for p in out.iterdir()}
    serve(monkeypatch, http_error(503))
    assert main(["publish", "--out", str(out)]) == EXIT_FAILURE
    assert {p.name: p.read_bytes() for p in out.iterdir()} == before


def test_wrong_verify_key_writes_nothing(env, monkeypatch, tmp_path, api_payload):
    serve(monkeypatch, FakeResponse(json.dumps(api_payload).encode()))
    other = public_key_b64(Ed25519PrivateKey.generate())
    out = tmp_path / "dist"
    assert main(["publish", "--out", str(out), "--verify-key", other]) == EXIT_FAILURE
    assert not out.exists()


@pytest.mark.parametrize("missing", ["FOOTBALL_DATA_TOKEN", "FIXTURES_SIGNING_KEY"])
def test_missing_env(env, monkeypatch, tmp_path, missing, caplog):
    monkeypatch.delenv(missing)
    opener = serve(monkeypatch)
    assert main(["publish", "--out", str(tmp_path / "dist")]) == EXIT_CONFIG
    assert missing in caplog.text
    assert opener.requests == []
    assert not (tmp_path / "dist").exists()


def test_invalid_signing_key(env, monkeypatch, tmp_path, capsys, caplog):
    bad = "Zm9vYmFyYmF6" * 3
    monkeypatch.setenv("FIXTURES_SIGNING_KEY", bad)
    opener = serve(monkeypatch)
    assert main(["publish", "--out", str(tmp_path / "dist")]) == EXIT_CONFIG
    assert opener.requests == []
    captured = capsys.readouterr()
    assert bad not in captured.out + captured.err + caplog.text


def test_empty_verify_key_refused(env, monkeypatch, tmp_path):
    serve(monkeypatch)
    assert main(["publish", "--out", str(tmp_path / "dist"), "--verify-key", ""]) == EXIT_CONFIG


def test_secrets_not_accepted_as_arguments(env, tmp_path):
    with pytest.raises(SystemExit):
        main(["publish", "--out", str(tmp_path), "--token", FAKE_TOKEN])
