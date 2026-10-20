"""Team aliases / tokens and matching rules, taken from scripts/poc_epg.py (validated 2026-10-20, 18/18)."""
from __future__ import annotations

import json
from functools import lru_cache
from pathlib import Path

from .normalize import norm

ALIASES_FILE = Path(__file__).with_name("aliases.json")

COMPETITIONS = {"PL": "Premier League", "FL1": "Ligue 1", "CL": "Ligue des champions"}

COMPETITION_KEYWORDS = {
    "PL": ["premier league", "epl"],
    "FL1": ["ligue 1", "ligue1", "mcdonald"],
    "CL": ["champions league", "ligue des champions", "uefa champions", "ucl", "liga de campeones", "champions"],
}

# Programmes to ignore: youth, women, highlights, magazines (normalised)
EXCLUDED_TITLE_WORDS = ["youth league", "u19", "u21", "u23", "women", "womens", "feminin", "feminine", "femenino",
                        "frauen", "wsl", "highlights", "resume", "resumen", "replay", "rediffusion", "rediff",
                        "magazine", "best of", "golos", "goals", "talk", "preview", "avant match", "debrief"]

# Words removed from team names before comparison
STOP_WORDS = {"fc", "afc", "sc", "ac", "as", "cf", "club", "de", "du", "la", "le", "the", "and", "football",
              "olympique", "stade", "racing", "sporting", "association", "sad", "sv", "ssc", "us", "rc", "ogc", "losc",
              "sk", "fk", "pae", "bk", "nk", "cp"}

# Single words too ambiguous to identify a team (shared between clubs or too common)
GENERIC_WORDS = {"united", "city", "real", "saint", "germain", "athletic", "atletico", "borussia", "royal", "union",
                 "dynamo", "dinamo", "manchester", "madrid", "milano", "london", "lisboa", "glasgow", "sheffield",
                 "bristol", "hotspur", "wanderers", "albion", "rovers", "county", "town", "sport", "sports",
                 "calcio", "football", "futbol", "olympique", "sporting", "club", "young", "boys", "1907",
                 "paris"}  # "paris" alone would also match Paris FC (Ligue 1)


class AliasFileError(ValueError):
    pass


def _check_normalised(value: object, where: str) -> str:
    if not isinstance(value, str) or not value or norm(value) != value:
        raise AliasFileError(f"{where}: {value!r} n'est pas une chaine normalisee")
    return value


@lru_cache(maxsize=1)
def extra_aliases() -> dict[str, tuple[str, ...]]:
    """Load and validate aliases.json (keys and values must already be normalised)."""
    data = json.loads(ALIASES_FILE.read_text(encoding="utf-8"))
    raw = data.get("aliases") if isinstance(data, dict) else None
    if not isinstance(raw, dict):
        raise AliasFileError("aliases.json : objet 'aliases' manquant")
    result = {}
    for key, values in raw.items():
        _check_normalised(key, "aliases.json cle")
        if not isinstance(values, list):
            raise AliasFileError(f"aliases.json : {key!r} doit etre une liste")
        result[key] = tuple(_check_normalised(v, f"aliases.json[{key!r}]") for v in values)
    return result


def _core_name(full: str) -> str:
    core = " ".join(w for w in full.split() if w not in STOP_WORDS)
    # "como 1907" -> "como": the founding year almost never appears in EPG titles
    return " ".join(w for w in core.split() if not w.isdigit() or len(w) < 4) or core


def team_aliases(team: dict) -> list[str]:
    full = norm(team.get("name") or "")
    aliases = {full, norm(team.get("shortName") or "")}
    core = _core_name(full)
    aliases.add(core)
    explicit = set(extra_aliases().get(core, ())) | {core}
    aliases.update(explicit)
    # Too-short aliases = false positives (except explicit nicknames and the club short name, e.g. "psv")
    return sorted(a for a in aliases if a and (len(a) >= 4 or (a in explicit and len(a) >= 2)))


def team_tokens(team: dict) -> list[str]:
    """Distinctive words of the name (e.g. "slavia", "bratislava") for titles written in another language."""
    words = set()
    for name in (team.get("name") or "", team.get("shortName") or ""):
        words.update(w for w in norm(name).split()
                     if len(w) >= 5 and w not in STOP_WORDS and w not in GENERIC_WORDS and not w.isdigit())
    return sorted(words)
