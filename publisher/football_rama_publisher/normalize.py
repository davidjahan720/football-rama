"""Text normalisation shared with the Android app (docs/CONTRAT_FIXTURES.md, section "Normalisation")."""
from __future__ import annotations

import re
import unicodedata

# Step 1: letters without a Unicode decomposition (they would be dropped by step 2)
SPECIAL_CHARS = str.maketrans({"ø": "o", "Ø": "O", "æ": "ae", "Æ": "AE", "ß": "ss", "đ": "d", "Đ": "D",
                               "ł": "l", "Ł": "L", "ı": "i", "œ": "oe", "Œ": "OE"})

_NON_ALNUM = re.compile(r"[^a-z0-9]+")


def norm(text: str) -> str:
    """1. transliteration  2. NFKD + drop non-ASCII  3. lowercase  4. non [a-z0-9] runs -> space, trim."""
    text = unicodedata.normalize("NFKD", text.translate(SPECIAL_CHARS)).encode("ascii", "ignore").decode("ascii")
    return _NON_ALNUM.sub(" ", text.lower()).strip()
