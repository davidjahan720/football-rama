"""Orchestration: fetch -> build -> sign -> atomic write (-> optional verification)."""
from __future__ import annotations

import logging
import os
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Callable

from . import client, uefa
from .aliases import COMPETITIONS
from .build import Window, build_document, serialize
from .signing import load_public_key, sign, verify

log = logging.getLogger(__name__)

FIXTURES_NAME = "fixtures.json"
SIGNATURE_NAME = "fixtures.json.sig"


class PublishError(RuntimeError):
    pass


def _write_atomic(path: Path, data: bytes) -> None:
    tmp = path.with_name(path.name + ".tmp")
    try:
        with open(tmp, "wb") as fh:
            fh.write(data)
            fh.flush()
            os.fsync(fh.fileno())
        os.replace(tmp, path)
    finally:
        tmp.unlink(missing_ok=True)


def publish(out_dir: Path, token: str, private_key, *, verify_key_b64: str | None = None,
            now: datetime | None = None, fetch: Callable = client.fetch_matches,
            fetch_uefa: Callable | None = None) -> dict:
    """Publish fixtures.json + fixtures.json.sig into out_dir. Returns the document.

    Nothing is written if the football-data call, the build or the signature fails.
    The uefa.com feed (Europa League / Conference League) is optional: its failure only drops these matches.
    """
    now = (now or datetime.now(UTC)).astimezone(UTC)
    window = Window.for_now(now)
    # dateTo is inclusive (day granularity): J+2 covers the 00:00-03:00 UTC tail of the window
    date_from, date_to = window.start.date(), (window.start + timedelta(days=2)).date()
    raw = fetch(token, date_from, date_to, list(COMPETITIONS))
    try:
        raw = raw + (fetch_uefa or uefa.fetch_uefa_matches)(date_from, date_to)
    except client.FetchError as e:
        log.warning("source UEFA indisponible, publie sans Ligue Europa / Conference League : %s", e)

    document = build_document(raw, now)
    data = serialize(document)
    signature = sign(data, private_key)

    public_key = load_public_key(verify_key_b64) if verify_key_b64 else None
    if public_key is not None and not verify(data, signature, public_key):
        # Checked before writing: the private key does not match the expected public key
        raise PublishError("la signature ne correspond pas a --verify-key (mauvaise paire de cles ?)")

    out_dir.mkdir(parents=True, exist_ok=True)
    json_path, sig_path = out_dir / FIXTURES_NAME, out_dir / SIGNATURE_NAME
    try:
        _write_atomic(json_path, data)
        _write_atomic(sig_path, signature)
    except OSError:
        # Never leave a fixtures.json next to a signature that does not match it
        json_path.unlink(missing_ok=True)
        sig_path.unlink(missing_ok=True)
        raise

    if public_key is not None:
        # Re-read from disk: what will actually be deployed
        if not verify(json_path.read_bytes(), sig_path.read_bytes(), public_key):
            json_path.unlink(missing_ok=True)
            sig_path.unlink(missing_ok=True)
            raise PublishError("verification apres ecriture echouee, fichiers supprimes")
        log.info("signature reverifiee avec la cle publique fournie")

    log.info("publie : %s (%d octets, %d matchs, valide jusqu'a %s)",
             json_path, len(data), len(document["matches"]), document["valid_until"])
    return document
