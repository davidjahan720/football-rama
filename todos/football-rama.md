# Football_Rama — plan de réalisation

Architecture validée par David le 2026-10-20. Boîtier cible : Formuler Z11 Pro (Android 11).
Interface entre les deux briques : `docs/CONTRAT_FIXTURES.md`.

## Lot A — AGENT AUTOMATISATION : publisher (dossier `publisher/`)
- [ ] `publish_fixtures.py` : football-data.org → `fixtures.json` (J/J+1, PL/FL1/CL) + signature Ed25519
- [ ] `keygen.py` : génère la paire de clés (privée affichée une seule fois, jamais écrite dans le dépôt)
- [ ] Alias et règles de matching repris du prototype
- [ ] Tests pytest (normalisation, alias, signature, fenêtre J/J+1, erreurs API)
- [ ] `.github/workflows/publish.yml` : cron toutes les 3 h, publication statique
- [ ] Vérifier la licence football-data.org (usage par une app diffusée)

## Lot B — AGENT BACKEND/ANDROID : cœur de l'app (dossier `android/`)
- [ ] Projet Gradle (wrapper), modules `:core` (Kotlin/JVM pur) et `:app` (Android)
- [ ] `:core` : client Xtream, parseur XMLTV en flux, matcher, vérification de signature, regroupement des chaînes
- [ ] Tests JUnit, dont un test d'intégration optionnel sur `cache/epg.xml`
- [ ] `:app` : stockage chiffré des identifiants, dépôt de données, rafraîchissement WorkManager

## Lot C — AGENT FRONTEND : écrans Android TV (après le lot B)
- [ ] Connexion Xtream, liste des matchs J/J+1, détail des chaînes, état « actualisation » et erreurs

## Lot D — AGENT SÉCURITÉ / RGPD
- [ ] Audit : stockage, réseau, journaux, signature, permissions, minification

## Lot E — AGENT DOCUMENTATION + AGENT TESTS/QA
- [ ] INSTALL.md, guide d'installation sur le Z11 Pro, ARCHITECTURE.md
- [ ] Revue de code, cas limites, test sur le boîtier

## Actions manuelles David (au fil de l'eau)
- [ ] Créer le dépôt GitHub et le secret `FIXTURES_SIGNING_KEY` et `FOOTBALL_DATA_TOKEN`
- [ ] Choisir l'hébergement du JSON (GitHub Pages ou Cloudflare Pages)
- [ ] Installer l'APK sur le Z11 Pro

## Phase 2 (2026-10-22) — version officielle + connexion par téléphone
### Lot F — AGENT BACKEND/AUTOMATISATION : version officielle et mises à jour
- [ ] Signature release (keystore hors dépôt, script de création lancé par David)
- [ ] `update.json` signé Ed25519 publié par le workflow (contrat mis à jour)
- [ ] Vérification de mise à jour dans l'app + téléchargement, contrôle SHA-256, installation (PackageInstaller)
- [ ] Script de publication GitHub Release (lancé après accord de David)
### Lot G — AGENT FRONTEND/SÉCURITÉ : connexion par téléphone (QR) + finitions
- [ ] Serveur HTTP local éphémère sur le boîtier, jeton à usage unique, QR code
- [ ] Libellés pays, noms courts des équipes, masquage des matchs terminés
### Lot H — DOCUMENTATION : guide d'installation adhérent (Downloader, codes, QR)
