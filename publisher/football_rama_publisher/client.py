"""Minimal football-data.org v4 client (stdlib only).

Any failure raises FetchError: the caller must then publish nothing.
The API token is only sent in the X-Auth-Token header and never appears in messages.
"""
from __future__ import annotations

import json
import logging
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import date
from typing import Callable

log = logging.getLogger(__name__)

API_URL = "https://api.football-data.org/v4/matches"
TIMEOUT_S = 30
MAX_RETRIES = 2            # retries after a 429 (3 attempts in total)
DEFAULT_WAIT_S = 60        # free plan: 10 requests / minute
MAX_WAIT_S = 120
MAX_BODY_BYTES = 5 * 1024 * 1024
USER_AGENT = "football-rama-publisher/1.0"



class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """Refuse every redirect: urllib would otherwise forward ALL request headers (X-Auth-Token included)
    to the Location host, even another domain or plain http (security audit)."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: D401
        raise urllib.error.HTTPError(req.full_url, code, "redirect refused", headers, fp)


# Indirection so tests can replace the network layer
_urlopen = urllib.request.build_opener(_NoRedirect).open


class FetchError(RuntimeError):
    """The schedule could not be retrieved (network, HTTP status, invalid payload)."""


def _wait_seconds(headers) -> float:
    for name in ("Retry-After", "X-RequestCounter-Reset"):
        value = headers.get(name) if headers is not None else None
        if value is None:
            continue
        try:
            return float(min(max(int(str(value).strip()), 1), MAX_WAIT_S))
        except ValueError:
            continue  # HTTP-date or garbage: try the next header
    return float(DEFAULT_WAIT_S)


def fetch_matches(token: str, date_from: date, date_to: date, competitions: list[str], *,
                  opener: Callable | None = None, sleep: Callable[[float], None] | None = None) -> list[dict]:
    """GET /v4/matches?competitions=..&dateFrom=..&dateTo=.. and return the 'matches' list."""
    if not token:
        raise FetchError("token football-data absent")
    opener = opener or _urlopen
    sleep = sleep or time.sleep
    query = urllib.parse.urlencode({
        "competitions": ",".join(competitions),
        "dateFrom": date_from.isoformat(),
        "dateTo": date_to.isoformat(),
    })
    url = f"{API_URL}?{query}"
    headers = {"X-Auth-Token": token, "Accept": "application/json", "User-Agent": USER_AGENT}

    for attempt in range(MAX_RETRIES + 1):
        req = urllib.request.Request(url, headers=headers, method="GET")
        try:
            with opener(req, timeout=TIMEOUT_S) as resp:
                body = resp.read(MAX_BODY_BYTES + 1)
        except urllib.error.HTTPError as e:
            if e.code == 429 and attempt < MAX_RETRIES:
                wait = _wait_seconds(e.headers)
                log.warning("football-data: HTTP 429, nouvel essai dans %.0f s (%d/%d)", wait, attempt + 1, MAX_RETRIES)
                sleep(wait)
                continue
            raise FetchError(f"football-data: HTTP {e.code}") from None
        except urllib.error.URLError as e:
            raise FetchError(f"football-data: connexion impossible ({type(e.reason).__name__})") from None
        except (TimeoutError, OSError) as e:
            raise FetchError(f"football-data: erreur reseau ({type(e).__name__})") from None

        if len(body) > MAX_BODY_BYTES:
            raise FetchError("football-data: reponse trop volumineuse")
        try:
            payload = json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            raise FetchError("football-data: reponse JSON invalide") from None
        matches = payload.get("matches") if isinstance(payload, dict) else None
        if not isinstance(matches, list):
            raise FetchError("football-data: champ 'matches' absent ou invalide")
        return matches

    raise FetchError("football-data: HTTP 429 persistant")  # pragma: no cover (loop always returns/raises)
