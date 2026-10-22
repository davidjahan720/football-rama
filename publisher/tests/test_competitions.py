"""Scope extension (2026-10-22): BL1, SA, PD, DED, PPL, ELC next to PL, FL1, CL.

The EPG matching is checked with the reference algorithm of scripts/poc_epg.py (match / foreign_team_in_title),
fed with the publisher's aliases and keywords: the prototype's own lists stay frozen on the 3 initial
competitions (validated on real data on 2026-10-20), the publisher is the source of truth for the lists.
"""
from __future__ import annotations

import importlib.util
import json
import sys
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest

from football_rama_publisher import aliases as aliases_mod
from football_rama_publisher.aliases import (COMPETITION_KEYWORDS, COMPETITIONS, EXCLUDED_TITLE_WORDS, GENERIC_WORDS,
                                             AliasFileError, extra_aliases, team_aliases, team_tokens)
from football_rama_publisher.build import build_document, select_matches, serialize, Window
from football_rama_publisher.normalize import norm
from football_rama_publisher.publish import publish

from conftest import FAKE_TOKEN, NOW

POC_PATH = Path(__file__).resolve().parents[2] / "scripts" / "poc_epg.py"
APP_MAX_JSON_BYTES = 2 * 1024 * 1024  # FixturesVerifier.MAX_JSON_BYTES (android/core)
NEW_CODES = {"BL1", "SA", "PD", "DED", "PPL", "ELC"}


# ------------------------------------------------------------------ competitions

def test_competition_codes_and_french_names():
    assert COMPETITIONS == {
        "PL": "Premier League", "FL1": "Ligue 1", "CL": "Ligue des champions",
        "BL1": "Bundesliga", "SA": "Serie A", "PD": "LaLiga", "DED": "Eredivisie",
        "PPL": "Liga Portugal", "ELC": "Championship",
    }
    # Paid plans on football-data.org: never requested
    assert not {"EL", "ECL", "UEL", "UECL"} & set(COMPETITIONS)


def test_single_api_call_with_all_codes(tmp_path, private_key):
    calls = []

    def fake_fetch(token, date_from, date_to, competitions):
        calls.append((token, date_from, date_to, list(competitions)))
        return []

    publish(tmp_path / "dist", FAKE_TOKEN, private_key, now=NOW, fetch=fake_fetch)
    assert len(calls) == 1
    assert set(calls[0][3]) == set(COMPETITIONS) and len(calls[0][3]) == 9
    assert ",".join(calls[0][3]).count(",") == 8


def test_every_competition_has_normalised_keywords():
    assert set(COMPETITION_KEYWORDS) == set(COMPETITIONS)
    for code, words in COMPETITION_KEYWORDS.items():
        assert words, code
        assert all(norm(w) == w and w for w in words), code
        assert len(set(words)) == len(words), code


@pytest.mark.parametrize("code, expected", [
    ("BL1", "bundesliga"), ("SA", "serie a"), ("SA", "calcio"), ("PD", "laliga"), ("PD", "la liga"),
    ("PD", "liga espanola"), ("PD", "liga ea sports"), ("DED", "eredivisie"), ("PPL", "liga portugal"),
    ("PPL", "primeira liga"), ("PPL", "liga betclic"), ("ELC", "efl championship"),
])
def test_expected_keywords(code, expected):
    assert expected in COMPETITION_KEYWORDS[code]


def test_ambiguous_keywords_avoided():
    # "championship" alone: rugby, Scottish Championship, golf... (see docs/CONTRAT_FIXTURES.md)
    assert "championship" not in COMPETITION_KEYWORDS["ELC"]
    # "liga" alone would be every league of the Spanish / Portuguese speaking world
    assert all("liga" not in kws for kws in COMPETITION_KEYWORDS.values())


def test_lower_divisions_excluded():
    for w in ("2 bundesliga", "serie b", "laliga hypermotion", "liga portugal 2", "eerste divisie", "league one"):
        assert w in EXCLUDED_TITLE_WORDS
    assert all(norm(w) == w for w in EXCLUDED_TITLE_WORDS)
    assert len(set(EXCLUDED_TITLE_WORDS)) == len(EXCLUDED_TITLE_WORDS)


# ------------------------------------------------------------------ aliases of the new clubs

def aliases_of(name: str, short: str) -> set[str]:
    return set(team_aliases({"name": name, "shortName": short}))


@pytest.mark.parametrize("name, short, expected", [
    ("FC Bayern München", "Bayern", {"bayern", "bayern munich", "bayern munchen"}),
    ("Bayer 04 Leverkusen", "Leverkusen", {"leverkusen", "bayer leverkusen"}),
    ("Borussia Dortmund", "Dortmund", {"dortmund", "bvb"}),
    ("Eintracht Frankfurt", "Frankfurt", {"frankfurt", "francfort"}),
    ("Borussia Mönchengladbach", "M'gladbach", {"gladbach", "monchengladbach"}),
    ("1. FC Köln", "1. FC Köln", {"cologne", "fc koln"}),
    ("Hamburger SV", "HSV", {"hsv", "hamburg"}),
    ("FC Internazionale Milano", "Inter", {"inter", "inter milan"}),
    ("AC Milan", "Milan", {"ac milan", "milan"}),
    ("Juventus FC", "Juventus", {"juventus", "juve"}),
    ("SSC Napoli", "Napoli", {"napoli", "naples"}),
    ("AS Roma", "Roma", {"roma", "as rome"}),
    ("SS Lazio", "Lazio", {"lazio"}),
    ("Club Atlético de Madrid", "Atleti", {"atletico madrid", "atletico de madrid", "atleti"}),
    ("Real Madrid CF", "Real Madrid", {"real madrid"}),
    ("FC Barcelona", "Barça", {"barca", "barcelone", "barcelona"}),
    ("Sevilla FC", "Sevilla", {"sevilla", "seville"}),
    ("Real Betis Balompié", "Real Betis", {"betis", "real betis"}),
    ("Athletic Club", "Athletic", {"athletic bilbao", "athletic club"}),
    ("AFC Ajax", "Ajax", {"ajax"}),
    ("PSV", "PSV", {"psv", "psv eindhoven"}),
    ("Feyenoord Rotterdam", "Feyenoord", {"feyenoord"}),
    ("AZ", "AZ", {"az alkmaar"}),
    ("Sport Lisboa e Benfica", "Benfica", {"benfica"}),
    ("FC Porto", "Porto", {"porto", "fc porto"}),
    ("Sporting Clube de Portugal", "Sporting CP",
     {"sporting cp", "sporting lisbonne", "sporting lisbon", "sporting portugal", "sporting"}),
    ("Sporting Clube de Braga", "Braga", {"braga", "sc braga"}),
    ("West Bromwich Albion FC", "West Brom", {"west brom"}),
    ("Queens Park Rangers FC", "QPR", {"qpr"}),
])
def test_new_club_aliases(name, short, expected):
    assert expected <= aliases_of(name, short)


@pytest.mark.parametrize("name, short, forbidden", [
    # A generic word alone never identifies a team
    ("Athletic Club", "Athletic", {"athletic"}),
    ("Real Madrid CF", "Real Madrid", {"real", "madrid"}),
    ("Club Atlético de Madrid", "Atleti", {"madrid"}),
    ("Sporting Clube de Braga", "Braga", {"sporting"}),       # "sporting" alone is reserved to Sporting CP
    ("RCD Espanyol de Barcelona", "Espanyol", {"barcelona"}),
    ("Sporting Clube de Portugal", "Sporting CP", {"portugal"}),
    ("Charlton Athletic FC", "Charlton Athletic", {"athletic"}),
])
def test_generic_words_are_not_aliases(name, short, forbidden):
    assert not forbidden & aliases_of(name, short)


@pytest.mark.parametrize("name, short, forbidden", [
    ("Sporting Clube de Portugal", "Sporting CP", {"sporting", "portugal", "clube"}),
    ("RCD Espanyol de Barcelona", "Espanyol", {"barcelona"}),     # would match every FC Barcelona programme
    ("FC Bayern München", "Bayern", {"munchen"}),                  # TSV 1860 Munchen
    ("Feyenoord Rotterdam", "Feyenoord", {"rotterdam"}),           # Sparta / Excelsior Rotterdam
    ("Sheffield Wednesday FC", "Sheffield Wed", {"wednesday"}),
    ("Derby County FC", "Derby County", {"derby"}),                # "le derby", "derby della Madonnina"
    ("Queens Park Rangers FC", "QPR", {"rangers", "queens"}),      # Rangers (Glasgow), Queen's Park
    ("Eintracht Frankfurt", "Frankfurt", {"eintracht"}),           # Eintracht Braunschweig
])
def test_generic_words_are_not_tokens(name, short, forbidden):
    assert not forbidden & set(team_tokens({"name": name, "shortName": short}))


def test_listed_generic_aliases_are_deliberate():
    """Every generic word kept as alias must be a deliberate choice (documented in docs/CONTRAT_FIXTURES.md)."""
    listed = {v for values in extra_aliases().values() for v in values}
    assert listed & GENERIC_WORDS == {"sporting", "atletico", "barcelona"}


def test_duplicate_key_in_aliases_file_rejected(tmp_path, monkeypatch):
    bad = tmp_path / "aliases.json"
    bad.write_text('{"version": 1, "aliases": {"psv": ["psv"], "psv": ["eindhoven"]}}', encoding="utf-8")
    monkeypatch.setattr(aliases_mod, "ALIASES_FILE", bad)
    extra_aliases.cache_clear()
    try:
        with pytest.raises(AliasFileError, match="double"):
            extra_aliases()
    finally:
        extra_aliases.cache_clear()


def test_non_normalised_alias_rejected(tmp_path, monkeypatch):
    bad = tmp_path / "aliases.json"
    bad.write_text('{"version": 1, "aliases": {"sevilla": ["Séville"]}}', encoding="utf-8")
    monkeypatch.setattr(aliases_mod, "ALIASES_FILE", bad)
    extra_aliases.cache_clear()
    try:
        with pytest.raises(AliasFileError):
            extra_aliases()
    finally:
        extra_aliases.cache_clear()


# ------------------------------------------------------------------ build: new codes kept, unknown codes ignored

def raw_match(match_id: int, code: str, kickoff: datetime, home: tuple[str, str], away: tuple[str, str]) -> dict:
    return {
        "id": match_id, "utcDate": kickoff.strftime("%Y-%m-%dT%H:%M:%SZ"), "status": "TIMED",
        "competition": {"code": code, "name": code},
        "homeTeam": {"name": home[0], "shortName": home[1]}, "awayTeam": {"name": away[0], "shortName": away[1]},
    }


def test_new_codes_kept_and_unknown_codes_ignored():
    k = datetime(2026, 10, 20, 18, 30, tzinfo=UTC)
    raw = [raw_match(i, code, k, ("Home FC", "Home"), ("Away FC", "Away"))
           for i, code in enumerate(sorted(COMPETITIONS) + ["EL", "BSA", "XYZ", ""], start=1)]
    kept = select_matches(raw, Window.for_now(NOW))
    assert {m["competition"] for m in kept} == set(COMPETITIONS)


# ------------------------------------------------------------------ size of fixtures.json

def busy_weekend(n: int = 72) -> list[dict]:
    """~70 matches over J / J+1 with long real-world names (worst case for the file size)."""
    teams = [
        ("Borussia Mönchengladbach", "M'gladbach"), ("FC Internazionale Milano", "Inter"),
        ("Club Atlético de Madrid", "Atleti"), ("Sporting Clube de Portugal", "Sporting CP"),
        ("Real Betis Balompié", "Real Betis"), ("Wolverhampton Wanderers FC", "Wolverhampton"),
        ("Rayo Vallecano de Madrid", "Rayo Vallecano"), ("RCD Espanyol de Barcelona", "Espanyol"),
        ("Sport Lisboa e Benfica", "Benfica"), ("Queens Park Rangers FC", "QPR"),
        ("Paris Saint-Germain FC", "PSG"), ("FC Bayern München", "Bayern"),
    ]
    codes = sorted(COMPETITIONS)
    start = datetime(2026, 10, 20, 11, 0, tzinfo=UTC)
    return [raw_match(1000 + i, codes[i % len(codes)], start + timedelta(minutes=30 * i),
                      teams[i % len(teams)], teams[(i + 5) % len(teams)]) for i in range(n)]


def test_fixtures_size_stays_far_below_app_limit():
    doc = build_document(busy_weekend(), NOW)
    assert len(doc["matches"]) == 72
    size = len(serialize(doc))
    # ~70 matches -> about 50 KB: 40x margin under the 2 MB accepted by the app
    assert size < 200 * 1024 < APP_MAX_JSON_BYTES


# ------------------------------------------------------------------ EPG matching (reference algorithm)

@pytest.fixture(scope="module")
def poc():
    spec = importlib.util.spec_from_file_location("poc_epg_ref", POC_PATH)
    module = importlib.util.module_from_spec(spec)
    sys.modules["poc_epg_ref"] = module  # dataclasses need the module registered
    try:
        spec.loader.exec_module(module)
    finally:
        sys.modules.pop("poc_epg_ref", None)
    # Publisher lists (contract) instead of the prototype's frozen 3-competition lists
    module.COMPETITION_KEYWORDS = {k: list(v) for k, v in COMPETITION_KEYWORDS.items()}
    module.EXCLUDED_TITLE_WORDS = list(EXCLUDED_TITLE_WORDS)
    return module


KICKOFF = datetime(2026, 10, 21, 19, 0, tzinfo=UTC)


def fixture(poc, code, home, away):
    h, a = {"name": home[0], "shortName": home[1]}, {"name": away[0], "shortName": away[1]}
    return poc.Fixture(1, code, KICKOFF, home[0], away[0], team_aliases(h), team_aliases(a),
                       team_tokens(h), team_tokens(a))


def scores(poc, fx, title, desc=""):
    fx.hits.clear()
    p = poc.Programme("chan", KICKOFF - timedelta(minutes=15), KICKOFF + timedelta(hours=2), title,
                      norm(f"{title} {desc}"))
    poc.match([fx], [p])
    return [h["score"] for h in fx.hits]


SPORTING = ("Sporting Clube de Portugal", "Sporting CP")
BENFICA = ("Sport Lisboa e Benfica", "Benfica")
ARSENAL = ("Arsenal FC", "Arsenal")


@pytest.mark.parametrize("title, desc", [
    ("Sporting Gijón - Real Oviedo", ""),
    ("LaLiga Hypermotion: Sporting Gijón - Real Oviedo", ""),
    ("Sporting de Gijón x Real Oviedo", "Liga Portugal ? Non : LaLiga Hypermotion"),
    ("Fútbol: Sporting de Gijón", "LaLiga Hypermotion, jornada 11"),
    ("Sporting Braga - FC Porto", "Liga Portugal Betclic"),
])
def test_no_false_positive_for_sporting_cp(poc, title, desc):
    for fx in (fixture(poc, "PPL", SPORTING, BENFICA), fixture(poc, "CL", SPORTING, ARSENAL)):
        assert scores(poc, fx, title, desc) == []


@pytest.mark.parametrize("title, desc, expected", [
    ("Sporting x Benfica", "", ["fort"]),                        # Portuguese EPG: "Sporting" alone
    ("Liga Portugal: Sporting CP - Benfica", "", ["fort"]),
    ("Sporting Lisbonne / Benfica", "", ["fort"]),
    ("Clássico", "Liga Portugal Betclic : Sporting recebe o Benfica", ["moyen"]),
    ("Futebol", "Sporting em direto, Liga Portugal Betclic", ["moyen"]),
])
def test_sporting_cp_found(poc, title, desc, expected):
    assert scores(poc, fixture(poc, "PPL", SPORTING, BENFICA), title, desc) == expected


@pytest.mark.parametrize("code, home, away, title, desc, expected", [
    ("BL1", ("FC Bayern München", "Bayern"), ("Borussia Dortmund", "Dortmund"), "Bayern Munich - Dortmund", "", "fort"),
    ("BL1", ("FC Bayern München", "Bayern"), ("Borussia Dortmund", "Dortmund"), "Der Klassiker",
     "Bundesliga: FC Bayern empfängt den BVB", "moyen"),
    ("SA", ("FC Internazionale Milano", "Inter"), ("AC Milan", "Milan"), "Inter - AC Milan", "", "fort"),
    ("SA", ("SSC Napoli", "Napoli"), ("Juventus FC", "Juventus"), "Calcio", "Naples reçoit la Juve", "moyen"),
    ("PD", ("Real Madrid CF", "Real Madrid"), ("FC Barcelona", "Barça"), "LaLiga EA Sports: Real Madrid - Barça", "",
     "fort"),
    ("PD", ("Sevilla FC", "Sevilla"), ("Real Betis Balompié", "Real Betis"), "Séville / Betis", "", "fort"),
    ("DED", ("AFC Ajax", "Ajax"), ("Feyenoord Rotterdam", "Feyenoord"), "Ajax - Feyenoord", "", "fort"),
    ("PPL", ("FC Porto", "Porto"), BENFICA, "FC Porto x Benfica", "", "fort"),
    ("ELC", ("West Bromwich Albion FC", "West Brom"), ("Queens Park Rangers FC", "QPR"), "Live EFL: West Brom v QPR",
     "", "fort"),
    ("ELC", ("Coventry City FC", "Coventry City"), ("Norwich City FC", "Norwich City"), "Football",
     "Sky Bet Championship: Coventry host Norwich", "moyen"),
])
def test_new_competitions_found(poc, code, home, away, title, desc, expected):
    result = scores(poc, fixture(poc, code, home, away), title, desc)
    if expected == "fort_or_moyen":
        assert result in (["fort"], ["moyen"])
    else:
        assert result == [expected]


@pytest.mark.parametrize("code, home, away, title, desc", [
    # Rugby "Championship" + English city token: bare "championship" is not a keyword
    ("ELC", ("Coventry City FC", "Coventry City"), ("Norwich City FC", "Norwich City"), "Rugby Union",
     "RFU Championship: Coventry welcome Ealing Trailfinders"),
    # Brazilian Serie A with an Italian club homonym-free fixture
    ("SA", ("FC Internazionale Milano", "Inter"), ("AC Milan", "Milan"), "Brasileirão Série A",
     "Internacional x Grêmio"),
    # 2. Bundesliga programme
    ("BL1", ("FC Bayern München", "Bayern"), ("Borussia Dortmund", "Dortmund"), "2. Bundesliga: Konferenz",
     "Mit Dortmund II ? Nein."),
    # Athletic Club fixture vs a Charlton Athletic programme
    ("PD", ("Athletic Club", "Athletic"), ("Real Betis Balompié", "Real Betis"), "Charlton Athletic v Millwall",
     "EFL Championship"),
])
def test_known_false_positives_rejected(poc, code, home, away, title, desc):
    assert scores(poc, fixture(poc, code, home, away), title, desc) == []


def test_contract_example_still_valid():
    """docs/CONTRAT_FIXTURES.md example: schema unchanged (no new field)."""
    doc = build_document([], NOW)
    assert doc["schema"] == 1
    assert set(doc) == {"schema", "generated_at", "valid_until", "window", "competitions", "matching", "matches"}
    assert json.loads(serialize(doc))["competitions"]["BL1"] == "Bundesliga"
