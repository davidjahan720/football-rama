"""Europa League / Conference League schedule from the public JSON feed behind uefa.com/match-calendar (stdlib only).

Not a documented API (no key, no contract): it can change or disappear without notice. The publisher therefore
treats it as OPTIONAL: any failure raises client.FetchError, publish() logs it and publishes the football-data
calendar without these two competitions.

Matches are converted to the football-data v4 shape consumed by build.select_matches, plus "altNames" (team names
in the Latin-script languages published by UEFA) to improve EPG matching.
"""
from __future__ import annotations

import json
import logging
import urllib.error
import urllib.parse
import urllib.request
from datetime import date
from typing import Callable

from .client import FetchError, _NoRedirect

log = logging.getLogger(__name__)

API_URL = "https://match.uefa.com/v5/matches"
TIMEOUT_S = 30
PAGE_SIZE = 100
MAX_PAGES = 3              # 3 days of one competition = ~40 matches: 1 page in practice
MAX_BODY_BYTES = 5 * 1024 * 1024
USER_AGENT = "football-rama-publisher/1.0"

# Our code -> UEFA competitionId
COMPETITION_IDS = {"UEL": "14", "UECL": "2019"}

# UEFA ids live in another numbering space than football-data ids: shifted to never collide (fits in a Kotlin Int).
ID_OFFSET = 1_000_000_000
MAX_UEFA_ID = 999_999_999

# UEFA status -> football-data status kept by build.KEPT_STATUSES; anything else (FINISHED, POSTPONED...) is dropped
STATUS_MAP = {"UPCOMING": "TIMED", "LIVE": "IN_PLAY"}

ALT_NAME_LANGUAGES = ("EN", "FR", "DE", "ES", "IT", "PT")
ALT_NAME_FIELDS = ("displayName", "displayOfficialName", "shortName")
MAX_NAME_LEN = 80

_urlopen = urllib.request.build_opener(_NoRedirect).open


def _text(value: object) -> str:
    return value.strip() if isinstance(value, str) and len(value.strip()) <= MAX_NAME_LEN else ""


def _team(raw: object) -> dict:
    """UEFA team -> {"name", "shortName", "altNames"}; {} for a placeholder (team not known yet)."""
    if not isinstance(raw, dict) or raw.get("isPlaceHolder") is True:
        return {}
    tr = raw.get("translations") if isinstance(raw.get("translations"), dict) else {}

    def tr_text(field: str, lang: str) -> str:
        values = tr.get(field)
        return _text(values.get(lang)) if isinstance(values, dict) else ""

    name = tr_text("displayOfficialName", "EN") or _text(raw.get("internationalName"))
    if not name:
        return {}
    short = tr_text("shortName", "EN") or tr_text("displayName", "EN")
    alt = {_text(raw.get("internationalName"))}
    alt.update(tr_text(f, lang) for f in ALT_NAME_FIELDS for lang in ALT_NAME_LANGUAGES)
    alt -= {"", name, short}
    return {"name": name, "shortName": short, "altNames": sorted(alt)}


def convert(raw: object, code: str) -> dict | None:
    """One UEFA match -> football-data-like dict, or None if malformed / not from the requested competition."""
    if not isinstance(raw, dict):
        return None
    comp = raw.get("competition")
    if not isinstance(comp, dict) or str(comp.get("id")) != COMPETITION_IDS[code]:
        return None
    raw_id = raw.get("id")
    if not isinstance(raw_id, (str, int)) or isinstance(raw_id, bool) or not str(raw_id).isdigit():
        return None
    uefa_id = int(raw_id)
    if not 0 < uefa_id <= MAX_UEFA_ID:
        return None
    kickoff = raw.get("kickOffTime")
    status = raw.get("status")
    return {
        "id": ID_OFFSET + uefa_id,
        "competition": {"code": code},
        "utcDate": kickoff.get("dateTime") if isinstance(kickoff, dict) else None,
        "status": STATUS_MAP.get(status, status if isinstance(status, str) else None),
        "homeTeam": _team(raw.get("homeTeam")),
        "awayTeam": _team(raw.get("awayTeam")),
    }


def _get_page(competition_id: str, date_from: date, date_to: date, offset: int, opener: Callable) -> list:
    query = urllib.parse.urlencode({
        "competitionId": competition_id,
        "fromDate": date_from.isoformat(),
        "toDate": date_to.isoformat(),
        "order": "ASC",
        "offset": offset,
        "limit": PAGE_SIZE,
    })
    req = urllib.request.Request(f"{API_URL}?{query}", method="GET",
                                 headers={"Accept": "application/json", "User-Agent": USER_AGENT})
    try:
        with opener(req, timeout=TIMEOUT_S) as resp:
            body = resp.read(MAX_BODY_BYTES + 1)
    except urllib.error.HTTPError as e:
        raise FetchError(f"uefa: HTTP {e.code}") from None
    except urllib.error.URLError as e:
        raise FetchError(f"uefa: connexion impossible ({type(e.reason).__name__})") from None
    except (TimeoutError, OSError) as e:
        raise FetchError(f"uefa: erreur reseau ({type(e).__name__})") from None
    if len(body) > MAX_BODY_BYTES:
        raise FetchError("uefa: reponse trop volumineuse")
    try:
        payload = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise FetchError("uefa: reponse JSON invalide") from None
    if not isinstance(payload, list):
        raise FetchError("uefa: format inattendu (liste de matchs attendue)")
    return payload


def fetch_uefa_matches(date_from: date, date_to: date, *, opener: Callable | None = None) -> list[dict]:
    """All EL + UECL matches between the two dates (inclusive), football-data shape. Raises FetchError."""
    opener = opener or _urlopen
    result: list[dict] = []
    for code, competition_id in COMPETITION_IDS.items():
        skipped = 0
        for page in range(MAX_PAGES):
            raw = _get_page(competition_id, date_from, date_to, page * PAGE_SIZE, opener)
            for m in raw:
                converted = convert(m, code)
                if converted is None:
                    skipped += 1
                else:
                    result.append(converted)
            if len(raw) < PAGE_SIZE:
                break
        if skipped:
            log.warning("uefa %s : %d match(s) ignore(s) (format inattendu)", code, skipped)
    log.info("uefa : %d matchs Ligue Europa / Conference League recuperes", len(result))
    return result
