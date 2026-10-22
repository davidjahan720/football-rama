"""Build the fixtures.json document (schema 1, docs/CONTRAT_FIXTURES.md)."""
from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

from . import SCHEMA_VERSION
from .aliases import ALL_COMPETITIONS, COMPETITION_KEYWORDS, EXCLUDED_TITLE_WORDS, team_aliases, team_tokens

log = logging.getLogger(__name__)

VALIDITY = timedelta(hours=36)
# Live statuses are kept so a match in progress stays visible.
# EXTRA_TIME / PENALTY_SHOOTOUT are live statuses of the v4 API (knock-out rounds).
KEPT_STATUSES = frozenset({"SCHEDULED", "TIMED", "IN_PLAY", "PAUSED", "EXTRA_TIME", "PENALTY_SHOOTOUT"})


@dataclass(frozen=True)
class Window:
    start: datetime   # midnight UTC of day J
    end: datetime     # J+2 03:00 UTC

    @classmethod
    def for_now(cls, now: datetime) -> "Window":
        now = now.astimezone(UTC)
        start = datetime(now.year, now.month, now.day, tzinfo=UTC)
        return cls(start, start + timedelta(days=2, hours=3))

    def contains(self, moment: datetime) -> bool:
        return self.start <= moment <= self.end


def iso_z(moment: datetime) -> str:
    return moment.astimezone(UTC).replace(microsecond=0).strftime("%Y-%m-%dT%H:%M:%SZ")


def _parse_utc(value: object) -> datetime | None:
    if not isinstance(value, str):
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed.astimezone(UTC) if parsed.tzinfo else None


def _team(team: dict) -> dict:
    name = team["name"].strip()
    short = (team.get("shortName") or "").strip() or name
    return {"name": name, "short": short, "aliases": team_aliases(team), "tokens": team_tokens(team)}


def select_matches(raw_matches: list, window: Window) -> list[dict]:
    """Filter (competition, status, window, teams known), de-duplicate by id, sort by (kickoff, id)."""
    selected: dict[int, dict] = {}
    skipped = 0
    for m in raw_matches:
        if not isinstance(m, dict):
            skipped += 1
            continue
        comp = (m.get("competition") or {}).get("code")
        kickoff = _parse_utc(m.get("utcDate"))
        home, away = m.get("homeTeam") or {}, m.get("awayTeam") or {}
        match_id = m.get("id")
        if (comp not in ALL_COMPETITIONS or m.get("status") not in KEPT_STATUSES or kickoff is None
                or not window.contains(kickoff) or not isinstance(match_id, int) or isinstance(match_id, bool)):
            skipped += 1
            continue
        # Knock-out ties whose teams are not yet known ("TBD"): nothing to match in the EPG
        if not isinstance(home.get("name"), str) or not home["name"].strip() \
                or not isinstance(away.get("name"), str) or not away["name"].strip():
            skipped += 1
            continue
        selected[match_id] = {   # same id twice -> last one wins (duplicates)
            "id": match_id,
            "competition": comp,
            "kickoff_utc": iso_z(kickoff),
            "home": _team(home),
            "away": _team(away),
        }
    log.info("matchs retenus : %d, ignores : %d", len(selected), skipped)
    return sorted(selected.values(), key=lambda x: (x["kickoff_utc"], x["id"]))


def build_document(raw_matches: list, now: datetime) -> dict:
    now = now.astimezone(UTC).replace(microsecond=0)
    window = Window.for_now(now)
    return {
        "schema": SCHEMA_VERSION,
        "generated_at": iso_z(now),
        "valid_until": iso_z(now + VALIDITY),
        "window": {"from": iso_z(window.start), "to": iso_z(window.end)},
        "competitions": dict(ALL_COMPETITIONS),
        "matching": {
            "competition_keywords": {k: list(v) for k, v in COMPETITION_KEYWORDS.items()},
            "excluded_title_words": list(EXCLUDED_TITLE_WORDS),
        },
        "matches": select_matches(raw_matches, window),
    }


def serialize(document: dict) -> bytes:
    """Compact, UTF-8, sorted keys: identical input -> identical bytes."""
    return json.dumps(document, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8")
