"""Prototype Football_Rama : detecter les matchs PL / Ligue 1 / LDC dans l'EPG d'un acces Xtream.

Usage :
    py scripts/poc_epg.py              # utilise le cache EPG s'il a moins de 6 h
    py scripts/poc_epg.py --refresh    # force le re-telechargement
    py scripts/poc_epg.py --days 5     # fenetre de matchs (max 10, limite football-data)

Aucun identifiant n'est affiche ni ecrit dans les rapports.
"""
from __future__ import annotations

import argparse
import bisect
import gzip
import io
import json
import re
import sys
import time
import unicodedata
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT / "cache"
OUTPUT = ROOT / "output"
CACHE_MAX_AGE = 6 * 3600

COMPETITIONS = {"PL": "Premier League", "FL1": "Ligue 1", "CL": "Ligue des champions"}
COMPETITION_KEYWORDS = {
    "PL": ["premier league", "epl"],
    "FL1": ["ligue 1", "ligue1", "mcdonald"],
    "CL": ["champions league", "ligue des champions", "uefa champions", "ucl", "liga de campeones", "champions"],
}

# Mots retires des noms d'equipe avant comparaison
STOP_WORDS = {"fc", "afc", "sc", "ac", "as", "cf", "club", "de", "du", "la", "le", "the", "and", "football",
              "olympique", "stade", "racing", "sporting", "association", "sad", "sv", "ssc", "us", "rc", "ogc", "losc",
              "sk", "fk", "pae", "bk", "nk", "cp"}

# Mots seuls trop ambigus pour identifier une equipe (partages entre clubs ou trop courants)
GENERIC_WORDS = {"united", "city", "real", "saint", "germain", "athletic", "atletico", "borussia", "royal", "union",
                 "dynamo", "dinamo", "manchester", "madrid", "milano", "london", "lisboa", "glasgow", "sheffield",
                 "bristol", "hotspur", "wanderers", "albion", "rovers", "county", "town", "sport", "sports",
                 "calcio", "football", "futbol", "olympique", "sporting", "club", "young", "boys", "1907"}

# Lettres sans decomposition Unicode (sinon supprimees par la normalisation)
SPECIAL_CHARS = str.maketrans({"ø": "o", "Ø": "O", "æ": "ae", "Æ": "AE", "ß": "ss", "đ": "d", "Đ": "D",
                               "ł": "l", "Ł": "L", "ı": "i", "œ": "oe", "Œ": "OE"})

# Separateurs "Equipe A - Equipe B" dans les titres EPG
TEAM_SEPARATOR = re.compile(r"\s+(?:-|–|—|v|vs|x|/)\.?\s+", re.IGNORECASE)
# Prefixe "LIVE: UCL: " (dernier ": " suivi d'un espace, pour ne pas couper "20:50")
TITLE_PREFIX = re.compile(r"^.*:\s+")

# Programmes a ignorer : jeunes, feminines, resumes, magazines (normalises)
EXCLUDED_TITLE_WORDS = ["youth league", "u19", "u21", "u23", "women", "womens", "feminin", "feminine", "femenino",
                        "frauen", "wsl", "highlights", "resume", "resumen", "replay", "rediffusion", "rediff",
                        "magazine", "best of", "golos", "goals", "talk", "preview", "avant match", "debrief"]

# Surnoms courants absents de football-data (cle = nom normalise sans STOP_WORDS)
EXTRA_ALIASES = {
    "paris saint germain": ["psg", "paris sg", "paris"],
    "marseille": ["om", "marseille"],
    "lyonnais": ["ol", "lyon"],
    "lille osc": ["losc", "lille"],
    "manchester united": ["man utd", "man united", "manchester utd"],
    "manchester city": ["man city"],
    "tottenham hotspur": ["spurs", "tottenham"],
    "wolverhampton wanderers": ["wolves"],
    "brighton hove albion": ["brighton"],
    "nottingham forest": ["nottm forest", "forest"],
    "newcastle united": ["newcastle"],
    "west ham united": ["west ham"],
    "bayern munchen": ["bayern", "bayern munich"],
    "internazionale milano": ["inter", "inter milan", "internazionale"],
    "barcelona": ["barca", "barcelone"],
    "atletico madrid": ["atletico", "atl madrid"],
    "real madrid": ["real madrid"],
    "borussia dortmund": ["dortmund", "bvb"],
    "bayer 04 leverkusen": ["leverkusen"],
    "juventus": ["juve"],
    "milan": ["ac milan"],
    "napoli": ["naples"],
    "aek": ["aek athens", "aek athenes", "aek athen", "aek"],
    "psv": ["psv eindhoven", "psv"],
    "slavia praha": ["slavia prague", "slavia praga", "slavia praag", "slavia"],
}


# ---------------------------------------------------------------- utilitaires

def load_env() -> dict[str, str]:
    path = ROOT / ".env"
    if not path.exists():
        sys.exit("Fichier .env absent : copier .env.example en .env et le remplir.")
    env = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip().strip('"').strip("'")
    missing = [k for k in ("XTREAM_URL", "XTREAM_USER", "XTREAM_PASSWORD", "FOOTBALL_DATA_TOKEN") if not env.get(k)]
    if missing:
        sys.exit(f"Variables manquantes dans .env : {', '.join(missing)}")
    env["XTREAM_URL"] = env["XTREAM_URL"].rstrip("/")
    return env


def norm(text: str) -> str:
    text = unicodedata.normalize("NFKD", text.translate(SPECIAL_CHARS)).encode("ascii", "ignore").decode().lower()
    return re.sub(r"[^a-z0-9]+", " ", text).strip()


def http_get(url: str, headers: dict[str, str], timeout: int = 120) -> bytes:
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = resp.read()
    except urllib.error.HTTPError as e:
        # Ne pas afficher l'URL : elle contient les identifiants Xtream
        raise RuntimeError(f"HTTP {e.code} sur {urllib.parse.urlsplit(url).path}") from None
    except urllib.error.URLError as e:
        raise RuntimeError(f"Connexion impossible : {e.reason}") from None
    if data[:2] == b"\x1f\x8b":
        data = gzip.decompress(data)
    return data


def cached(name: str, fetch, refresh: bool) -> bytes:
    CACHE.mkdir(exist_ok=True)
    path = CACHE / name
    if not refresh and path.exists() and time.time() - path.stat().st_mtime < CACHE_MAX_AGE:
        return path.read_bytes()
    data = fetch()
    path.write_bytes(data)
    return data


def parse_xmltv_time(value: str) -> datetime | None:
    m = re.match(r"(\d{14})\s*([+-]\d{4})?", value or "")
    if not m:
        return None
    dt = datetime.strptime(m.group(1), "%Y%m%d%H%M%S")
    tz = m.group(2) or "+0000"
    offset = timedelta(hours=int(tz[1:3]), minutes=int(tz[3:5])) * (1 if tz[0] == "+" else -1)
    return dt.replace(tzinfo=timezone(offset)).astimezone(timezone.utc)


# ---------------------------------------------------------------- sources

def fetch_xtream(env, refresh):
    base, ua = env["XTREAM_URL"], env.get("XTREAM_USER_AGENT") or "okhttp/4.12.0"
    creds = urllib.parse.urlencode({"username": env["XTREAM_USER"], "password": env["XTREAM_PASSWORD"]})
    headers = {"User-Agent": ua}

    def api(action):
        return lambda: http_get(f"{base}/player_api.php?{creds}&action={action}", headers)

    categories = json.loads(cached("live_categories.json", api("get_live_categories"), refresh))
    streams = json.loads(cached("live_streams.json", api("get_live_streams"), refresh))
    epg = cached("epg.xml", lambda: http_get(f"{base}/xmltv.php?{creds}", headers, timeout=600), refresh)
    return categories, streams, epg


def fetch_fixtures(env, days, refresh):
    today = datetime.now(timezone.utc).date()
    params = urllib.parse.urlencode({
        "competitions": ",".join(COMPETITIONS),
        "dateFrom": today.isoformat(),
        "dateTo": (today + timedelta(days=days)).isoformat(),
    })
    data = cached(f"fixtures_{today}_{days}.json",
                  lambda: http_get(f"https://api.football-data.org/v4/matches?{params}",
                                   {"X-Auth-Token": env["FOOTBALL_DATA_TOKEN"]}),
                  refresh)
    return json.loads(data).get("matches", [])


# ---------------------------------------------------------------- matching

@dataclass
class Programme:
    channel: str
    start: datetime
    stop: datetime | None
    title: str
    text: str  # titre + description normalises


@dataclass
class Fixture:
    id: int
    competition: str
    kickoff: datetime
    home: str
    away: str
    home_aliases: list[str]
    away_aliases: list[str]
    home_tokens: list[str]
    away_tokens: list[str]
    hits: list[dict] = field(default_factory=list)


def team_aliases(team: dict) -> list[str]:
    full = norm(team.get("name") or "")
    aliases = {full, norm(team.get("shortName") or "")}
    core = " ".join(w for w in full.split() if w not in STOP_WORDS)
    # "como 1907" -> "como" : l'annee de fondation n'apparait presque jamais dans l'EPG
    core = " ".join(w for w in core.split() if not w.isdigit() or len(w) < 4) or core
    aliases.add(core)
    explicit = set(EXTRA_ALIASES.get(core, [])) | {core}
    aliases.update(explicit)
    # Alias trop courts = faux positifs (sauf surnoms explicites et nom court du club, ex. "psv")
    return sorted(a for a in aliases if a and (len(a) >= 4 or (a in explicit and len(a) >= 2)))


def team_tokens(team: dict) -> list[str]:
    """Mots distinctifs du nom (ex. "slavia", "bratislava") pour les titres ecrits dans une autre langue."""
    words = set()
    for name in (team.get("name") or "", team.get("shortName") or ""):
        words.update(w for w in norm(name).split()
                     if len(w) >= 5 and w not in STOP_WORDS and w not in GENERIC_WORDS and not w.isdigit())
    return sorted(words)


def team_hit(text: str, aliases: list[str], tokens: list[str]) -> bool:
    return any(contains(text, a) for a in aliases) or any(contains(text, t) for t in tokens)


def foreign_team_in_title(title: str, fx: "Fixture") -> bool:
    """Vrai si le titre est de la forme "A - B" et qu'un des deux cotes n'est aucune des equipes du match
    (ex. "Real Betis x Porto" pour Porto - PSV)."""
    sides = TEAM_SEPARATOR.split(TITLE_PREFIX.sub("", title, count=1))
    if len(sides) < 2:
        return False
    # "A x B - Competition" : seuls les deux premiers segments sont des equipes
    for side in map(norm, sides[:2]):
        if not (team_hit(side, fx.home_aliases, fx.home_tokens) or team_hit(side, fx.away_aliases, fx.away_tokens)):
            return True
    return False


def contains(text: str, alias: str) -> bool:
    return re.search(rf"(?<![a-z0-9]){re.escape(alias)}(?![a-z0-9])", text) is not None


def parse_epg(epg: bytes, window_start: datetime, window_end: datetime) -> tuple[dict, list[Programme]]:
    channels, programmes = {}, []
    for _, el in ET.iterparse(io.BytesIO(epg), events=("end",)):
        if el.tag == "channel":
            channels[(el.get("id") or "").lower()] = (el.findtext("display-name") or "").strip()
            el.clear()
        elif el.tag == "programme":
            start = parse_xmltv_time(el.get("start"))
            if start and window_start <= start <= window_end:
                title = (el.findtext("title") or "").strip()
                desc = (el.findtext("desc") or "").strip()
                programmes.append(Programme((el.get("channel") or "").lower(), start, parse_xmltv_time(el.get("stop")),
                                            title, norm(f"{title} {desc}")))
            el.clear()
    return channels, programmes


def match(fixtures: list[Fixture], programmes: list[Programme]) -> None:
    ordered = sorted(programmes, key=lambda p: p.start)
    starts = [p.start for p in ordered]
    for fx in fixtures:
        lo, hi = fx.kickoff - timedelta(minutes=60), fx.kickoff + timedelta(minutes=30)
        # Un programme qui couvre le coup d'envoi commence au plus 6 h avant
        first = bisect.bisect_left(starts, fx.kickoff - timedelta(hours=6))
        for p in ordered[first:]:
            if p.start > hi:
                break
            covers = p.start <= fx.kickoff and (p.stop or p.start) > fx.kickoff
            if not (lo <= p.start or covers):
                continue
            title = norm(p.title)
            if any(contains(title, w) for w in EXCLUDED_TITLE_WORDS):
                continue
            home = team_hit(p.text, fx.home_aliases, fx.home_tokens)
            away = team_hit(p.text, fx.away_aliases, fx.away_tokens)
            comp = any(contains(p.text, k) for k in COMPETITION_KEYWORDS[fx.competition])
            if (home or away) and foreign_team_in_title(p.title, fx):
                continue
            # "fort" : les deux equipes dans le titre ; dans la description seulement = "moyen"
            if team_hit(title, fx.home_aliases, fx.home_tokens) and team_hit(title, fx.away_aliases, fx.away_tokens):
                score = "fort"
            elif home and away:
                score = "moyen"
            elif (home or away) and comp:
                score = "moyen"
            else:
                continue
            fx.hits.append({"channel_epg_id": p.channel, "title": p.title, "start": p.start.isoformat(), "score": score})


QUALITY_TAGS = re.compile(r"\b(?:FHD|UHD|HD|SD|LQ|HQ|4K|8K|HEVC|H265|50FPS|60FPS|RAW|BACKUP)\b", re.IGNORECASE)


def channel_group(name: str) -> str:
    """'|FR| CANAL+ LIVE 1 FHD' et '|FR| CANAL+ LIVE 1 HD' -> '|FR| CANAL+ LIVE 1'."""
    return re.sub(r"\s{2,}", " ", QUALITY_TAGS.sub("", name)).strip()


# ---------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--refresh", action="store_true")
    ap.add_argument("--days", type=int, default=7)
    args = ap.parse_args()
    days = max(1, min(args.days, 10))
    env = load_env()

    print("Telechargement / lecture du cache...")
    categories, streams, epg = fetch_xtream(env, args.refresh)
    raw_fixtures = fetch_fixtures(env, days, args.refresh)

    now = datetime.now(timezone.utc)
    channels, programmes = parse_epg(epg, now - timedelta(hours=3), now + timedelta(days=days + 1))

    cat_name = {str(c.get("category_id")): c.get("category_name", "") for c in categories}
    # Une meme chaine EPG peut correspondre a plusieurs flux (HD, FHD, backup...)
    streams_by_epg = defaultdict(list)
    for s in streams:
        if s.get("epg_channel_id"):
            streams_by_epg[s["epg_channel_id"].lower()].append(
                {"name": s.get("name", ""), "category": cat_name.get(str(s.get("category_id")), "")})

    fixtures = []
    for m in raw_fixtures:
        comp = m["competition"]["code"]
        fixtures.append(Fixture(m["id"], comp, datetime.fromisoformat(m["utcDate"].replace("Z", "+00:00")),
                                m["homeTeam"].get("name") or "?", m["awayTeam"].get("name") or "?",
                                team_aliases(m["homeTeam"]), team_aliases(m["awayTeam"]),
                                team_tokens(m["homeTeam"]), team_tokens(m["awayTeam"])))
    match(fixtures, programmes)

    # ---- indicateurs de qualite EPG
    epg_ids_with_prog = {p.channel for p in programmes}
    with_epg_id = sum(1 for s in streams if s.get("epg_channel_id"))
    with_prog = sum(1 for s in streams if (s.get("epg_channel_id") or "").lower() in epg_ids_with_prog)
    horizon = max((p.start for p in programmes), default=now)
    found = [f for f in fixtures if f.hits]
    in_horizon = [f for f in fixtures if f.kickoff <= horizon]
    strong = [f for f in fixtures if any(h["score"] == "fort" for h in f.hits)]

    stats = {
        "chaines_live": len(streams),
        "chaines_avec_epg_id": with_epg_id,
        "chaines_avec_programmes": with_prog,
        "programmes_dans_fenetre": len(programmes),
        "horizon_epg_jours": round((horizon - now).total_seconds() / 86400, 1),
        "matchs_calendrier": len(fixtures),
        "matchs_detectes": len(found),
        "taux_detection": f"{100 * len(found) / len(fixtures):.0f}%" if fixtures else "n/a",
        "matchs_dans_horizon_epg": len(in_horizon),
        "detectes_dans_horizon": f"{len([f for f in in_horizon if f.hits])}/{len(in_horizon)}",
        "dont_score_fort": f"{len(strong)}/{len(found)}",
    }

    report = {"genere_le": now.isoformat(), "stats": stats, "matchs": []}
    for fx in sorted(fixtures, key=lambda f: f.kickoff):
        chans = {}
        for h in sorted(fx.hits, key=lambda h: h["score"] != "fort"):
            for s in streams_by_epg.get(h["channel_epg_id"], []) or [{"name": channels.get(h["channel_epg_id"], h["channel_epg_id"]), "category": "?"}]:
                key = channel_group(s["name"])
                g = chans.setdefault(key, {"chaine": key, "category": s["category"], "score": h["score"],
                                           "titre_epg": h["title"], "variantes": []})
                if s["name"] not in g["variantes"]:
                    g["variantes"].append(s["name"])
        report["matchs"].append({
            "competition": COMPETITIONS[fx.competition],
            "coup_envoi_utc": fx.kickoff.isoformat(),
            "match": f"{fx.home} - {fx.away}",
            "chaines": sorted(chans.values(), key=lambda c: (c["score"] != "fort", c["chaine"])),
        })

    OUTPUT.mkdir(exist_ok=True)
    out = OUTPUT / f"rapport_{now:%Y%m%d_%H%M}.json"
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    print("\n=== Qualite EPG ===")
    for k, v in stats.items():
        print(f"  {k:28} {v}")
    print("\n=== Matchs ===")
    for m in report["matchs"]:
        local = datetime.fromisoformat(m["coup_envoi_utc"]).astimezone()
        print(f"\n{local:%a %d/%m %H:%M}  [{m['competition']}]  {m['match']}")
        if not m["chaines"]:
            print("    (aucune chaine trouvee dans l'EPG)")
        for c in m["chaines"][:12]:
            variants = f" x{len(c['variantes'])}" if len(c["variantes"]) > 1 else ""
            print(f"    - {c['chaine']}{variants}  [{c['score']}]  << {c['titre_epg']}")
        if len(m["chaines"]) > 12:
            print(f"    ... +{len(m['chaines']) - 12} autres")
    print(f"\nRapport complet : {out}")


if __name__ == "__main__":
    # Console Windows : forcer l'UTF-8 pour les accents
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    try:
        main()
    except RuntimeError as e:
        sys.exit(f"Erreur : {e}")
