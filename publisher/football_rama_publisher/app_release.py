"""App update manifest: publisher/app_release.json -> update.json + update.json.sig (Ed25519).

Contract: docs/CONTRAT_FIXTURES.md, section "update.json". Same signing key and same .sig format as
fixtures.json. The Android app re-checks every rule below; this module refuses to sign anything else.
"""
from __future__ import annotations

import json
import logging
import re
from pathlib import Path

from .publish import _write_atomic
from .signing import load_public_key, sign, verify

log = logging.getLogger(__name__)

MANIFEST_NAME = "update.json"
SIGNATURE_NAME = "update.json.sig"

SCHEMA = 1
APK_URL_PREFIX = "https://github.com/davidjahan720/football-rama/releases/download/"
MAX_APK_BYTES = 100 * 1024 * 1024
MAX_MANIFEST_BYTES = 64 * 1024
MAX_NOTES = 500
MAX_VERSION_CODE = 2**31 - 1

_SEGMENT = re.compile(r"[A-Za-z0-9._-]{1,100}")
_SHA256 = re.compile(r"[0-9a-f]{64}")
_VERSION_NAME = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+")
_FIELDS = ("schema", "version_code", "version_name", "apk_url", "sha256", "size", "min_sdk", "notes")


class ManifestError(ValueError):
    """app_release.json violates the contract: nothing is published."""


def _reject_duplicates(pairs):
    out = {}
    for key, value in pairs:
        if key in out:
            raise ManifestError(f"cle en double : {key}")
        out[key] = value
    return out


def _is_int(value) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def is_allowed_apk_url(url: str) -> bool:
    """Same strict rule as UpdatePolicy.isAllowedApkUrl (Android): <prefix><tag>/<file>.apk, safe characters only."""
    if not isinstance(url, str) or not url.startswith(APK_URL_PREFIX):
        return False
    parts = url[len(APK_URL_PREFIX):].split("/")
    if len(parts) != 2:
        return False
    tag, file = parts
    if not (_SEGMENT.fullmatch(tag) and _SEGMENT.fullmatch(file)):
        return False
    return set(tag) != {"."} and file.endswith(".apk") and len(file) > len(".apk")


def validate(doc) -> dict:
    """Strict validation. Returns a normalized dict (known fields only, fixed order)."""
    if not isinstance(doc, dict):
        raise ManifestError("objet JSON attendu")
    unknown = sorted(set(doc) - set(_FIELDS))
    if unknown:
        raise ManifestError(f"champ(s) inconnu(s) : {', '.join(unknown)}")
    missing = [f for f in _FIELDS if f != "notes" and f not in doc]
    if missing:
        raise ManifestError(f"champ(s) manquant(s) : {', '.join(missing)}")
    if doc["schema"] != SCHEMA or not _is_int(doc["schema"]):
        raise ManifestError("schema doit valoir 1")
    vc = doc["version_code"]
    if not _is_int(vc) or not 0 < vc <= MAX_VERSION_CODE:
        raise ManifestError("version_code doit etre un entier > 0")
    vn = doc["version_name"]
    if not isinstance(vn, str) or not _VERSION_NAME.fullmatch(vn):
        raise ManifestError("version_name doit etre de la forme x.y.z")
    url = doc["apk_url"]
    if not is_allowed_apk_url(url):
        raise ManifestError(f"apk_url doit commencer par {APK_URL_PREFIX} et viser un fichier .apk")
    tag, file = url[len(APK_URL_PREFIX):].split("/")
    if tag != f"v{vn}" or file != f"MatchsEtChaines-{vn}.apk":
        raise ManifestError(f"apk_url incoherente avec version_name (attendu v{vn}/MatchsEtChaines-{vn}.apk)")
    sha = doc["sha256"]
    if not isinstance(sha, str) or not _SHA256.fullmatch(sha):
        raise ManifestError("sha256 doit contenir 64 caracteres hexadecimaux minuscules")
    size = doc["size"]
    if not _is_int(size) or not 0 < size <= MAX_APK_BYTES:
        raise ManifestError(f"size doit etre un entier entre 1 et {MAX_APK_BYTES}")
    min_sdk = doc["min_sdk"]
    if not _is_int(min_sdk) or not 1 <= min_sdk <= 1000:
        raise ManifestError("min_sdk invalide")
    notes = doc.get("notes", "")
    if not isinstance(notes, str) or len(notes) > MAX_NOTES:
        raise ManifestError(f"notes : texte de {MAX_NOTES} caracteres au maximum")
    return {"schema": SCHEMA, "version_code": vc, "version_name": vn, "apk_url": url, "sha256": sha,
            "size": size, "min_sdk": min_sdk, "notes": notes}


def load(path: Path) -> dict | None:
    """Read and validate app_release.json. None = nothing to publish (file absent or placeholder sha256 "")."""
    if not path.is_file():
        log.info("%s absent : update.json non publie", path.name)
        return None
    raw = path.read_bytes()
    if len(raw) > MAX_MANIFEST_BYTES:
        raise ManifestError("fichier trop volumineux")
    try:
        doc = json.loads(raw.decode("utf-8"), object_pairs_hook=_reject_duplicates)
    except (UnicodeDecodeError, json.JSONDecodeError) as e:
        raise ManifestError(f"JSON illisible ({type(e).__name__})") from None
    if isinstance(doc, dict) and doc.get("sha256") == "":
        log.info("%s contient un placeholder (sha256 vide) : update.json non publie", path.name)
        return None
    return validate(doc)


def serialize(doc: dict) -> bytes:
    data = json.dumps(doc, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if len(data) > MAX_MANIFEST_BYTES:
        raise ManifestError("update.json trop volumineux")
    return data


def publish_update(manifest_path: Path, out_dir: Path, private_key, *, verify_key_b64: str | None = None) -> dict | None:
    """Write update.json + update.json.sig into out_dir. Returns the document, or None when skipped.

    Nothing is written if validation or signature fails; a stale update.json is never left behind.
    """
    doc = load(manifest_path)
    json_path, sig_path = out_dir / MANIFEST_NAME, out_dir / SIGNATURE_NAME
    if doc is None:
        return None
    data = serialize(doc)
    signature = sign(data, private_key)
    public_key = load_public_key(verify_key_b64) if verify_key_b64 else None
    if public_key is not None and not verify(data, signature, public_key):
        raise ManifestError("la signature ne correspond pas a --verify-key (mauvaise paire de cles ?)")

    out_dir.mkdir(parents=True, exist_ok=True)
    try:
        _write_atomic(json_path, data)
        _write_atomic(sig_path, signature)
        if public_key is not None and not verify(json_path.read_bytes(), sig_path.read_bytes(), public_key):
            raise ManifestError("verification apres ecriture echouee")
    except BaseException:
        json_path.unlink(missing_ok=True)
        sig_path.unlink(missing_ok=True)
        raise
    log.info("publie : %s (version %s, code %d)", json_path, doc["version_name"], doc["version_code"])
    return doc
