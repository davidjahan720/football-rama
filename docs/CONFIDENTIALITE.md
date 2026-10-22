# Politique de confidentialité — Matchs & Chaînes

*Dernière mise à jour : [date de publication à compléter]*

Matchs & Chaînes est une application pour Android TV qui indique quelles chaînes de **votre propre abonnement IPTV** diffusent les matchs de football, d’après le guide TV de votre fournisseur. Elle ne fournit, ne diffuse et ne lit aucune chaîne.

**Éditeur :** David Jahan — contact : [adresse e-mail de contact à compléter]

## En résumé

- L’éditeur ne collecte **aucune** donnée personnelle : pas de compte, pas de serveur Matchs & Chaînes, pas de statistiques d’usage, pas de publicité, pas de rapport de plantage.
- Vos identifiants IPTV restent sur votre appareil, chiffrés, et ne sont envoyés qu’au serveur de votre fournisseur.
- « Se déconnecter » efface vos identifiants et les résultats enregistrés. Désinstaller l’application efface tout.

## Données traitées sur votre appareil

| Donnée | Finalité | Stockage | Durée de conservation |
|---|---|---|---|
| Adresse du serveur, identifiant et mot de passe IPTV | Interroger le serveur de votre fournisseur | Sur l’appareil uniquement, chiffrés (AES-256-GCM, clé non exportable de l’Android Keystore) | Jusqu’à la déconnexion ou la désinstallation |
| Liste de vos chaînes et guide TV de votre fournisseur | Trouver les chaînes qui diffusent les matchs | Guide TV : fichier temporaire supprimé après analyse. Résultat (noms de chaînes, numéros de flux) : sur l’appareil | Jusqu’à la prochaine mise à jour, la déconnexion ou la désinstallation |
| Réglages (User-Agent facultatif, filtre pays) | Préférences d’affichage et de connexion | Sur l’appareil | Jusqu’à la déconnexion ou la désinstallation |

Ces données ne sont ni sauvegardées dans le cloud ni transférées vers un autre appareil (sauvegarde Android désactivée).

## Données transmises à des tiers

1. **Votre fournisseur IPTV** — l’application lui envoie vos identifiants pour récupérer votre liste de chaînes et votre guide TV, exactement comme le ferait votre lecteur IPTV. Ce traitement relève de votre relation avec ce fournisseur, choisi par vous. Si son adresse commence par `http://` (connexion non chiffrée), vos identifiants circulent en clair sur le réseau : l’application vous en avertit.
2. **GitHub Pages (GitHub, Inc.)** — toutes les quatre heures environ, l’application télécharge le calendrier public des matchs, sans identifiant ni information de compte. Comme pour toute consultation d’un site web, l’hébergeur reçoit techniquement l’adresse IP de l’appareil et des informations techniques de connexion, qu’il peut journaliser pour la sécurité de son service. GitHub peut traiter ces données hors de l’Union européenne (voir sa déclaration de confidentialité : https://docs.github.com/fr/site-policy/privacy-policies/github-general-privacy-statement).

Le calendrier provient de football-data.org ; l’application ne transmet rien à ce service.

**Connexion depuis le téléphone (facultative).** Si vous choisissez « Se connecter avec mon téléphone », le boîtier ouvre pendant 5 minutes au plus une page web sur votre réseau local. Le formulaire que vous remplissez sur votre téléphone est envoyé directement au boîtier, sans passer par Internet ni par un serveur Matchs & Chaînes. Cette liaison locale n’est pas chiffrée (HTTP) : utilisez cette option uniquement sur votre réseau domestique. La page s’arrête dès la connexion réussie, à la sortie de l’écran ou après 5 codes de vérification erronés ; rien n’y est conservé.

## Vos droits

L’éditeur ne détenant aucune donnée vous concernant, vous exercez directement la maîtrise de vos données :

- **Effacement :** Réglages › « Se déconnecter » (identifiants, clé de chiffrement, résultats et réglages supprimés), ou désinstallation de l’application.
- **Accès et rectification :** reconnectez-vous avec d’autres identifiants.

Pour toute question, écrivez à l’adresse de contact ci-dessus. Vous pouvez aussi adresser une réclamation à la CNIL (https://www.cnil.fr).

## Sécurité

Identifiants chiffrés par une clé matérielle de l’appareil quand celui-ci le permet ; aucune journalisation des identifiants ; calendrier vérifié par signature numérique avant usage ; connexion HTTPS obligatoire pour le calendrier ; captures d’écran bloquées sur l’écran de connexion ; connexion depuis le téléphone protégée par un lien à usage unique et un code de vérification affiché uniquement sur la TV.

## Modifications

Toute modification de cette politique sera publiée à cette adresse avec une nouvelle date de mise à jour.
