"""Generate an Ed25519 key pair for signing fixtures.json.

Usage : py keygen.py
Prints both keys (raw 32 bytes, standard base64) on the console. Writes NO file.
"""
from __future__ import annotations

import base64

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey


def main() -> None:
    private_key = Ed25519PrivateKey.generate()
    private_b64 = base64.b64encode(private_key.private_bytes_raw()).decode("ascii")
    public_b64 = base64.b64encode(private_key.public_key().public_bytes_raw()).decode("ascii")
    print("=" * 72)
    print("ATTENTION : la cle PRIVEE ci-dessous permet de signer de faux calendriers.")
    print("- Copiez-la UNIQUEMENT dans le secret GitHub FIXTURES_SIGNING_KEY.")
    print("- Ne la collez nulle part ailleurs (fichier, chat, ticket, commit).")
    print("- Effacez ensuite l'historique / le defilement de ce terminal.")
    print("=" * 72)
    print(f"FIXTURES_SIGNING_KEY (privee) : {private_b64}")
    print(f"FIXTURES_PUBLIC_KEY (publique) : {public_b64}")
    print("La cle publique va dans la variable de repo FIXTURES_PUBLIC_KEY et dans le build Android.")


if __name__ == "__main__":
    main()
