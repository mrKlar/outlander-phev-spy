# Publication Google Play — état et marche à suivre manuelle

## État au 2026-10-30

- Le bundle de release signé est **prêt**.
- **Rien n'a été téléversé** : aucun accès à la Play Console n'a été fait, aucun envoi réseau vers Google.
- Version : `versionCode 1`, `versionName "0.1.0-m0"`, package `ca.mrklar.outlander.phevspy`.

## Bundle produit

- Chemin : `app/build/outputs/bundle/release/app-release.aab`
  (absolu : `/home/n/code/outlander-phev-spy/app/build/outputs/bundle/release/app-release.aab`)
- Taille : `7 975 124 octets` (`7,7M` selon `ls -lh`)
- SHA-256 du fichier :

```
551e517cd75a1db266f41a10e11b096926f075fabe3422f6e7432d0e2086a33f  app/build/outputs/bundle/release/app-release.aab
```

- Signature vérifiée : `jarsigner -verify` → `jar verified.` (certificat auto-signé, normal pour une clé d'upload ; avertissements d'horodatage et de chaîne PKIX attendus, sans effet pour Play).
- Contenu confirmé (`unzip -l`) : `BundleConfig.pb`, `base/dex/`, `base/manifest/AndroidManifest.xml`, `base/res/`, `META-INF/MANIFEST.MF`.

## Clé d'upload dédiée

- Alias : `outlander-upload`
- DN du certificat : `CN=Outlander PHEV Spy, O=mrKlar, C=CA`
- Empreinte SHA-256 du certificat d'upload (publique, consignable) :

```
DE:3E:11:C1:C7:24:FE:7F:89:54:4E:2F:24:19:84:18:E4:7C:DF:8B:32:02:C4:20:BF:58:19:A2:EC:92:E1:EA
```

- Validité : du 2026-10-30 au 2054-03-17 (10 000 jours), RSA 2048 bits.
- Keystore : `/home/n/code/outlander-phev-spy/.toolchain/outlander-upload.keystore` (format JKS, permissions `600`)
- Mots de passe : `/home/n/code/outlander-phev-spy/.toolchain/upload-signing.env` (permissions `600`)
- Ces deux fichiers sont **hors git** (`.gitignore:2:/.toolchain/`, confirmé par `git check-ignore`). Aucun mot de passe ne figure dans ce dépôt.
- Remarque JKS : le JDK 21 crée du PKCS12 par défaut, qui ignore un mot de passe de clé distinct du mot de passe du keystore (« Different store and key passwords not supported for PKCS12 KeyStores »). Le keystore a donc été généré en JKS pour que les **deux** mots de passe distincts soient réellement effectifs.

- [ ] Sauvegarder `/home/n/code/outlander-phev-spy/.toolchain/outlander-upload.keystore` et `/home/n/code/outlander-phev-spy/.toolchain/upload-signing.env` hors machine (action propriétaire)

> Perdre la clé d'upload n'est pas fatal (réinitialisation possible dans la Play Console avec le certificat), mais perdre les deux fichiers complique inutilement les futurs téléversements. La clé de signature de l'application, elle, sera détenue par Google (Play App Signing).

## Rebuild reproductible

```bash
source scripts/dev-env.sh
source .toolchain/upload-signing.env
./gradlew :protocol:test :app:bundleRelease
```

Sans les quatre variables `OUTLANDER_UPLOAD_*`, la configuration reste valide et le bundle release est produit **non signé** (utile en CI locale sans secrets) — vérifié par `./gradlew :app:bundleRelease --dry-run` sans les variables.

## Marche à suivre MANUELLE dans la Play Console

À faire par le propriétaire, dans le navigateur. Aucune de ces étapes n'a été faite.

1. Ouvrir la [Play Console](https://play.google.com/console) avec le compte développeur.
2. « Créer une application » : nom « Outlander PHEV Spy », langue par défaut français (Canada), type « Application », gratuite. Le package `ca.mrklar.outlander.phevspy` est fixé par le bundle et ne pourra plus changer.
3. Accepter **Play App Signing** (signature des applications par Google Play) : Google détiendra la clé de signature finale ; notre keystore ne sert qu'à authentifier les téléversements (clé d'upload).
4. Aller dans « Tests » → « Test fermé » → créer une piste de test fermé (par exemple « Alpha »).
5. Créer une version dans cette piste et téléverser `/home/n/code/outlander-phev-spy/app/build/outputs/bundle/release/app-release.aab`. Vérifier que l'empreinte du certificat d'upload affichée correspond à celle ci-dessus.
6. Remplir les notes de version : voir [fiche-boutique.md](fiche-boutique.md), section « Notes de version (test fermé) ».
7. Ajouter les testeurs à la piste fermée (liste de courriels ou groupe Google) : voir la section « Testeurs » ci-dessous et le modèle [testeurs-modele.csv](testeurs-modele.csv).
8. Remplir toutes les sections obligatoires du tableau de bord de l'application :
   - **Fiche boutique** : nom, description courte, description complète, icône, visuels — textes préparés dans [fiche-boutique.md](fiche-boutique.md) ; les visuels restent à produire.
   - **Confidentialité** : URL d'une politique de confidentialité (à fournir par le propriétaire).
   - **Sécurité des données** : ébauche M0 dans [fiche-boutique.md](fiche-boutique.md) (aucune donnée collectée/partagée par le squelette actuel) — à réviser avant envoi.
   - **Classification du contenu** : répondre au questionnaire (outil utilitaire, aucun contenu sensible attendu).
   - **Public cible** : adultes (outil lié à un véhicule).
   - **Annonces** : déclarer « aucune annonce ».
   - **Accès à l'app** : aucun compte, aucun identifiant requis — déclarer l'accès libre à toutes les fonctions.
9. Envoyer la piste fermée en révision et attendre l'approbation de Google avant de partager le lien d'opt-in aux testeurs.

## Testeurs — règle Google Play à respecter

Google Play exige de **vrais comptes Google** dont les titulaires ont **accepté l'invitation (opt-in)** via le lien de la piste fermée. Conséquences :

- Une liste d'adresses inventées ne compte pas comme testeurs et ne doit **pas** être fabriquée.
- [testeurs-modele.csv](testeurs-modele.csv) ne contient donc **aucune adresse** : c'est un modèle vide à remplir uniquement avec les adresses de comptes Google réels de personnes qui acceptent de tester.
- Pour les comptes développeur personnels récents, Google Play peut en outre imposer un nombre minimal de testeurs en opt-in et une durée minimale de test fermé avant l'accès à la production. Vérifier l'exigence exacte affichée dans la Console pour ce compte.
