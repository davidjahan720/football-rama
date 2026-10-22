"""uefa.com feed (Europa League / Conference League): conversion, failures isolated from the main calendar."""
from __future__ import annotations

import copy
import json
import urllib.error
import urllib.parse
from datetime import UTC, date, datetime

import pytest

from football_rama_publisher import uefa
from football_rama_publisher.build import Window, select_matches
from football_rama_publisher.client import FetchError
from football_rama_publisher.publish import publish

from conftest import DATA, FAKE_TOKEN, FakeResponse

# The sample was captured on 2026-10-22 (UEL + UECL matchday)
UEFA_NOW = datetime(2026, 10, 22, 6, 0, tzinfo=UTC)


@pytest.fixture
def uefa_raw() -> list[dict]:
    return json.loads((DATA / "uefa_sample.json").read_text(encoding="utf-8"))


def opener_for(raw: list[dict], calls: list | None = None):
    """Fake urlopen answering per competitionId with the matching sample entries."""
    def opener(req, timeout):
        assert req.full_url.startswith(uefa.API_URL + "?")
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(req.full_url).query)
        if calls is not None:
            calls.append(query)
        comp = query["competitionId"][0]
        page = [m for m in raw if m["competition"]["id"] == comp] if query["offset"] == ["0"] else []
        return FakeResponse(json.dumps(page).encode("utf-8"))
    return opener


def test_fetch_converts_both_competitions(uefa_raw):
    calls = []
    matches = uefa.fetch_uefa_matches(date(2026, 10, 22), date(2026, 10, 24), opener=opener_for(uefa_raw, calls))
    assert [q["competitionId"] for q in calls] == [["14"], ["2019"]]
    assert all(q["fromDate"] == ["2026-10-22"] and q["toDate"] == ["2026-10-24"] for q in calls)
    assert {m["competition"]["code"] for m in matches} == {"UEL", "UECL"}
    assert len(matches) == len(uefa_raw)
    juve = next(m for m in matches if m["id"] == uefa.ID_OFFSET + 2050093)
    assert juve["status"] == "TIMED" and juve["utcDate"] == "2026-10-22T16:45:00Z"
    assert juve["homeTeam"]["name"] == "Juventus" and juve["awayTeam"]["shortName"] == "Rennes"


def test_converted_matches_go_through_select_matches(uefa_raw):
    matches = uefa.fetch_uefa_matches(date(2026, 10, 22), date(2026, 10, 24), opener=opener_for(uefa_raw))
    kept = select_matches(matches, Window.for_now(UEFA_NOW))
    assert len(kept) == len(uefa_raw)
    usg = next(m for m in kept if m["home"]["name"] == "R. Union Saint-Gilloise")
    assert usg["competition"] == "UEL"
    assert "union sg" in usg["home"]["aliases"]
    # ids never collide with football-data ids and fit in a 32-bit int
    assert all(uefa.ID_OFFSET < m["id"] < 2**31 for m in kept)


def test_alt_names_become_aliases():
    raw = {
        "id": "42", "status": "UPCOMING", "kickOffTime": {"dateTime": "2026-10-22T16:45:00Z"},
        "competition": {"id": "2019"},
        "homeTeam": {"isPlaceHolder": False, "internationalName": "Lech",
                     "translations": {"displayOfficialName": {"EN": "KKS Lech Poznań"},
                                      "shortName": {"EN": "Lech"},
                                      "displayName": {"EN": "Lech Poznań", "FR": "Lech Poznań", "RU": "Лех"}}},
        "awayTeam": {"isPlaceHolder": False, "internationalName": "Paris",
                     "translations": {"displayName": {"FR": "Paris"}}},
    }
    m = uefa.convert(raw, "UECL")
    assert m["homeTeam"]["altNames"] == ["Lech Poznań"]   # name / shortName / non-Latin languages left out
    kept = select_matches([m], Window.for_now(UEFA_NOW))[0]
    assert "lech poznan" in kept["home"]["aliases"]
    # A generic word ("paris") stays out of the aliases even when it comes from UEFA
    assert "paris" not in kept["away"]["aliases"]


@pytest.mark.parametrize("change", [
    lambda m: m.update(status="FINISHED"),
    lambda m: m.update(status="POSTPONED"),
    lambda m: m["homeTeam"].update(isPlaceHolder=True),
    lambda m: m.update(awayTeam=None),
    lambda m: m.update(kickOffTime={}),
])
def test_unusable_matches_dropped(uefa_raw, change):
    m = copy.deepcopy(uefa_raw[0])
    change(m)
    assert select_matches([uefa.convert(m, "UEL")], Window.for_now(UEFA_NOW)) == []


@pytest.mark.parametrize("change", [
    lambda m: m.update(id="abc"),
    lambda m: m.update(id=True),
    lambda m: m.update(id="0"),
    lambda m: m.update(id=str(uefa.MAX_UEFA_ID + 1)),
    lambda m: m.update(competition={"id": "1"}),       # another competition returned despite the filter
])
def test_malformed_matches_rejected(uefa_raw, change):
    m = copy.deepcopy(uefa_raw[0])
    change(m)
    assert uefa.convert(m, "UEL") is None


def test_pagination_stops_on_short_page(uefa_raw):
    calls = []
    full = [dict(uefa_raw[0], id=str(i)) for i in range(1, uefa.PAGE_SIZE + 1)]

    def opener(req, timeout):
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(req.full_url).query)
        calls.append((query["competitionId"][0], query["offset"][0]))
        page = full if query["competitionId"] == ["14"] and query["offset"] == ["0"] else []
        return FakeResponse(json.dumps(page).encode("utf-8"))

    matches = uefa.fetch_uefa_matches(date(2026, 10, 22), date(2026, 10, 24), opener=opener)
    assert len(matches) == uefa.PAGE_SIZE
    assert calls == [("14", "0"), ("14", str(uefa.PAGE_SIZE)), ("2019", "0")]


@pytest.mark.parametrize("body", [b"not json", b'{"error": {"status": 404}}', b"\xff\xfe"])
def test_bad_payload_raises_fetch_error(body):
    with pytest.raises(FetchError):
        uefa.fetch_uefa_matches(date(2026, 10, 22), date(2026, 10, 24),
                                opener=lambda req, timeout: FakeResponse(body))


def test_http_error_raises_fetch_error():
    def opener(req, timeout):
        raise urllib.error.HTTPError(req.full_url, 503, "unavailable", None, None)
    with pytest.raises(FetchError, match="HTTP 503"):
        uefa.fetch_uefa_matches(date(2026, 10, 22), date(2026, 10, 24), opener=opener)


def test_publish_merges_uefa_matches(tmp_path, private_key, uefa_raw):
    def fetch_uefa(date_from, date_to):
        return uefa.fetch_uefa_matches(date_from, date_to, opener=opener_for(uefa_raw))

    doc = publish(tmp_path / "dist", FAKE_TOKEN, private_key, now=UEFA_NOW,
                  fetch=lambda *a: [], fetch_uefa=fetch_uefa)
    assert {m["competition"] for m in doc["matches"]} == {"UEL", "UECL"}
    assert doc["competitions"]["UEL"] == "Ligue Europa"


def test_uefa_failure_does_not_block_publication(tmp_path, private_key, raw_matches, caplog):
    def broken(date_from, date_to):
        raise FetchError("uefa: HTTP 503")

    from conftest import NOW
    doc = publish(tmp_path / "dist", FAKE_TOKEN, private_key, now=NOW,
                  fetch=lambda *a: raw_matches, fetch_uefa=broken)
    assert doc["matches"]
    assert not {"UEL", "UECL"} & {m["competition"] for m in doc["matches"]}
    assert (tmp_path / "dist" / "fixtures.json").exists()
    assert "source UEFA indisponible" in caplog.text
