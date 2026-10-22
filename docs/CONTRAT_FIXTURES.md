# Contrat `fixtures.json` (publisher → app Android)

Ce document est la source de vérité de l'interface entre le publisher (Python, GitHub Actions) et l'app Android. Toute modification passe par un incrément de `schema`.

## Fichiers publiés (hébergement statique HTTPS)

| Fichier | Contenu |
|---|---|
| `fixtures.json` | Calendrier J et J+1 et règles de matching (UTF-8, JSON compact) |
| `fixtures.json.sig` | Signature Ed25519 des **octets exacts** de `fixtures.json`, encodée en base64 standard (une ligne) |

## Signature

- Algorithme : Ed25519 (RFC 8032).
- La clé privée (32 octets bruts, en base64) est stockée uniquement dans le secret GitHub `FIXTURES_SIGNING_KEY`. Elle n'est jamais committée.
- La clé publique (32 octets bruts, en base64) est intégrée à l'app au moment du build (`BuildConfig.FIXTURES_PUBLIC_KEY`).
- L'app **rejette** le fichier si :
  - la signature est invalide ;
  - `schema` ne correspond pas à une version connue ;
  - `generated_at` est antérieur à celui du fichier déjà en cache (anti-rollback) ;
  - `valid_until` est dépassé.

### Compléments (2026-10-20, après livraison du publisher)

- `fixtures.json.sig` n'a pas de retour à la ligne final. L'app applique quand même `trim()` avant de décoder.
- **Rotation de clé :** l'app accepte une **liste** de clés publiques (`BuildConfig.FIXTURES_PUBLIC_KEYS`, séparées par des virgules). La signature est valide si l'une d'elles la vérifie.
- **Incohérence passagère :** le cache CDN peut servir un JSON et une signature de deux versions différentes pendant quelques minutes. Une signature invalide n'est donc pas traitée comme une attaque : l'app garde son cache valide et réessaie plus tard (backoff).
- **Bande passante :** l'app fait des requêtes conditionnelles (`ETag` / `If-None-Match`, réponse 304), par exemple avec le cache HTTP d'OkHttp.

## Schéma (version 1)

```json
{
  "schema": 1,
  "generated_at": "2026-10-20T06:00:00Z",
  "valid_until": "2026-10-21T18:00:00Z",
  "window": { "from": "2026-10-20T00:00:00Z", "to": "2026-10-22T03:00:00Z" },
  "competitions": { "PL": "Premier League", "FL1": "Ligue 1", "CL": "Ligue des champions" },
  "matching": {
    "competition_keywords": { "PL": ["premier league", "epl"], "FL1": ["ligue 1"], "CL": ["champions league", "ucl"] },
    "excluded_title_words": ["youth league", "u19", "highlights"]
  },
  "matches": [
    {
      "id": 551234,
      "competition": "CL",
      "kickoff_utc": "2026-10-20T19:00:00Z",
      "home": { "name": "Paris Saint-Germain FC", "short": "PSG", "aliases": ["paris saint germain", "psg", "paris sg"], "tokens": [] },
      "away": { "name": "FC Barcelona", "short": "Barça", "aliases": ["barcelona", "barca", "barcelone"], "tokens": ["barcelona"] }
    }
  ]
}
```

Règles :
- `aliases` et `tokens` sont **déjà normalisés** par le publisher : minuscules, sans accents, lettres spéciales translittérées (ø→o, ß→ss…), ponctuation remplacée par des espaces, espaces compactés. L'app applique la même normalisation au texte de l'EPG avant de comparer (voir ci-dessous).
- `window` : de minuit UTC du jour J à J+2 03:00 UTC, bornes incluses. Cela couvre les soirées de J+1 en Europe.
- Statuts football-data conservés : SCHEDULED, TIMED, IN_PLAY, PAUSED, EXTRA_TIME, PENALTY_SHOOTOUT. Les matchs dont une équipe n'est pas encore connue (« TBD ») sont exclus.
- `competition_keywords` et `excluded_title_words` contiennent les listes complètes du prototype. L'exemple ci-dessus est abrégé.
- `tokens` peut être vide. « paris » est un mot générique (il désignerait aussi le Paris FC) : le PSG est reconnu par « psg », « paris sg » et « paris saint germain ».
- `valid_until` = `generated_at` + 36 h.
- Les dates sont en ISO 8601 UTC, suffixe `Z`.

## Normalisation (identique des deux côtés)

1. Translittération : `ø→o, Ø→O, æ→ae, Æ→AE, ß→ss, đ→d, Đ→D, ł→l, Ł→L, ı→i, œ→oe, Œ→OE`.
2. Décomposition Unicode NFKD, puis suppression des caractères non ASCII.
3. Passage en minuscules.
4. Remplacement de toute suite de caractères hors `[a-z0-9]` par un espace, puis suppression des espaces en début et fin.

## Règles de matching (appliquées par l'app)

Référence : `scripts/poc_epg.py`, validé sur données réelles le 2026-10-20 (18/18 matchs).

- Programme candidat : début dans [coup d'envoi − 60 min, coup d'envoi + 30 min], ou programme qui couvre le coup d'envoi.
- `contains(texte, alias)` : l'alias est présent comme suite de mots entière (frontières `[a-z0-9]`).
- Une équipe est trouvée si l'un de ses `aliases` ou `tokens` est présent.
- Un programme est ignoré si son titre normalisé contient un mot de `excluded_title_words`.
- Un programme est ignoré si son titre, après retrait du préfixe `^.*:\s+`, se découpe sur `\s+(-|–|—|v|vs|x|/)\.?\s+` en au moins 2 segments et que l'un des 2 premiers segments ne contient aucune des deux équipes.
- Score :
  - `fort` : les deux équipes sont dans le **titre** ;
  - `moyen` : les deux équipes sont dans le titre + la description, ou une seule équipe et un mot-clé de la compétition.
- Regroupement des flux d'une même chaîne : on retire les mentions de qualité `FHD|UHD|HD|SD|LQ|HQ|4K|8K|HEVC|H265|50FPS|60FPS|RAW|BACKUP` du nom.

## update.json (mise à jour de l'application)

Ajouté le 2026-10-22. L'app est distribuée par APK (GitHub Releases + app Downloader) : le Play Store des boîtiers ne fonctionne pas, il n'y a donc pas de mise à jour automatique. L'app consulte `update.json` pour proposer la nouvelle version.

### Fichiers publiés

| Fichier | Contenu |
|---|---|
| `update.json` | Dernière version publiée (UTF-8, JSON compact, champs dans l'ordre du schéma) |
| `update.json.sig` | Signature Ed25519 des **octets exacts** de `update.json` : même clé (`FIXTURES_SIGNING_KEY`) et même format que `fixtures.json.sig` |

Source : `publisher/app_release.json`, versionné dans le dépôt et mis à jour par `scripts/publish_release.ps1`. Le workflow `publish.yml` le valide, le copie en `update.json` et le signe à chaque exécution. Si le fichier est absent ou contient un placeholder (`"sha256": ""`), `update.json` n'est pas publié et le workflow n'échoue pas : l'app reçoit un 404 et considère qu'elle est à jour. Un manifeste invalide n'est jamais signé ; l'étape échoue sans bloquer la publication du calendrier.

### Schéma (version 1)

```json
{
  "schema": 1,
  "version_code": 2,
  "version_name": "1.0.1",
  "apk_url": "https://github.com/davidjahan720/football-rama/releases/download/v1.0.1/MatchsEtChaines-1.0.1.apk",
  "sha256": "<64 caractères hexadécimaux minuscules>",
  "size": 12345678,
  "min_sdk": 26,
  "notes": "Corrections et améliorations."
}
```

Règles (vérifiées par le publisher **et** par l'app) :

- `schema` = 1 ; `version_code` entier > 0 (≤ 2³¹ − 1, comme le `versionCode` Android) ; `version_name` de la forme `x.y.z`.
- `apk_url` commence **exactement** par `https://github.com/davidjahan720/football-rama/releases/download/`, suivi de `<tag>/<fichier>.apk` : deux segments seulement, caractères `[A-Za-z0-9._-]`, pas de `..`, pas d'encodage `%`, pas d'identifiants, pas de port, pas de paramètres ni de fragment. Le publisher impose en plus `v<version_name>/MatchsEtChaines-<version_name>.apk`.
- `sha256` : 64 caractères hexadécimaux minuscules ; `size` : entier entre 1 et 100 Mo (104 857 600 octets).
- `min_sdk` : niveau d'API Android minimal ; `notes` : texte facultatif, 500 caractères au maximum.
- Taille de `update.json` : 64 Ko au maximum. Les champs inconnus sont refusés par le publisher et ignorés par l'app.

### Comportement de l'app

1. Vérification au démarrage et dans la tâche de rafraîchissement, au plus une fois toutes les 12 h (1 h après un échec réseau), avec requête conditionnelle (`ETag` / 304). Le bouton « Rechercher une mise à jour » des réglages force une vérification (au plus une par minute).
2. Rejet si : signature invalide, URL hors du préfixe, `sha256` ou `size` invalides, schéma inconnu. Un `version_code` inférieur ou égal à la version installée est ignoré (rejeu d'un ancien manifeste sans effet). Un `min_sdk` supérieur à l'Android de l'appareil est ignoré.
3. L'installation n'est lancée que par l'utilisateur (« Installer ») : téléchargement HTTPS (redirections suivies manuellement, HTTPS et hôtes `github.com` / `*.githubusercontent.com` uniquement), taille et SHA-256 vérifiés **avant** l'installation, puis une seconde fois sur les octets remis à `PackageInstaller`. Le fichier temporaire est supprimé après usage.
4. Android refuse en plus toute mise à jour signée par un autre certificat que l'app installée, ou de `versionCode` inférieur.
