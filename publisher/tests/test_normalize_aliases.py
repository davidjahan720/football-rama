import pytest

from football_rama_publisher.aliases import (COMPETITION_KEYWORDS, EXCLUDED_TITLE_WORDS, GENERIC_WORDS, extra_aliases,
                                             team_aliases, team_tokens)
from football_rama_publisher.normalize import norm


@pytest.mark.parametrize("raw, expected", [
    ("Fenerbahçe SK", "fenerbahce sk"),
    ("FC Bayern München", "fc bayern munchen"),
    ("Club Atlético de Madrid", "club atletico de madrid"),
    ("FK Bodø/Glimt", "fk bodo glimt"),
    ("ØSTER", "oster"),
    ("Großaspach", "grossaspach"),
    ("Łódź", "lodz"),
    ("Kasımpaşa", "kasimpasa"),
    ("Œuvre Æsir", "oeuvre aesir"),
    ("ŠK Slovan Bratislava", "sk slovan bratislava"),
    ("Brighton & Hove Albion FC", "brighton hove albion fc"),
    ("  Paris Saint-Germain FC!!  ", "paris saint germain fc"),
    ("LIVE: UCL | PSG v. Barça (20:45)", "live ucl psg v barca 20 45"),
    ("ｆｕｌｌｗｉｄｔｈ １", "fullwidth 1"),
    ("", ""),
    ("日本", ""),
])
def test_norm(raw, expected):
    assert norm(raw) == expected


def test_norm_is_idempotent():
    for s in ("Fenerbahçe SK", "FK Bodø/Glimt", "Großaspach"):
        assert norm(norm(s)) == norm(s)


def test_psg_aliases():
    team = {"name": "Paris Saint-Germain FC", "shortName": "PSG"}
    aliases = team_aliases(team)
    for a in ("paris saint germain", "paris saint germain fc", "psg", "paris sg"):
        assert a in aliases
    assert team_tokens(team) == []  # "saint", "germain", "paris" are generic (Paris FC)


def test_como_1907_founding_year_removed():
    team = {"name": "Como 1907", "shortName": "Como"}
    assert "como" in team_aliases(team)
    assert team_tokens(team) == []          # "como" < 5 letters, "1907" is a number


def test_slavia_aliases_and_tokens():
    team = {"name": "SK Slavia Praha", "shortName": "Slavia Praha"}
    aliases = team_aliases(team)
    for a in ("slavia praha", "slavia prague", "slavia"):
        assert a in aliases
    assert team_tokens(team) == ["praha", "slavia"]


def test_generic_words_never_tokens():
    assert team_tokens({"name": "Manchester United FC", "shortName": "Man United"}) == []
    assert team_tokens({"name": "Real Madrid CF", "shortName": "Real Madrid"}) == []
    assert team_tokens({"name": "Borussia Dortmund", "shortName": "Dortmund"}) == ["dortmund"]
    assert team_tokens({"name": "Club Atlético de Madrid", "shortName": "Atleti"}) == ["atleti"]


def test_short_aliases_filtered_unless_explicit():
    # "om" (2 letters) is kept because it is an explicit nickname; "sk" never is
    assert "om" in team_aliases({"name": "Olympique de Marseille", "shortName": "Marseille"})
    aliases = team_aliases({"name": "PAE AEK", "shortName": "AEK"})
    assert "aek" in aliases and "aek athens" in aliases


def test_special_letters_in_aliases():
    team = {"name": "FK Bodø/Glimt", "shortName": "Bodø/Glimt"}
    assert "bodo glimt" in team_aliases(team)
    assert team_tokens(team) == ["glimt"]


def test_aliases_file_is_normalised():
    data = extra_aliases()
    assert "paris saint germain" in data
    for key, values in data.items():
        assert norm(key) == key
        assert all(norm(v) == v and v for v in values)


def test_matching_rules_are_normalised():
    words = EXCLUDED_TITLE_WORDS + [k for kws in COMPETITION_KEYWORDS.values() for k in kws]
    assert all(norm(w) == w for w in words)
    assert "1907" in GENERIC_WORDS
