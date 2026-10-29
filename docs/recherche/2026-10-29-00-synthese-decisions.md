# Synthèse — LeafSpy Outlander PHEV 2024

Rapports : **R1** = ancien Outlander/PID connus, **R2** = 2023-2025/batterie/gateway, **R3** = UDS/ELM327/scan sûr, **R4** = Android/libs/tests. Décisions déjà prises et non rouvertes : Kotlin natif Compose, BLE + Bluetooth Classic, lecture seule stricte, v1 = découverte + décodage des PID/DID connus.

## A. Faits décisifs

1. **Le 2024 n’est pas un GG2W.** Plateforme Alliance CMF-CD partagée avec le Nissan Rogue/X-Trail ; PHEV Watchdog est officiellement limité aux Outlander PHEV « up to 2021 ». Tout héritage de l’ancien protocole est une hypothèse à invalider, pas une base. *Confiance élevée — R1, R2.*
2. **Seul retour terrain structuré sur la 4e gén. : CAN 29 bits, service UDS `0x22`.** Sur un Outlander PHEV 2023 (WiCAN/ELM327 + Car Scanner) : protocole ISO 15765-4 29 bits (`ATSP7`), adresse fonctionnelle `18DB33F1`, réponses filtrées `18DAF1DB` (adresse logique présumée BMU `0xDB`), plus `18DAF1DE` et `18DAF1E5` pour d’autres ECU. *Confiance moyenne — source unique, un véhicule/marché, mais concrète et partiellement corroborée par un second propriétaire — R2 ; repris en hypothèse prioritaire — R3 ; mentionné — R1.*
3. **DID Gen4 communautaires à revalider en premier sur `0xDB` :** `2201D1` SOC (équation empirique `B*0.64−19.2`), `2201D0` « SOC_MAX » selon l’auteur, `220106` tension HV, `220178`/`220179` tensions cellule max/min ; aussi cités sans équation dans les rapports : `22010B`, `22022A`, `22F0D0`. L’auteur prévient lui-même de tout prendre « with a grain of salt ». La définition exacte de l’octet `B` et les formules/échelles des autres DID sont **absentes des rapports**. *Confiance faible à moyenne — R2 ; `2201D1` aussi cité — R1.*
4. **Aucun broadcast exploitable sur la prise OBD.** `ATMA` ne produit rien et aucun trafic CAN passif n’a été vu sur le 2023 : pas de LeafSpy passif, tout doit passer par requête/réponse derrière passerelle. *Confiance moyenne-élevée — R2 ; cohérent avec l’approche scan — R3.*
5. **Protocole ni « ancien MUT » ni « Leaf/Consult ».** Le LBC Leaf parle 11 bits `79B→7BB` service `0x21` ; l’ancien Mitsubishi parle 11 bits direct `761→762` service `0x21`. Le modèle mental R2 : caisse/BCM/clé/passerelle d’origine Nissan, supervision PHEV Mitsubishi ré-exposée en UDS standard `0x22` sur 29 bits. *Confiance moyenne-élevée (~70 % selon R2) — R2.*
6. **Secure Gateway : présente vraisemblablement, mais lecture démontrée.** L’Outlander 2022+ partage le BCM/passerelle des Nissan à Central Gateway (même câble bypass BCM 40 broches), mais sur le 2023 documenté, Mode 01/09 **et** lectures `0x22` BMU ont répondu sans authentification ni bypass. Risque jugé faible pour Mode 01/09, faible-à-moyen pour `0x22` (sous-ensemble de DID inconnu, NRC/session possibles), élevé pour l’écriture — hors périmètre. *Confiance moyenne — R2, nuancé — R3.*
7. **Ancien protocole à tester à bas coût, sans y croire :** `ATFCSH761` + `ATFCSD300000` + `ATFCSM1` + `ATSH761`, puis `2101` (BMU `761→762`, réponse `61 01…`, multi-trames, ex. First Frame `10 37 61 01…` = 55 octets utiles). Décodages anko (items comptés à partir de 0 **après** `61 01`) : item 0 SOC % = valeur/2 − 5 (SOC « réel », peut dépasser 100) ; items 8+9 tension pack = valeur/10 ; items 27+28 capacité Ah = valeur/10 (MUT item 18) ; items 29+30 charge restante Ah = valeur/10 ; `2102` tensions cellules = valeur 16 bits/1000, avec trous entre blocs de 8 (liste d’offsets de forum, confiance moyenne). Formule courant items 14+15 tronquée/inutilisable, températures OBD non documentées. *Confiance élevée pour l’ancien modèle ; faible pour la transposition au 2024 — R1 ; R3 recommande le test (3 trames) puis abandon si `NO DATA`.*
8. **Batterie cible : 20 kWh brut, 350 V, 57,1 Ah (Amérique du Nord 2023-2024).** Le 22,7 kWh est Europe/Japon 2025+, pas la cible. Nombre de cellules/modules et fournisseur de la 4e gén. : **non publiés** ; ~95 cellules série est une inférence arithmétique (350 V ÷ ~3,7 V), pas une donnée. Donc interdiction de coder en dur les constantes anciennes : 80 cellules, 300 V, 12/13,8 kWh, dénominateur SOH 38 vs 40 Ah (déjà disputé sur l’ancien). Capacité utile 16-17 kWh = estimation secondaire, confiance faible. *Confiance élevée pour 20 kWh/350 V/57,1 Ah — R2 ; anciennes constantes — R1.*
9. **Pas de SOH trouvé sur la 4e gén.** L’explorateur 2025 déclare « I could not find SOH » ; son `SOC_MAX` (`2201D0`) comme proxy de dégradation est une interprétation non validée. La v1 ne doit promettre ni SOH ni capacité résiduelle tant qu’un DID équivalent à l’ancien item 18 n’est pas corrélé. *Confiance moyenne (absence constatée) — R2 ; contexte ancien — R1.*
10. **L’OBD légiféré ne donne presque rien côté batterie.** Seul PID pertinent : `0x5B` (charge batterie hybride %, `A×100/255`) **s’il est déclaré** dans le bitmask de `0140` — requête `015B`. La phase légiférée sert à prouver le tuyau, lister les répondants et valider le réassemblage ISO-TP sur le VIN (`0902`). *Confiance élevée — R3.*
11. **Le flow control manuel est le point dur ELM327.** Avec `ATCAF1`+`ATCFC1`, l’ELM gère l’ISO-TP côté bus, mais la dérivation automatique de l’ID de Flow Control échoue hors convention (+1 type `761→762`, fonctionnel 29 bits dont la FC doit partir sur l’adresse physique `18DADBF1`). Recette par ECU : `ATFCSH <ID requête physique>`, `ATFCSD 30 00 00`, `ATFCSM1`. Côté hôte, ne jamais présumer si les octets PCI ISO-TP (`10…`, `21…`) sont présents ou déjà retirés selon le firmware : journaliser le brut et écrire un réassembleur qui gère les deux. *Confiance élevée — R3 ; origine terrain — R1.*
12. **L’adaptateur fait ou défait le projet.** Les clones « ELM327 v1.5/v2.1 » ont des `ATFC*`/`ATCRA`/`ATCP` parfois absents ou menteurs, des tampons minuscules (`BUFFER FULL` sur longues réponses BLE) et un 29 bits capricieux. Référence convergente des quatre rapports : adaptateur STN/OBDLink (ou équivalent vLinker) comme oracle, matrice de capacités AT testée à chaque connexion. Watchdog déclarant « will not work with BLE » (R1) ne prouve pas que le protocole interdit le BLE — R1 le signale lui-même comme ambigu ; c’est un risque adaptateur, pas un verdict. *Confiance élevée sur le risque clone — R1-R4.*
13. **Le risque n°1 n’est pas l’ECU, c’est la batterie 12 V.** Scan = ECU maintenus éveillés ; hors READY le DC-DC ne recharge pas l’auxiliaire ; la broche 16 est un +12 V permanent (plaintes 12 V déjà rapportées sur 2023 avec adaptateur laissé branché). En READY, le thermique peut démarrer seul : travail à l’extérieur/ventilé uniquement. *Confiance élevée — R3 ; plaintes terrain — R2.*
14. **Aucune lib maintenue ne fait la découverte UDS.** kotlin-obd-api et ObdMetrics sont centrés Mode 01/DTC typés ; notre besoin (requêtes brutes `22xxxx`, `19 02…`, en-têtes `ATH1`, NRC `7F 22 31/78`, multi-ECU, réassemblage) est hors de leur modèle. Le cœur AT/ISO-TP/UDS doit être écrit maison en Kotlin pur. OVMS/OBDb n’ont rien d’exploitable pour l’Outlander PHEV (ni ancien protocole CAN transposable, ni PID 2023+). *Confiance élevée — R4 ; absence OVMS/OBDb corroborée — R1, R3.*
15. **Le test sans voiture est possible et doit précéder le premier branchement.** Ircama/ELM327-emulator est le seul émulateur trouvé sachant scénariser du UDS multi-trames avec flow control ISO-TP (série virtuelle ou TCP `-n <port>`, multi-ECU, scénarios superposables), à compléter par un transport de rejeu de captures réelles servant d’oracle indépendant octet pour octet. *Confiance moyenne-élevée — R4 ; besoin de validation contre trames réelles — R3.*

## B. Contradictions et incohérences entre rapports

1. **« Aucune liste de DID batterie publique » (R3) vs profil Gen4 détaillé (R2).** R3 s’appuie sur des dépôts OBDb/OVMS/scainner vides ; R2 a trouvé la source primaire (fil WiCAN #468, profil d’octobre 2025 avec DID et filtrages `18DAF1xx`). **Croire R2** sur l’existence des pistes — source primaire terrain — tout en gardant le niveau de confiance faible de R2 lui-même. La phrase de R3 est trop large ; son plan de scan reste valide comme méthode de revalidation.
2. **« Sans aucun héritage de PID » (conclusion R2) vs test `761→762`/`2101` recommandé (R3) et mission « décodage des PID connus ».** Pas une contradiction de fait : R2 donne un principe de planification (probabilité d’héritage faible), R3 un test de falsification à 3 trames. **Trancher :** architecture pensée pour zéro héritage, mais le test `2101` est fait une fois, tôt, car un positif changerait tout.
3. **Liste blanche R3 vs sa propre phase 2.** R3 §(c) ne blanchit que Modes `01,02,03,07,09,0A` et UDS `10 (01/03), 19 (01/02/0A), 22, 3E` — mais sa phase 2 envoie `2101` (service `0x21`, non blanchi). **Incohérence interne à résoudre dans la spec :** exception explicite et fermée — service `0x21` autorisé uniquement vers `761`, uniquement `2101` puis `2102` si `2101` répond. Aucun balayage `0x21` générique.
4. **Phase 1 « 11 bits d’abord » (R3) vs « l’OBD générique répond en 29 bits » (R2).** R3 construit sa carte d’identité sur `ATSP6`+`ATSH7DF` ; R2 rapporte que Car Scanner obtient les PID de base en 29 bits et pose comme inconnue n°1 si le 11 bits (`7DF`) répond aussi. **Croire R2** (observation) : le plan doit essayer les deux adressages pour le légiféré et traiter un silence 11 bits comme non concluant, pas comme échec.
5. **Risque gateway : « faible » (R2) vs ton plus réservé (R3).** R3 souligne que les SGW Nissan filtrent le diagnostic non légiféré et que l’applicabilité au Mitsubishi n’est pas confirmée ; R2 dispose de la preuve positive `0x22` sans auth sur un 2023. **Croire la synthèse :** risque faible-à-moyen, validable seulement sur le véhicule cible exact. Un `NO DATA` uniforme restera ambigu (gateway, filtre `ATCRA`, syntaxe d’en-tête du clone, ECU endormi).
6. **Séquences d’init ELM divergentes.** R4 cite la séquence kotlin-obd-api (`ATZ/ATE0/ATL0/ATS0/ATH1/ATSP6/ATAT1…`) comme référence ; R3 prescrit `ATS1` (lisibilité découverte) et `ATAT0`+`ATSTFF` (déterministe, anti-troncature multi-ECU) sur base datasheet. **Croire R3** pour la v1 découverte : R4 ne recommande pas cette séquence, il la cite comme exemple de lib.
7. **Place de la session ELM dans R4.** R4 §(b) place la session ELM « dans le module pur » ; son tableau §(e) la met dans `:app`. **Trancher pour le module pur** : c’est la seule option compatible avec le principe R4 lui-même (« tout ce qui peut être faux sans voiture vit dans `:protocol` ») et avec le rejeu/émulateur de R3/R4.
8. **BLE « impossible » pour Watchdog (R1) vs projet BLE+Classic (décidé) et faisabilité R4.** R1 documente une limite de l’app Watchdog et des adaptateurs BLE courants, pas du protocole ; R4 montre la voie BLE générique (découverte de services, MTU, framing par `>`). **Pas de contradiction factuelle** ; conséquence pratique : l’adaptateur de référence pour invalider/valider un silence doit être le meilleur disponible (STN, Classic d’abord), jamais un clone BLE seul.
9. Points **internes à R1**, signalés mais sans impact 2024 : 10 CMU×8 (open-source pack) vs items MUT « CMU ID01…ID11 » ; dénominateur SOH 38 vs 40 Ah ; tables d’IDs du bus interne CMU discordantes entre forks. **Ne rien en transposer** ; le bus interne BMU↔CMU n’est de toute façon pas accessible à la prise OBD.

Hors ces points, les rapports convergent : adressages anciens/nouveaux, absence OVMS/OBDb, exigences adaptateur, danger principal 12 V, lecture seule par liste blanche.

## C. Décisions d’architecture recommandées

### Modules (Gradle, trois modules + découpage interne strict)

| Module | Contenu | Environnement |
|---|---|---|
| `:protocol` | `ElmTransport` (interface), codec/tokeniseur du flux ELM (prompt `>`, écho, `SEARCHING...`, `NO DATA`, `CAN ERROR`, `BUFFER FULL`, `?`), **session ELM en acteur sérialisé** (une commande en vol, timeouts, attente NRC `78`), réassembleur ISO-TP tolérant PCI présent/absent, client OBD légiféré + UDS lecture avec **liste blanche codée en dur**, planificateur de scan en phases, définitions véhicule déclaratives et décodeurs, schéma/écriture du journal JSONL, transport de rejeu | **Kotlin/JVM pur** (JDK seul) |
| `:transport-android` | `ClassicSppTransport` (RFCOMM natif), `BleTransport` (Kable), `TcpTransport` (émulateur/dev), scanners BLE + appareils Classic appairés, permissions | Android |
| `:app` | Compose : choix d’adaptateur, console AT brute et statut de scan d’abord, signaux décodés ensuite avec badge de confiance ; saisies « vérité terrain » du propriétaire (SOC affiché, état véhicule) ; export des journaux | Android |

Le découpage demandé (transport / session ELM / protocole OBD-UDS / décodeurs véhicule / journal) vit comme **packages distincts dans `:protocol`**, pour rester testable en JVM sans multiplier les modules Gradle prématurément. Les décodeurs véhicule sont **data-driven** (inspiration EVNotify/Torque/AndrOBD, R4) : une définition par sonde = en-têtes TX/RX/FC, requête hex, offsets, échelle, unité, niveau de validation (`communautaire-non-validé`, `corrélé-tableau-de-bord`, `vérifié`). Aucune formule ne passe en « vérifié » sans corrélation véhicule ; aucun SOH affiché sans dénominateur nommé (enseignement du débat 38/40 Ah, R1).

### Interfaces clés

- `ElmTransport { val incoming: Flow<ByteArray>; suspend fun write(ByteArray); suspend fun close() }` — chunks arbitraires, jamais d’alignement supposé (R4).
- `ElmScanner { fun scan(): Flow<ElmDevice> }` — BLE découvert + Classic appairés.
- `ElmSession` (acteur/Mutex) : `suspend fun transact(cmd): ElmResponse` — sérialise, attend le `>`, classe `positive|nrc|no_data|timeout|elm_error|buffer_full`, gère `7F xx 78` (attendre, ne jamais renvoyer par-dessus).
- `EcuChannel(txId, rxId, fcHeader)` + `UdsClient.read(did)` : applique `ATSH`/`ATCRA`/`ATFCSH`/`ATFCSD`/`ATFCSM1` par cible et **refuse tout premier octet hors liste blanche avant émission** (R3 §c + exception `2101/2102` vers `761` du point B.3). Services interdits à coder en refus explicite : `27, 2E, 31, 2F, 14, 85, 28, 11, 34–38, 23, 3D, 2A, 2C, 87, 10 02`, Modes `04, 08` ; jamais de `10 03` en adressage fonctionnel.
- `SignalDefinition` / registre de métriques nommées (idée OVMS, R4) : le décodé est découplé de la requête qui l’a produit.
- `DiscoveryJournal` : `append(event)` avec flush ligne à ligne.
- Matrice de capacités adaptateur : résultat de `ATI`, `STI` (≠ `?` ⇒ STN), et de chaque commande critique (`ATFC*`, `ATCRA`, `ATCEA`, `ATCP`, `ATSP7`) ; le plan de scan se dégrade d’après elle (ex. pas de phase 29 bits si `ATCP` répond `?`). `STPX` traité en bonus détecté, jamais en prérequis (R3).

### JVM pur vs Android

JVM pur : tout le parsing, la session, l’ISO-TP, l’UDS, les décodeurs, le plan de scan, le journal, le rejeu. Android uniquement : scan/appairage/GATT/RFCOMM, permissions, stockage des fichiers de session, UI. Transport Classic : UUID SPP `00001101-0000-1000-8000-00805F9B34FB`, `bondedDevices` d’abord, `cancelDiscovery()` avant `connect()`. BLE : aucun UUID codé en dur seul — table de couples connus (`FFE0/FFE1`, `18F0`+`2AF0/2AF1`, Vgate proprio, Nordic UART `6E400001/2/3`, OBDLink BLE) puis repli générique notify+write après `discoverServices()` ; MTU 247/512 demandé, écritures découpées en `mtu−3`, framing uniquement par le `>` ELM (R4). Le modèle exact de l’adaptateur du propriétaire est **absent des rapports** : la famille d’UUID réelle restera à confirmer.

### Libs : retenues et rejetées

| Décision | Lib | Motif (rapport) |
|---|---|---|
| Retenue | **Kable 0.45.0 épinglée** (Apache-2.0) | BLE scan+connexion en Flow, aligné coroutines ; API 0.x donc isolée derrière `BleTransport` (R4) |
| Repli | Nordic Android-BLE-Library 2.11.0 + Scanner-Compat | Plus éprouvée, plus verbeuse ; seulement si Kable déçoit sur le matériel réel (R4) |
| Retenue (tests seulement) | Ircama/ELM327-emulator | Seul émulateur UDS multi-trames scriptable ; **licence à vérifier dans le dépôt avant tout usage hors test local** (`NOASSERTION` GitHub) (R4) |
| Non importée | eltonvs/kotlin-obd-api 1.4.1 | Mode 01 typé seulement ; référence d’init à lire, dépendance JitPack non justifiée (R4) |
| Rejetées | pires/obd-java-api (archivé 2017) ; RxAndroidBle (impose RxJava) ; `BluetoothGatt` en direct ; OBDSim et simulateurs matériels | R4 |
| Étude seulement | ObdMetrics (Java, Mode 01) ; AndrOBD (**GPL-3.0** : lire/utiliser comme outil de capture, ne pas copier de code) ; OVMS/EVNotify (idées d’architecture) | R4 |

Libs de sérialisation JSON/DI : **absentes des rapports** — choix à faire dans la spec, sans impact protocole.

### Toolchain épinglée (faits datés 2026-10-29, R4)

- JDK **Temurin 21 LTS** (bytecode cible 17) ; Gradle **9.7.1** (wrapper) ; AGP **9.4.1** (Kotlin intégré : ne plus appliquer `org.jetbrains.kotlin.android`, seul le plugin Compose) ; Kotlin/plugin Compose **2.4.21** ; Compose BOM **2026.09.00**.
- `compileSdk 37`, `targetSdk 36`, **`minSdk 31`** — téléphone personnel récent présumé : une seule branche permissions, `BLUETOOTH_SCAN` avec `neverForLocation` + `BLUETOOTH_CONNECT`, zéro permission localisation. (Si un vieux téléphone secondaire était visé, `minSdk 26` réintroduirait la branche API ≤ 30 avec `ACCESS_FINE_LOCATION` — R4 ; le modèle de téléphone du propriétaire est absent des rapports.)
- Versions uniquement dans `gradle/libs.versions.toml`.
- Installation headless : cmdline-tools + `sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;37.0.0"`, vérifier chaque libellé par `sdkmanager --list` le jour J ; build `./gradlew :app:assembleDebug`, tests `./gradlew :protocol:test`, install `adb install -r …/app-debug.apk`. Signature debug automatique suffisante en sideload, **à condition de sauvegarder `$HOME/.android/debug.keystore`** (sinon perte des données à la réinstallation).
- Pas de foreground service en v1 (usage interactif au premier plan).

### Stratégie de test sans auto

1. **Tests unitaires JVM sur oracles publiés** : la trame d’exemple R1 (`762 10 37 61 01…`) doit redonner items 27+28 = `01 54` → 34,0 Ah : elle valide convention d’offset et réassembleur avant tout branchement.
2. **Golden files du tokeniseur** : `7F 22 78` suivi de la vraie réponse, `NO DATA`, `CAN ERROR`, `BUFFER FULL`, écho activé, `SEARCHING...` initial, réponses multi-ECU, lignes indexées vs en-têtes CAN.
3. **Intégration émulateur** : `TcpTransport` → Ircama en TCP ; scénarios multi-ECU 11 bits et 29 bits, réponses longues (type tensions cellules) pour exercer FC et `BUFFER FULL`.
4. **Rejeu de captures** : chaque session réelle devient fixture ; le flux est re-découpé à des frontières arbitraires (milieu de ligne, juste avant `>`) et les décodés comparés aux golden files. VIN anonymisé avant tout commit de capture (R4).
5. **Test de liste blanche** : propriété vérifiée par test — toute requête dont le premier octet est hors liste blanche (et hors exception `2101/2102`→`761`) est refusée avant émission.
6. Captures initiales possibles sans notre app via AndrOBD (outil GPL utilisable tel quel) ou terminal série BT (R4).

### Journal de découverte (format retenu : JSONL R3, complété R4)

Un fichier par session, une transaction par ligne, écrit et flushé immédiatement. Champs obligatoires (R3) : `schema:1`, `session_id`, `seq`, `ts_wall` (ISO avec fuseau), `ts_mono_ms`, `phase`, `kind` (`at|uds|obd`), `adapter{ati, stn, transport}`, instantané ELM complet (`protocol, sh, cra, fcsh, fcsd, caf, st_ms`), `vehicle{state, atrv_v}`, `request{tx_id, bytes, service, did}`, `response{raw_text verbatim avant parsing, frames[], reassembled, status, nrc, elm_tokens[]}`, `duration_ms`, `retry_of`. Complément R4 : conserver aussi les octets bruts TX/RX **avec leurs frontières de chunks BLE** pour alimenter le rejeu re-découpé. Le CSV d’analyse se dérive du JSONL, jamais l’inverse. `NO DATA` est journalisé `no_data`, jamais « ECU absent ».

### Session de test « maison » du propriétaire (procédure)

Checklist affichée par l’app avant lancement : véhicule dehors ou garage ventilé, stationné, frein de parc, capot fermé, personne sous le véhicule, état **READY** (jamais de scan en roulant) ; adaptateur de référence branché ; noter/photo du tableau de bord (SOC affiché %, autonomie, état de charge AC/CHAdeMO ou aucun) au début et à la fin, horodatés dans le journal comme vérité terrain ; `ATRV` relu automatiquement toutes les ~30 s ; lecture DTC `1902FF` avant et après la première session pour prouver l’absence d’effet de bord — sans jamais effacer ; **retirer l’adaptateur en sortant** (+12 V permanent). Export du fichier JSONL en fin de session pour analyse/rejeu au bureau.

## D. Plan de la première session sur le véhicule

Objectif unique : transformer les trois inconnues (adressage réel, politique gateway, DID valides) en observations journalisées. Rythme partout : une commande en vol, ≥ 100-200 ms après réponse, attentes généreuses en identification (`ATSTFF` = 1020 ms), 400-500 ms seulement en balayage.

**Phase 0 — Conditions.** Procédure maison ci-dessus ; état READY à l’extérieur. (Alternative IG-ON seulement courte, avec mainteneur 12 V.)

**Étape 1 — Init et empreinte adaptateur (R3).** Purge 300 ms → `ATZ` (prompt sous 2 s) → `ATI`, `STI`, `ATRV` → `ATE0`, `ATL0`, `ATS1`, `ATH1`, `ATCAF1`, `ATCFC1`, `ATAT0`, `ATSTFF`, `ATCEA` (`?` toléré, journalisé). Chaque `OK`/`?` alimente la matrice de capacités. Puis `ATSP0` + premier `0100` pour l’auto-détection une seule fois, figer et journaliser via `ATDP`/`ATDPN`.

**Étape 2 — Légiféré 11 bits puis 29 bits.** `ATSP6` + `ATSH7DF`, sans `ATCRA` restrictif : `0100`, puis `0120`/`0140`/`0160`/`0180` tant que le bit de chaînage est présent ; `0900`, `0902` (VIN, test du réassemblage), `0904`, `0906`, `090A` ; `03`, `07`, `0A` ; `0101`, `0142`, tous les PID déclarés une fois en brut, `015B` si le bit `5B` est déclaré. Chaque en-tête `7E8`–`7EF` distinct = un ECU émission. Refaire le même bloc en 29 bits (`ATSP7`, `ATCP18`, en-tête fonctionnel `18DB33F1`) pour lister les répondants `18DAF1xx` — passage obligé vu le point B.4. La syntaxe exacte des en-têtes 29 bits varie selon les clones : valider chaque `OK` (R3) ; la forme « sans filtre » 29 bits précise est **absente des rapports**, à consigner telle qu’observée.

**Étape 3 — Test d’héritage ancien (une fois).** `ATSP6` ; `ATFCSH761` ; `ATFCSD300000` ; `ATFCSM1` ; `ATSH761` (+ `ATCRA762` selon la méthode R3) → `2101`. Un seul essai.

**Étape 4 — Présence UDS 11 bits (paires plausibles seulement).** Par cible : `ATSH`/`ATCRA`/`ATFCSH`/`ATFCSD 30 00 00`/`ATFCSM1`, sonde `3E00` (réponse `7E 00`), à défaut `1001` (`50 01…`). Paires dans l’ordre : normatives `7E0→7E8` … `7E7→7EF` ; Alliance documentées `797→79A`, `79B→7BB`, `784→78C`, `743→763`, `740→760` (R3). Aucun balayage `0x000`–`0x7FF` ; au-delà, seulement la plage `0x700`–`0x7FF` avec offsets +8/+1/+0x20/+3 si nécessaire. Jamais sonder avec `3E80` (réponse supprimée).

**Étape 5 — Cible 29 bits `0xDB` (hypothèse principale).** Cycle propre : `ATSP7` ; `ATCP18` ; physique `ATSH DA DB F1` ; `ATCRA DA F1 DB` ; `ATFCSH DA DB F1` ; `ATFCSD 30 00 00` ; `ATFCSM1` → `3E00`, puis `22F190`. Si silence en physique, variante fonctionnelle R3 : `ATSH DB 33 F1` + `ATCRA DA F1 DB` + FC sur l’adresse physique, sonde `22F190` uniquement. Même traitement ensuite pour `0xDE` et `0xE5` (`ATSH DA DE F1`/`ATCRA DA F1 DE`, etc. — construction par le schéma R2, à valider).

**Étape 6 — Identification de chaque répondant.** Un DID à la fois, session par défaut : `22F186` (session active, sonde rapide), `22F190`, `22F187`, `22F189`, `22F191`, `22F18C`, puis la plage `F180`–`F19F` complète ; DTC en lecture `1901FF`, `1902FF`, `190A`. C’est ce qui nomme les ECU (`0xDB` = BMU ?) sans deviner. Toute réponse longue tronquée (`BUFFER FULL`) : rejeter et re-tenter une fois avec FC bridée `ATFCSD 30 0A 0A`.

**Étape 7 — DID Gen4 connus, avant tout balayage aveugle.** Sur l’ECU identifié batterie (prioritairement `0xDB`) : `2201D1`, `2201D0`, `220106`, `220178`, `220179`, puis `22010B`, `22022A`, `22F0D0` ; chacun lu **deux fois à quelques minutes d’intervalle**, avec SOC tableau de bord noté aux mêmes instants. Le mapping DID→`0xDE`/`0xE5` est absent des rapports : si un DID répond `7F 22 31` sur `0xDB`, un essai sur `0xDE`/`0xE5` est journalisable comme hypothèse, pas comme fait.

**Étape 8 — Seulement si le temps et le 12 V le permettent : amorce de balayage borné.** Plage `F100`–`F1FF` sur l’ECU batterie, un DID par requête, tranches ≤ 500 DID reprenables, `ATST64`/`ATST7D`. Session étendue (`1003`, keepalive `3E80` toutes les 2 s, retour `1001` explicite, un seul ECU) **uniquement** pour retester les DID ayant donné `7F 22 7F`/`7E`/`33` en session par défaut (délai S3 ~5 s = filet de sécurité). Jamais `27`.

**Critères d’arrêt immédiat de la tranche (R3) :** 3 `CAN ERROR` consécutifs ; `ATRV` < 12,0 V hors READY ; NRC `78` en boucle ; nouveau voyant/message au combiné ou tout comportement anormal ; démarrage thermique en espace clos (prévenu par la Phase 0). Fin de session : `1001` si une session étendue a été ouverte, lecture DTC finale, retrait de l’adaptateur.

**Ce que chaque issue permet de conclure :**

| Observation | Conclusion | Suite |
|---|---|---|
| `2101` → `61 01…` multi-trames sur `762` | Héritage Mitsubishi partiel contre toute attente | Décoder prudemment (SOC item 0, tension pack items 8+9) et confronter au pack 350 V : si la tension décodée tombe près de la réalité, tester `2102` sans présumer 80 cellules ; rien n’est « vérifié » sans corrélation |
| `0xDB` répond en 29 bits, DID `F1xx` + DID Gen4 positifs et **variant** entre deux lectures | Voie principale confirmée sur ce véhicule ; gateway ne bloque pas la lecture de base | Corréler sur charge/décharge étalonnée avant de figer la moindre équation (R2) ; construire les décodeurs |
| `7F 22 33` sur certains DID | Ces DID **existent** mais sont verrouillés | Consigner « existe-verrouillé », ne jamais tenter `0x27` ; v1 = légiféré + DID ouverts |
| `7F 22 31` généralisé mais `F1xx` répond | Bonne adresse/session, cartographie communautaire fausse ou partielle pour ce véhicule/marché | Balayage borné méthodique (étape 8) aux sessions suivantes |
| `7F 22 7F`/`7E` | Service/DID peut-être réservé à la session étendue | Liste « à retester en `1003` », un ECU à la fois |
| Légiféré parfait, phases UDS en `NO DATA` uniforme (ou seul l’ECU moteur répond) | Symptôme compatible gateway **ou** artefact d’adaptateur/en-têtes — `NO DATA` ≠ absence | Répéter avec l’adaptateur de référence STN/Classic et en-têtes vérifiés avant de conclure ; si reproduit proprement, c’est un **résultat documenté** (filtrage du non-légiféré), pas un mur à contourner en v1 |
| Silence aussi en légiféré 29 bits | Problème plus bas : protocole, syntaxe `ATCP`/`ATSH` du clone, état véhicule | Revenir à l’étape 1/matrice de capacités ; aucune conclusion véhicule |

## E. Inconnues résiduelles

**Bloquantes pour la v1 (à lever sur le véhicule ou par le choix d’adaptateur) :**

- `0xDB` est-il bien le BMU du 2024 nord-américain cible, et répond-il comme le 2023 documenté ? (R2/R3 — toute la v1 en dépend.)
- Politique gateway réelle par service/DID : `0x22` en session par défaut partout, ou `1003`/NRC `33` sur une partie, `0x19` filtré ? (R2 inconnue 3, R3.)
- Validité des DID communautaires et de leurs échelles sur ce VIN : équation `2201D1` (`B` non défini), signification exacte de `220106`/`220178`/`220179`/`22010B`/`22022A`/`22F0D0` — formules **absentes des rapports** au-delà des étiquettes.
- Existence d’un DID de capacité/SOH : non trouvé à ce jour ; bloquant pour toute promesse « santé batterie », gérable pour une v1 découverte+SOC/tensions.
- Tensions de cellules individuelles : seul min/max est évoqué en Gen4 ; une plage de tensions par cellule est inconnue. Topologie réelle (nombre de cellules/capteurs de température) non publiée.
- Capacité réelle de l’adaptateur qui sera utilisé (29 bits, `ATFCS*`, tampon BLE) — modèle exact absent des rapports ; sans adaptateur de référence, aucun silence n’est interprétable.

**Gérables (méthode connue, réponses attendues des étapes 1-7) :**

- Le légiféré répond-il aussi en 11 bits `7DF` ? Inventaire complet des répondants `7E8`–`7EF` et `18DAF1xx`, Mode 09 complet ?
- Identité de `0xDE` et `0xE5` (chargeur ? PHEV-ECU ?) via les DID `F187`/`F189`/`F191`.
- Températures pack via diagnostic : décodage inconnu **sur les deux générations** (R1) — candidat naturel du balayage DID (réponses qui varient lentement), aucune formule « octet − 40 » à inventer.
- Présentation PCI du firmware de l’adaptateur choisi, syntaxe exacte des en-têtes 29 bits, fidélité octet-pour-octet de l’émulateur face au réel : réglés par journal brut + rejeu.
- Licence d’ELM327-emulator et libellés SDK/patchs du jour d’installation : vérifications ponctuelles (R4).

**Plus tard (hors v1) :**

- Contextes de réponse (READY vs charge AC vs CHAdeMO vs contact coupé), cadence de polling sûre à long terme et endormissement/12 V en usage continu ; foreground service si arrière-plan un jour.
- Variantes Europe/Japon 22,7 kWh (2025+) : mêmes DID, autres échelles ?
- Historique kilométrage/cycles/capacité (côté MUT ancien : items 400+ sans décodage OBD public ; équivalent Gen4 inconnu) ; les « cycles » façon Watchdog resteraient une estimation d’app.
- Fournisseur/chimie des cellules 20 kWh : aucune source ; seule une doc d’atelier GN ou l’étiquette du pack trancherait.
- Courant batterie : formule ancienne inutilisable (R1) et équivalent Gen4 non identifié dans les rapports — à découvrir parmi les DID longs qui changent vite, ou absent.

**Phrase de cadrage pour la spec :** construire un outil de découverte 29 bits/`0x22` derrière passerelle, cœur Kotlin pur prouvé sans voiture par émulateur et rejeu, liste blanche lecture seule avec exception fermée `2101/2102` vers `761`, journal JSONL brut systématique — et ne promouvoir aucun décodage Gen4 au-delà du statut « piste communautaire » tant que la première session (étapes 1-7) ne l’a pas corrélé au tableau de bord du véhicule cible.