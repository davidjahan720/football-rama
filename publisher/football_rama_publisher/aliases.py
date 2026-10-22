"""Team aliases / tokens and matching rules, taken from scripts/poc_epg.py (validated 2026-10-20, 18/18)."""
from __future__ import annotations

import json
from functools import lru_cache
from pathlib import Path

from .normalize import norm

ALIASES_FILE = Path(__file__).with_name("aliases.json")

# football-data.org codes, all included in the free plan: ONE API call per run (competitions=<all codes>).
# Europa League / Conference League are paid plans: not included.
COMPETITIONS = {
    "PL": "Premier League",
    "FL1": "Ligue 1",
    "CL": "Ligue des champions",
    "BL1": "Bundesliga",
    "SA": "Serie A",
    "PD": "LaLiga",
    "DED": "Eredivisie",
    "PPL": "Liga Portugal",
    "ELC": "Championship",
}

# Context words: they only count together with ONE team found in a programme of the kickoff window
# ("moyen" score). Ambiguous words alone are avoided (docs/CONTRAT_FIXTURES.md, "Mots-cles de competition").
COMPETITION_KEYWORDS = {
    "PL": ["premier league", "epl"],
    "FL1": ["ligue 1", "ligue1", "mcdonald"],
    "CL": ["champions league", "ligue des champions", "uefa champions", "ucl", "liga de campeones", "champions"],
    "BL1": ["bundesliga", "1 bundesliga"],
    # "serie a" alone also names the Brazilian / Ecuadorian leagues: "calcio" and "lega serie a" add context
    "SA": ["serie a", "lega serie a", "serie a tim", "serie a enilive", "calcio"],
    "PD": ["laliga", "la liga", "liga espanola", "liga ea sports", "laliga ea sports"],
    "DED": ["eredivisie"],
    "PPL": ["liga portugal", "primeira liga", "liga betclic", "liga portugal betclic"],
    # No bare "championship": RFU / rugby league / Scottish Championship, golf, darts... would combine with
    # English city tokens ("coventry", "norwich"). "efl" only exists in English football.
    "ELC": ["efl championship", "sky bet championship", "skybet championship", "efl"],
}

# Programmes to ignore: youth, women, highlights, magazines, second divisions sharing a keyword (normalised)
EXCLUDED_TITLE_WORDS = ["youth league", "u19", "u21", "u23", "women", "womens", "feminin", "feminine", "femenino",
                        "frauen", "wsl", "highlights", "resume", "resumen", "replay", "rediffusion", "rediff",
                        "magazine", "best of", "golos", "goals", "talk", "preview", "avant match", "debrief",
                        "vrouwen", "femminile", "feminino",
                        # "laliga" is in "LaLiga Hypermotion", "bundesliga" in "2. Bundesliga"...
                        "2 bundesliga", "serie b", "laliga hypermotion", "laliga 2", "la liga 2", "segunda division",
                        "liga portugal 2", "eerste divisie", "keuken kampioen", "league one", "league two",
                        "ligue 2"]

# Words removed from team names before comparison
STOP_WORDS = {"fc", "afc", "sc", "ac", "as", "cf", "club", "de", "du", "la", "le", "the", "and", "football",
              "olympique", "stade", "racing", "sporting", "association", "sad", "sv", "ssc", "us", "rc", "ogc", "losc",
              "sk", "fk", "pae", "bk", "nk", "cp"}

# Single words too ambiguous to identify a team (shared between clubs, cities with several clubs, common words).
# Never tokens; a team whose core name is one of them only gets it as alias if aliases.json lists it explicitly.
GENERIC_WORDS = {"united", "city", "real", "saint", "germain", "athletic", "atletico", "borussia", "royal", "union",
                 "dynamo", "dinamo", "manchester", "madrid", "milano", "london", "lisboa", "glasgow", "sheffield",
                 "bristol", "hotspur", "wanderers", "albion", "rovers", "county", "town", "sport", "sports",
                 "calcio", "football", "futbol", "olympique", "sporting", "club", "young", "boys", "1907",
                 "paris",       # "paris" alone would also match Paris FC (Ligue 1)
                 # added with BL1 / SA / PD / DED / PPL / ELC
                 "barcelona",   # Espanyol de Barcelona
                 "munchen",     # TSV 1860 Munchen
                 "berlin",      # Union Berlin / Hertha BSC
                 "eintracht",   # Frankfurt / Braunschweig
                 "fortuna",     # Sittard / Dusseldorf / Koln
                 "rotterdam",   # Feyenoord / Sparta / Excelsior
                 "sparta", "excelsior", "eagles",
                 "portugal",    # Sporting Clube de Portugal: also the national team and the league name
                 "clube", "vitoria", "nacional", "deportivo", "sociedad", "balompie", "sportiva", "unione",
                 "wednesday", "north", "derby", "rangers", "queens"}


class AliasFileError(ValueError):
    pass


def _check_normalised(value: object, where: str) -> str:
    if not isinstance(value, str) or not value or norm(value) != value:
        raise AliasFileError(f"{where}: {value!r} n'est pas une chaine normalisee")
    return value


def _no_duplicate_keys(pairs: list) -> dict:
    """A key written twice would silently replace the first list (easy mistake with ~60 clubs)."""
    result = {}
    for key, value in pairs:
        if key in result:
            raise AliasFileError(f"aliases.json : cle en double {key!r}")
        result[key] = value
    return result


@lru_cache(maxsize=1)
def extra_aliases() -> dict[str, tuple[str, ...]]:
    """Load and validate aliases.json (keys and values must already be normalised)."""
    data = json.loads(ALIASES_FILE.read_text(encoding="utf-8"), object_pairs_hook=_no_duplicate_keys)
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
    listed = set(extra_aliases().get(core, ()))
    explicit = listed | {core}
    aliases.update(explicit)
    # Too-short aliases = false positives (except explicit nicknames and the club short name, e.g. "psv").
    # A generic word alone ("Athletic Club" -> "athletic", also Charlton / Wigan Athletic) is dropped,
    # unless aliases.json lists it on purpose.
    return sorted(a for a in aliases if a and (len(a) >= 4 or (a in explicit and len(a) >= 2))
                  and (a not in GENERIC_WORDS or a in listed))


def team_tokens(team: dict) -> list[str]:
    """Distinctive words of the name (e.g. "slavia", "bratislava") for titles written in another language."""
    words = set()
    for name in (team.get("name") or "", team.get("shortName") or ""):
        words.update(w for w in norm(name).split()
                     if len(w) >= 5 and w not in STOP_WORDS and w not in GENERIC_WORDS and not w.isdigit())
    return sorted(words)
