"""Generate the Ed25519 signing key and push secrets to GitHub without ever printing them."""
import base64
import subprocess
import sys
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

REPO = "davidjahan720/football-rama"
ROOT = Path(sys.argv[1])


def gh_secret(name: str, value: str) -> None:
    # Value goes through stdin only: never on the command line, never printed
    r = subprocess.run(["gh", "secret", "set", name, "--repo", REPO], input=value.encode(),
                       stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    if r.returncode != 0:
        sys.exit(f"echec gh secret set {name} (code {r.returncode})")


token = ""
for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
    if line.strip().startswith("FOOTBALL_DATA_TOKEN="):
        token = line.split("=", 1)[1].strip().strip('"').strip("'")
if not token:
    sys.exit("FOOTBALL_DATA_TOKEN absent du .env")

key = Ed25519PrivateKey.generate()
private_b64 = base64.b64encode(key.private_bytes_raw()).decode()
public_b64 = base64.b64encode(key.public_key().public_bytes_raw()).decode()

gh_secret("FIXTURES_SIGNING_KEY", private_b64)
gh_secret("FOOTBALL_DATA_TOKEN", token)
del private_b64, key, token

r = subprocess.run(["gh", "variable", "set", "FIXTURES_PUBLIC_KEY", "--repo", REPO, "--body", public_b64],
                   stdout=subprocess.DEVNULL)
if r.returncode != 0:
    sys.exit("echec gh variable set")
print("PUBLIC_KEY", public_b64)
