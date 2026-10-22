# Football_Rama — prototype EPG

Objectif : vérifier si l'EPG d'un accès IPTV Xtream suffit pour savoir quelles chaînes diffusent les matchs des grands championnats européens et de la Ligue des champions (prototype `scripts/poc_epg.py` validé sur Ligue 1, Premier League et Ligue des champions ; 6 championnats ajoutés le 2026-10-22, voir `docs/CONTRAT_FIXTURES.md`).

## Installation

Python 3.10+ uniquement (aucune dépendance externe).

1. Copier `.env.example` en `.env`.
2. Remplir l'accès Xtream et la clé football-data.org.

## Lancement

```
py scripts/poc_epg.py              # utilise le cache s'il a moins de 6 h
py scripts/poc_epg.py --refresh    # force le re-téléchargement
py scripts/poc_epg.py --days 5     # fenêtre de matchs (10 jours max)
```

## Sortie

- Console : indicateurs de qualité EPG, puis chaque match avec les chaînes trouvées.
- `output/rapport_AAAAMMJJ_HHMM.json` : rapport complet.
- Score `fort` : les deux équipes sont dans le titre ou la description. Score `moyen` : une équipe et le nom de la compétition.

## Sécurité

- `.env`, `cache/` et `output/` sont exclus de git.
- Les identifiants ne sont jamais affichés : les messages d'erreur montrent seulement le chemin, sans les paramètres de l'URL.
