"""update.json publication (app_release.json -> update.json + update.json.sig). No network involved."""
import base64
import copy
import json
import logging
from pathlib import Path

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

from football_rama_publisher import app_release
from football_rama_publisher.__main__ import EXIT_CONFIG, EXIT_FAILURE, EXIT_OK, main
from football_rama_publisher.signing import load_public_key, public_key_b64, verify

REPO_MANIFEST = Path(__file__).resolve().parents[1] / "app_release.json"

VALID = {
    "schema": 1,
    "version_code": 2,
    "version_name": "1.0.1",
    "apk_url": "https://github.com/davidjahan720/football-rama/releases/download/v1.0.1/MatchsEtChaines-1.0.1.apk",
    "sha256": "0123456789abcdef" * 4,
    "size": 12_345_678,
    "min_sdk": 26,
    "notes": "Corrections et améliorations.",
}


@pytest.fixture
def keys(private_key):
    return base64.b64encode(private_key.private_bytes_raw()).decode(), public_key_b64(private_key)


@pytest.fixture
def env(monkeypatch, keys):
    monkeypatch.setenv("FIXTURES_SIGNING_KEY", keys[0])
    monkeypatch.delenv("FOOTBALL_DATA_TOKEN", raising=False)
    return keys


def write(tmp_path, doc, raw: str | None = None) -> Path:
    p = tmp_path / "app_release.json"
    p.write_text(raw if raw is not None else json.dumps(doc, ensure_ascii=False), encoding="utf-8")
    return p


def with_(**changes):
    d = copy.deepcopy(VALID)
    for k, v in changes.items():
        if v is ...:
            d.pop(k)
        else:
            d[k] = v
    return d


# ------------------------------------------------------------------ publication

def test_publish_update_signed_and_verifiable(env, tmp_path, capsys, caplog):
    caplog.set_level(logging.DEBUG)
    out = tmp_path / "site"
    assert main(["publish-update", "--manifest", str(write(tmp_path, VALID)), "--out", str(out),
                 "--verify-key", env[1]]) == EXIT_OK
    data = (out / "update.json").read_bytes()
    sig = (out / "update.json.sig").read_bytes()
    assert verify(data, sig, load_public_key(env[1]))
    assert not sig.endswith(b"\n")
    assert json.loads(data.decode("utf-8")) == VALID
    assert sorted(p.name for p in out.iterdir()) == ["update.json", "update.json.sig"]
    captured = capsys.readouterr()
    assert env[0] not in captured.out + captured.err + caplog.text


def test_published_bytes_are_canonical(env, tmp_path):
    """Pretty-printed input, unknown order: the signed bytes are compact, in contract field order."""
    raw = json.dumps(dict(reversed(list(VALID.items()))), indent=4, ensure_ascii=False)
    out = tmp_path / "site"
    assert main(["publish-update", "--manifest", str(write(tmp_path, None, raw)), "--out", str(out)]) == EXIT_OK
    data = (out / "update.json").read_bytes()
    assert data == json.dumps(VALID, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


@pytest.mark.parametrize("case", ["absent", "placeholder"])
def test_absent_or_placeholder_publishes_nothing_without_failure(env, monkeypatch, tmp_path, case):
    monkeypatch.delenv("FIXTURES_SIGNING_KEY")  # no key needed when there is nothing to publish
    manifest = tmp_path / "app_release.json"
    if case == "placeholder":
        write(tmp_path, with_(sha256="", size=0))
    out = tmp_path / "site"
    assert main(["publish-update", "--manifest", str(manifest), "--out", str(out)]) == EXIT_OK
    assert not (out / "update.json").exists()
    assert not (out / "update.json.sig").exists()


def test_repository_manifest_is_valid_or_placeholder():
    """publisher/app_release.json committed in the repo must never break the workflow."""
    doc = app_release.load(REPO_MANIFEST)
    raw = json.loads(REPO_MANIFEST.read_text(encoding="utf-8"))
    assert doc is None or doc["version_code"] >= 1
    assert raw["apk_url"].startswith(app_release.APK_URL_PREFIX)


@pytest.mark.parametrize("url", [
    "http://github.com/davidjahan720/football-rama/releases/download/v1.0.1/MatchsEtChaines-1.0.1.apk",
    "https://github.com/someone/football-rama/releases/download/v1.0.1/MatchsEtChaines-1.0.1.apk",
    "https://github.com.evil.example/davidjahan720/football-rama/releases/download/v1.0.1/MatchsEtChaines-1.0.1.apk",
    "https://user@github.com/davidjahan720/football-rama/releases/download/v1.0.1/MatchsEtChaines-1.0.1.apk",
    "https://github.com/davidjahan720/football-rama/releases/download/v1.0.1/MatchsEtChaines-1.0.1.apk?x=1",
    "https://github.com/davidjahan720/football-rama/releases/download/v1.0.1/../MatchsEtChaines-1.0.1.apk",
    "https://github.com/davidjahan720/football-rama/releases/download/v1.0.1/%2e%2e/MatchsEtChaines-1.0.1.apk",
    "https://github.com/davidjahan720/football-rama/releases/download/v1.0.1/MatchsEtChaines-1.0.1.zip",
    "https://github.com/davidjahan720/football-rama/releases/download/v1.0.2/MatchsEtChaines-1.0.1.apk",
    "https://github.com/davidjahan720/football-rama/releases/download/v1.0.1/Other-1.0.1.apk",
    "https://github.com:8443/davidjahan720/football-rama/releases/download/v1.0.1/MatchsEtChaines-1.0.1.apk",
    42,
])
def test_bad_url_refused(env, tmp_path, url):
    out = tmp_path / "site"
    assert main(["publish-update", "--manifest", str(write(tmp_path, with_(apk_url=url))), "--out", str(out)]) == EXIT_FAILURE
    assert not out.exists() or not any(out.iterdir())


@pytest.mark.parametrize("changes", [
    {"sha256": "abc"},
    {"sha256": "0123456789ABCDEF" * 4},
    {"sha256": "0123456789abcdef" * 4 + "0"},
    {"sha256": "g" * 64},
    {"version_code": 0},
    {"version_code": -1},
    {"version_code": 1.5},
    {"version_code": "2"},
    {"version_code": True},
    {"version_code": 2**31},
    {"version_name": "1.0"},
    {"version_name": "1.0.1-beta"},
    {"size": 0},
    {"size": app_release.MAX_APK_BYTES + 1},
    {"size": "12"},
    {"min_sdk": 0},
    {"schema": 2},
    {"schema": True},
    {"notes": "x" * (app_release.MAX_NOTES + 1)},
    {"notes": 3},
    {"extra": "champ inconnu"},
    {"sha256": ...},
    {"apk_url": ...},
])
def test_invalid_manifest_refused(env, tmp_path, changes):
    out = tmp_path / "site"
    assert main(["publish-update", "--manifest", str(write(tmp_path, with_(**changes))), "--out", str(out)]) == EXIT_FAILURE
    assert not (out / "update.json").exists()


@pytest.mark.parametrize("raw", [
    "pas du json",
    "[1, 2]",
    '{"schema": 1, "schema": 1}',
    "﻿" + json.dumps(VALID),  # BOM written by PowerShell 5.1 -> refused rather than misread
])
def test_unreadable_manifest_refused(env, tmp_path, raw):
    out = tmp_path / "site"
    assert main(["publish-update", "--manifest", str(write(tmp_path, None, raw)), "--out", str(out)]) == EXIT_FAILURE


def test_previous_files_untouched_on_invalid_manifest(env, tmp_path):
    out = tmp_path / "site"
    assert main(["publish-update", "--manifest", str(write(tmp_path, VALID)), "--out", str(out)]) == EXIT_OK
    before = {p.name: p.read_bytes() for p in out.iterdir()}
    assert main(["publish-update", "--manifest", str(write(tmp_path, with_(sha256="xyz"))), "--out", str(out)]) == EXIT_FAILURE
    assert {p.name: p.read_bytes() for p in out.iterdir()} == before


def test_wrong_verify_key_writes_nothing(env, tmp_path):
    other = public_key_b64(Ed25519PrivateKey.generate())
    out = tmp_path / "site"
    assert main(["publish-update", "--manifest", str(write(tmp_path, VALID)), "--out", str(out),
                 "--verify-key", other]) == EXIT_FAILURE
    assert not (out / "update.json").exists()


def test_missing_or_invalid_key(env, monkeypatch, tmp_path, capsys, caplog):
    manifest = write(tmp_path, VALID)
    monkeypatch.delenv("FIXTURES_SIGNING_KEY")
    assert main(["publish-update", "--manifest", str(manifest), "--out", str(tmp_path / "a")]) == EXIT_CONFIG
    bad = "Zm9vYmFyYmF6" * 3
    monkeypatch.setenv("FIXTURES_SIGNING_KEY", bad)
    assert main(["publish-update", "--manifest", str(manifest), "--out", str(tmp_path / "b")]) == EXIT_CONFIG
    captured = capsys.readouterr()
    assert bad not in captured.out + captured.err + caplog.text
    assert main(["publish-update", "--manifest", str(manifest), "--out", str(tmp_path / "c"), "--verify-key", ""]) in (
        EXIT_CONFIG,)


def test_check_update_command(tmp_path, monkeypatch):
    monkeypatch.delenv("FIXTURES_SIGNING_KEY", raising=False)
    assert main(["check-update", "--manifest", str(write(tmp_path, VALID))]) == EXIT_OK
    assert main(["check-update", "--manifest", str(write(tmp_path, with_(size=-1)))]) == EXIT_FAILURE
    assert main(["check-update", "--manifest", str(tmp_path / "absent.json")]) == EXIT_OK


def test_url_rule_matches_android_prefix():
    assert app_release.is_allowed_apk_url(VALID["apk_url"])
    assert not app_release.is_allowed_apk_url(app_release.APK_URL_PREFIX + "../x/a.apk")
    assert not app_release.is_allowed_apk_url(app_release.APK_URL_PREFIX + "../a.apk")
    assert not app_release.is_allowed_apk_url(app_release.APK_URL_PREFIX + "v1/.apk")
