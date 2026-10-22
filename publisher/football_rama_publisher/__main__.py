"""CLI: py -m football_rama_publisher publish --out dist/ [--verify-key <base64 public key>]

Other commands:
  publish-update --manifest app_release.json --out dist/ [--verify-key ...]  -> update.json + update.json.sig
  check-update --manifest app_release.json                                   -> validation only (no key)

Secrets are read from the environment only (FOOTBALL_DATA_TOKEN, FIXTURES_SIGNING_KEY), never from argv,
and are never logged.

Exit codes: 0 = published (or update skipped: manifest absent / placeholder), 1 = API / publication failure or
invalid manifest (nothing written), 2 = configuration error.
"""
from __future__ import annotations

import argparse
import logging
import os
import sys
from pathlib import Path

from . import app_release
from .client import FetchError
from .publish import PublishError, publish
from .signing import SigningKeyError, load_private_key

log = logging.getLogger("football_rama_publisher")

EXIT_OK, EXIT_FAILURE, EXIT_CONFIG = 0, 1, 2


def _parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(prog="football_rama_publisher", description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="command", required=True)
    pub = sub.add_parser("publish", help="recupere le calendrier et publie fixtures.json signe")
    pub.add_argument("--out", type=Path, required=True, help="dossier de sortie (ex. dist/)")
    pub.add_argument("--verify-key", metavar="BASE64_PUB",
                     help="cle publique Ed25519 (base64) pour reverifier la signature apres ecriture")
    upd = sub.add_parser("publish-update", help="publie update.json signe a partir de app_release.json")
    upd.add_argument("--manifest", type=Path, required=True, help="chemin de app_release.json")
    upd.add_argument("--out", type=Path, required=True, help="dossier de sortie (ex. dist/)")
    upd.add_argument("--verify-key", metavar="BASE64_PUB",
                     help="cle publique Ed25519 (base64) pour reverifier la signature apres ecriture")
    chk = sub.add_parser("check-update", help="valide app_release.json sans rien signer")
    chk.add_argument("--manifest", type=Path, required=True, help="chemin de app_release.json")
    return ap


def _publish_update(args) -> int:
    try:
        if args.command == "check-update":
            doc = app_release.load(args.manifest)
            log.info("app_release.json valide" if doc else "rien a publier (absent ou placeholder)")
            return EXIT_OK
        if app_release.load(args.manifest) is None:
            return EXIT_OK  # nothing to publish: no key needed, no failure
        key_b64 = os.environ.get("FIXTURES_SIGNING_KEY", "").strip()
        if not key_b64:
            log.error("variable d'environnement manquante : FIXTURES_SIGNING_KEY")
            return EXIT_CONFIG
        verify_key = (args.verify_key or "").strip() or None
        if args.verify_key is not None and verify_key is None:
            log.error("--verify-key fourni mais vide")
            return EXIT_CONFIG
        private_key = load_private_key(key_b64)
        del key_b64
        app_release.publish_update(args.manifest, args.out, private_key, verify_key_b64=verify_key)
    except SigningKeyError as e:
        log.error("cle invalide : %s", e)
        return EXIT_CONFIG
    except app_release.ManifestError as e:
        log.error("app_release.json refuse, update.json non publie : %s", e)
        return EXIT_FAILURE
    except OSError as e:
        log.error("echec d'ecriture : %s", type(e).__name__)
        return EXIT_FAILURE
    return EXIT_OK


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    if not logging.getLogger().handlers:
        logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s", stream=sys.stderr)
    if args.command in ("publish-update", "check-update"):
        return _publish_update(args)

    token = os.environ.get("FOOTBALL_DATA_TOKEN", "").strip()
    key_b64 = os.environ.get("FIXTURES_SIGNING_KEY", "").strip()
    missing = [n for n, v in (("FOOTBALL_DATA_TOKEN", token), ("FIXTURES_SIGNING_KEY", key_b64)) if not v]
    if missing:
        log.error("variable(s) d'environnement manquante(s) : %s", ", ".join(missing))
        return EXIT_CONFIG
    try:
        private_key = load_private_key(key_b64)
        del key_b64
        verify_key = (args.verify_key or "").strip() or None
        if args.verify_key is not None and verify_key is None:
            log.error("--verify-key fourni mais vide")
            return EXIT_CONFIG
        publish(args.out, token, private_key, verify_key_b64=verify_key)
    except SigningKeyError as e:
        log.error("cle invalide : %s", e)
        return EXIT_CONFIG
    except (FetchError, PublishError) as e:
        log.error("echec, rien n'a ete publie : %s", e)
        return EXIT_FAILURE
    except OSError as e:
        log.error("echec d'ecriture : %s", type(e).__name__)
        return EXIT_FAILURE
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main())
