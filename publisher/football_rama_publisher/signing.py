"""Ed25519 signature of the exact fixtures.json bytes (raw 32-byte keys, standard base64)."""
from __future__ import annotations

import base64
import binascii

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey


class SigningKeyError(ValueError):
    """Invalid key material. Messages never contain the key itself."""


def _decode_key(value: str, label: str) -> bytes:
    try:
        raw = base64.b64decode("".join((value or "").split()), validate=True)
    except (binascii.Error, ValueError):
        raise SigningKeyError(f"{label} : base64 invalide") from None
    if len(raw) != 32:
        raise SigningKeyError(f"{label} : 32 octets attendus")
    return raw


def load_private_key(b64: str) -> Ed25519PrivateKey:
    return Ed25519PrivateKey.from_private_bytes(_decode_key(b64, "FIXTURES_SIGNING_KEY"))


def load_public_key(b64: str) -> Ed25519PublicKey:
    return Ed25519PublicKey.from_public_bytes(_decode_key(b64, "cle publique"))


def public_key_b64(private_key: Ed25519PrivateKey) -> str:
    return base64.b64encode(private_key.public_key().public_bytes_raw()).decode("ascii")


def sign(data: bytes, private_key: Ed25519PrivateKey) -> bytes:
    """Return the .sig file content: base64 signature on a single line, no trailing newline
    (java.util.Base64 decoders reject whitespace)."""
    return base64.b64encode(private_key.sign(data))


def verify(data: bytes, sig_file: bytes, public_key: Ed25519PublicKey) -> bool:
    try:
        signature = base64.b64decode(sig_file.strip(), validate=True)
        public_key.verify(signature, data)
    except (binascii.Error, ValueError, InvalidSignature):
        return False
    return True
