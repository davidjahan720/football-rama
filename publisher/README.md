# Football_Rama — publisher

Job planifié (GitHub Actions, toutes les 3 h) qui récupère sur football-data.org les matchs du jour J et de J+1 de 9 compétitions (Ligue 1, Premier League, Ligue des champions, Bundesliga, Serie A, LaLiga, Eredivisie, Liga Portugal, Championship : toutes incluses dans l'offre gratuite, une seule requête par exécution), puis publie sur GitHub Pages :

- `fixtures.json` : calendrier + alias d'équipes normalisés + règles de matching ;
- `fixtures.json.sig` : signature Ed25519 des octets exacts de `fixtures.json` (base64, une ligne, sans retour à la ligne final).

Le format est défini par [`docs/CONTRAT_FIXTURES.md`](../docs/CONTRAT_FIXTURES.md) (schéma 1). L'app Android vérifie la signature avec la clé publique intégrée au build et rejette tout fichier invalide, périmé (`valid_until` = génération + 36 h) ou plus ancien que celui en cache.

Attribution obligatoire (CGU football-data.org, §7) : l'app doit afficher « Football data provided by the Football-Data.org API ».

## Variables

| Nom | Type | Rôle |
|---|---|---|
| `FOOTBALL_DATA_TOKEN` | secret GitHub / variable d'environnement | Jeton API football-data.org (en-tête `X-Auth-Token`) |
| `FIXTURES_SIGNING_KEY` | secret GitHub / variable d'environnement | Clé privée Ed25519, 32 octets bruts en base64 |
| `FIXTURES_PUBLIC_KEY` | variable de dépôt GitHub (non secrète) | Clé publique correspondante, passée à `--verify-key` |

Les secrets ne sont lus que dans l'environnement : jamais en argument de ligne de commande, jamais journalisés.

## Lancement local

```powershell
cd publisher
py -m venv .venv
.\.venv\Scripts\python.exe -m pip install --require-hashes -r requirements-dev.txt
.\.venv\Scripts\python.exe -m pytest

# Publication réelle (consomme 1 requête API) :
$env:FOOTBALL_DATA_TOKEN = "..."
$env:FIXTURES_SIGNING_KEY = "..."
.\.venv\Scripts\python.exe -m football_rama_publisher publish --out dist/ --verify-key "<clé publique base64>"
Remove-Item Env:FOOTBALL_DATA_TOKEN, Env:FIXTURES_SIGNING_KEY
```

Codes retour : `0` publié, `1` échec API ou publication (aucun fichier écrit, les fichiers précédents restent intacts), `2` configuration invalide (variable manquante, clé mal formée).

Un jour sans match produit un fichier valide avec `"matches": []`.

## Manifeste de mise à jour de l'app (`update.json`)

`app_release.json` (dans ce dossier, versionné) décrit la dernière release de l'APK. Il est mis à jour par `scripts/publish_release.ps1`. Contrat : section « update.json » de [`docs/CONTRAT_FIXTURES.md`](../docs/CONTRAT_FIXTURES.md).

```powershell
# Validation seule (aucune clé nécessaire) :
.\.venv\Scripts\python.exe -m football_rama_publisher check-update --manifest app_release.json
# Publication signée (même clé que le calendrier, lue dans FIXTURES_SIGNING_KEY) :
.\.venv\Scripts\python.exe -m football_rama_publisher publish-update --manifest app_release.json --out dist/ --verify-key "<clé publique base64>"
```

Fichier absent ou placeholder (`"sha256": ""`) : rien n'est publié, code retour `0`. Manifeste invalide : rien n'est signé, code retour `1`.

## Alias d'équipes

Les surnoms absents de football-data (PSG, Barça, Spurs…) sont dans [`football_rama_publisher/aliases.json`](football_rama_publisher/aliases.json). Clé = nom normalisé sans mots vides ni année de fondation ; valeurs déjà normalisées. Le fichier est validé au chargement et par les tests : lancer `pytest` après chaque modification.

## Génération des clés

```powershell
.\.venv\Scripts\python.exe keygen.py
```

Le script affiche la clé privée et la clé publique sans écrire de fichier. Copier :

1. la clé privée dans le secret `FIXTURES_SIGNING_KEY` (Settings > Secrets and variables > Actions) ;
2. la clé publique dans la variable `FIXTURES_PUBLIC_KEY` et dans la configuration de build Android (`BuildConfig.FIXTURES_PUBLIC_KEY`).

Fermer ensuite le terminal (la clé privée ne doit exister que dans le secret GitHub).

## Rotation de clé

L'app ne connaît qu'une clé publique par build : une rotation impose une nouvelle version de l'app.

1. Générer une nouvelle paire avec `keygen.py`.
2. Publier une version de l'app qui embarque la nouvelle clé publique. Pour une rotation planifiée sans coupure, l'app doit accepter l'ancienne **et** la nouvelle clé pendant la transition (à prévoir côté Android).
3. Une fois la nouvelle version largement diffusée, remplacer `FIXTURES_SIGNING_KEY` et `FIXTURES_PUBLIC_KEY`, puis lancer le workflow manuellement (`workflow_dispatch`).
4. Retirer l'ancienne clé publique de l'app à la version suivante.

En cas de fuite de la clé privée : remplacer immédiatement le secret, publier en urgence une version de l'app avec la nouvelle clé publique, et considérer que les anciennes versions peuvent recevoir un calendrier falsifié jusqu'à leur mise à jour.
