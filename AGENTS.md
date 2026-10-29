# AGENTS.md — Règles pour les agents

Ce dépôt développe une app Android Kotlin+Compose en lecture seule qui dialogue avec un véhicule réel (Mitsubishi Outlander PHEV 2024) via un ELM327. Les règles ci-dessous sont contraignantes.

## Avant de coder

- Lire en entier la [spécification de design](docs/superpowers/specs/2026-10-29-outlander-phev-spy-design.md) et la [synthèse décisionnelle](docs/recherche/2026-10-29-00-synthese-decisions.md). Consulter les rapports détaillés `01` à `04` de [docs/recherche/](docs/recherche/) pour tout point de protocole.
- La spec fait foi sur le périmètre. Ce qui n'y figure pas n'est pas à construire ; ce qui y est marqué inconnu se résout par le plan écrit, pas par invention.

## Invariant lecture seule — non négociable

- Lecture seule stricte. Toute requête émise vers le véhicule doit figurer dans la liste blanche de la spec (modes OBD `01, 02, 03, 07, 09, 0A` ; UDS `0x10` sous-fonctions `01`/`03`, `0x19` sous-fonctions `01`/`02`/`0A`, `0x22`, `0x3E`), plus l'exception fermée `2101` puis `2102` vers `761`.
- La liste blanche vit dans le code (`UdsClient`, unique point d'émission, refus avant émission) **et** dans les tests (test de propriété couvrant les 256 valeurs du premier octet et les sous-fonctions fermées). Aucune modification ne peut affaiblir l'un sans l'autre.
- Jamais d'écriture, sous aucun prétexte : aucun service de la liste noire (`27, 2E, 31, 2F, 14, 85, 28, 11, 34–38, 23, 3D, 2A, 2C, 87, 10 02`, modes `04, 08`), jamais `0x27`, jamais de contournement de Secure Gateway.
- Rien n'est envoyé au véhicule hors du plan de la spec : ni par l'app, ni depuis la console AT brute (qui passe par le même garde-fou), ni par un script d'agent. Toute nouvelle requête suppose d'abord une mise à jour de la spec.

## Captures et VIN

- Jamais une capture contenant un VIN réel ne se committe. Le dossier `captures/` et les journaux de session `*.jsonl` sont ignorés par git ; les garder hors du dépôt.
- Avant qu'une capture devienne fixture de test ou soit partagée : anonymiser le VIN avec l'outil prévu (package `journal`), puis revérifier par re-lecture qu'aucun VIN réel ne subsiste.

## Décodages

- Toute formule de décodage cite sa source et son statut de confiance dans sa `SignalDefinition` (`source`, `confidence`). Une formule sans source ne se committe pas.
- Promotion de statut seulement selon la règle de la spec : corrélation au tableau de bord du véhicule cible, session citée. Aucun passage automatique en « vérifié ».
- Interdiction des constantes de l'ancien modèle en dur (80 cellules, 300 V, 12/13,8 kWh, dénominateur SOH 38/40 Ah) et de toute formule inventée (températures « octet − 40 », nombre de cellules déduit).
- Aucun SOH affiché sans dénominateur nommé et sourcé ; en v1, aucun SOH du tout.
- `NO DATA` signifie `no_data` dans le journal et l'UI. Jamais « ECU absent » ni « DID inexistant ».

## Vérification

- Ordre de vérification avant de déclarer un travail terminé : `./gradlew :protocol:test` d'abord, puis `./gradlew :app:assembleDebug`. Les deux doivent être verts, dans cette session.
- Le test sans voiture précède le branchement : émulateur Ircama via TCP et rejeu de captures sont les oracles ; la capture réelle gagne en cas de divergence.
- Ne jamais affirmer qu'un comportement véhicule « marche » sans journal de session réel à l'appui.

## Conventions

- Documentation, commentaires destinés au propriétaire et messages de commit en **français**.
- Versions uniquement dans `gradle/libs.versions.toml` ; toolchain épinglée selon la spec.
- `:protocol` reste en Kotlin/JVM pur : aucune dépendance Android n'y entre.
- Sauvegarder `$HOME/.android/debug.keystore` hors machine dès la première installation ; ne jamais le committer.
