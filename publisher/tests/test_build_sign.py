import base64
import json
from datetime import UTC, datetime

from football_rama_publisher.build import Window, build_document, select_matches, serialize
from football_rama_publisher.signing import (SigningKeyError, load_private_key, load_public_key, public_key_b64,
                                             sign, verify)

from conftest import NOW
import pytest


# ------------------------------------------------------------------ window J / J+1

def test_window_bounds():
    w = Window.for_now(NOW)
    assert w.start == datetime(2026, 10, 20, 0, 0, tzinfo=UTC)
    assert w.end == datetime(2026, 10, 22, 3, 0, tzinfo=UTC)


def test_window_just_before_midnight():
    w = Window.for_now(datetime(2026, 12, 31, 23, 59, 59, tzinfo=UTC))
    assert w.start == datetime(2026, 12, 31, tzinfo=UTC)
    assert w.end == datetime(2027, 1, 2, 3, 0, tzinfo=UTC)


def test_window_filters_matches(raw_matches):
    ids = [m["id"] for m in select_matches(raw_matches, Window.for_now(NOW))]
    assert 559654 not in ids          # Brest - Nice, 2026-10-23: outside the window
    assert 575368 in ids              # Como - Man United, J+1
    assert len(ids) == 6


def test_window_edges(raw_matches):
    m = raw_matches[0]
    w = Window.for_now(NOW)
    m["utcDate"] = "2026-10-22T03:00:00Z"
    assert [x["id"] for x in select_matches([m], w)] == [m["id"]]
    m["utcDate"] = "2026-10-22T03:00:01Z"
    assert select_matches([m], w) == []
    m["utcDate"] = "2026-10-19T23:59:59Z"
    assert select_matches([m], w) == []


# ------------------------------------------------------------------ statuses / duplicates / bad data

@pytest.mark.parametrize("status, kept", [
    ("SCHEDULED", True), ("TIMED", True), ("IN_PLAY", True), ("PAUSED", True),
    ("EXTRA_TIME", True), ("PENALTY_SHOOTOUT", True),
    ("POSTPONED", False), ("CANCELLED", False), ("FINISHED", False), ("SUSPENDED", False),
    ("AWARDED", False), (None, False), ("UNKNOWN", False),
])
def test_status_filter(raw_matches, status, kept):
    m = raw_matches[0]
    m["status"] = status
    assert bool(select_matches([m], Window.for_now(NOW))) is kept


def test_duplicates_and_sorting(raw_matches):
    doubled = raw_matches + raw_matches[::-1]
    result = select_matches(doubled, Window.for_now(NOW))
    ids = [m["id"] for m in result]
    assert len(ids) == len(set(ids)) == 6
    assert result == sorted(result, key=lambda m: (m["kickoff_utc"], m["id"]))


def test_invalid_entries_skipped(raw_matches):
    good = raw_matches[0]
    bad = [
        "not a dict",
        {**good, "id": "575360"},
        {**good, "id": True},
        {**good, "utcDate": "garbage"},
        {**good, "utcDate": "2026-10-20T19:00:00"},         # no timezone
        {**good, "competition": {"code": "SA"}},
        {**good, "homeTeam": {"name": None}},                # TBD knock-out
        {**good, "awayTeam": {"name": "   "}},
    ]
    assert select_matches(bad, Window.for_now(NOW)) == []


# ------------------------------------------------------------------ document

def test_document_matches_contract(raw_matches):
    doc = build_document(raw_matches, NOW)
    assert doc["schema"] == 1
    assert doc["generated_at"] == "2026-10-20T06:00:00Z"
    assert doc["valid_until"] == "2026-10-21T18:00:00Z"
    assert doc["window"] == {"from": "2026-10-20T00:00:00Z", "to": "2026-10-22T03:00:00Z"}
    assert doc["competitions"] == {"PL": "Premier League", "FL1": "Ligue 1", "CL": "Ligue des champions"}
    assert set(doc["matching"]) == {"competition_keywords", "excluded_title_words"}
    assert set(doc["matching"]["competition_keywords"]) == {"PL", "FL1", "CL"}
    psg = next(m for m in doc["matches"] if m["id"] == 575367)
    assert psg["competition"] == "CL"
    assert psg["kickoff_utc"] == "2026-10-20T19:00:00Z"
    assert psg["home"]["name"] == "Paris Saint-Germain FC"
    assert psg["home"]["short"] == "PSG"
    assert "psg" in psg["home"]["aliases"]
    assert "barca" in psg["away"]["aliases"] and "barcelona" in psg["away"]["tokens"]
    for m in doc["matches"]:
        assert set(m) == {"id", "competition", "kickoff_utc", "home", "away"}
        for side in (m["home"], m["away"]):
            assert set(side) == {"name", "short", "aliases", "tokens"}


def test_zero_matches_is_valid():
    doc = build_document([], NOW)
    assert doc["matches"] == []
    assert json.loads(serialize(doc))["matches"] == []


def test_serialize_is_compact_sorted_utf8(raw_matches):
    doc = build_document(raw_matches, NOW)
    data = serialize(doc)
    assert data == serialize(build_document(list(reversed(raw_matches)), NOW))  # reproducible
    text = data.decode("utf-8")
    assert "Fenerbahçe" in text                     # real UTF-8, not \u escapes
    assert ": " not in text and ", " not in text.replace("Fenerbahçe, ", "")
    assert text.startswith('{"competitions":')     # sorted keys
    assert not text.endswith("\n")


# ------------------------------------------------------------------ signature

def test_signature_roundtrip_and_tamper(raw_matches, private_key):
    data = serialize(build_document(raw_matches, NOW))
    sig = sign(data, private_key)
    assert b"\n" not in sig and len(base64.b64decode(sig, validate=True)) == 64
    pub = load_public_key(public_key_b64(private_key))
    assert verify(data, sig, pub)
    for i in (0, len(data) // 2, len(data) - 1):
        tampered = bytearray(data)
        tampered[i] ^= 0x01
        assert not verify(bytes(tampered), sig, pub)
    bad_sig = bytearray(base64.b64decode(sig))
    bad_sig[0] ^= 0x01
    assert not verify(data, base64.b64encode(bytes(bad_sig)), pub)
    assert not verify(data, b"not base64 !!", pub)


def test_wrong_public_key_rejected(private_key):
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    other = load_public_key(public_key_b64(Ed25519PrivateKey.generate()))
    assert not verify(b"{}", sign(b"{}", private_key), other)


def test_load_private_key_roundtrip(private_key):
    b64 = base64.b64encode(private_key.private_bytes_raw()).decode()
    loaded = load_private_key(b64)
    assert public_key_b64(loaded) == public_key_b64(private_key)


@pytest.mark.parametrize("value", ["", "!!!!", base64.b64encode(b"x" * 31).decode(), base64.b64encode(b"x" * 64).decode()])
def test_invalid_key_message_does_not_echo_key(value):
    with pytest.raises(SigningKeyError) as exc:
        load_private_key(value)
    if value:
        assert value not in str(exc.value)
