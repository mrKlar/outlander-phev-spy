# M2 — Transports Bluetooth réels et écran de session : plan d'implémentation

> **Pour les agents exécutants :** chaque tâche est autonome, cycle test d'abord quand la logique est testable en JVM, et se termine par une preuve exécutée (tests relancés par le meneur et, pour la tâche B, installation réelle sur le Pixel avec capture). `AGENTS.md`, la spec et le présent plan font foi.

**But :** brancher le cœur `:protocol` (M1, 124 tests verts) à de vrais adaptateurs ELM327 — BLE et Bluetooth Classic — avec un écran Android de session permettant au propriétaire de se connecter, de lancer les phases 1 à 7 du scan et d'exporter le journal JSONL, le tout toujours derrière la liste blanche lecture seule.

**Architecture :** logique de sélection des profils BLE en pur JVM dans `:protocol` (testable) ; transports et scanner Android dans `:transport-android` (Kable pour le BLE, RFCOMM natif pour le Classic) ; état et écrans Compose dans `:app`. Aucun accès réseau. Le journal de session est un `FileJournal` sous `filesDir/sessions/`, exportable par partage Android.

**Tech Stack :** Kotlin 2.4.21, AGP 9.4.1, Compose BOM 2026.09.00, Kable 0.45.0 (Apache-2.0, relevée en M0), kotlinx-coroutines 1.11.0.

**Spec :** `docs/superpowers/specs/2026-10-29-outlander-phev-spy-design.md`, sections 4 (modules, transports, flux), 5 (lecture seule), 6 (interrogation), 7 (première session), 9 (journal), 13 (jalon M2).

## Contraintes globales

- Lecture seule stricte inchangée : toute émission non-AT passe par `UdsClient`/`ObdClient` et le `RequestGuard`. La console AT brute n'envoie que des commandes AT/ST ; toute saisie hexadécimale passe par les clients gardés.
- `:protocol` reste Kotlin/JVM pur. Aucune API Android n'y entre.
- Versions uniquement dans `gradle/libs.versions.toml` (ajout : `kable = "0.45.0"`, `androidx-lifecycle` pour `viewmodel-compose` si requis, relevée au catalogue le jour J).
- Permissions : `BLUETOOTH_SCAN` (`neverForLocation`, déjà au manifest) et `BLUETOOTH_CONNECT`, demandées à l'exécution avant tout scan/connexion ; sans elles, l'écran explique et n'émet rien.
- Aucune émission pendant les tests automatisés : la vérification bout-en-bout sur adaptateur réel est manuelle, guidée, avec la première sonde limitée (init + légiféré) — aucune phase de scan complète sans le propriétaire devant l'auto.
- Vérification : `./gradlew :protocol:test` puis `./gradlew :app:assembleDebug` verts ; installation réelle sur le Pixel 8 Pro via le portable `zen` (SSH/Tailscale/adb) et capture d'écran lue par le meneur.

## Tâche A — Transports réels + sélection de profil BLE

**Fichiers :** `protocol/.../transport/BleProfiles.kt` (+ test JVM), `transport-android/.../ClassicSppTransport.kt`, `transport-android/.../BleTransport.kt`, `transport-android/.../AndroidElmScanner.kt`, `transport-android/.../AndroidTransportFactory.kt`, catalogue et `transport-android/build.gradle.kts` (dép. Kable + `:protocol`).

- `BleProfiles` (pur JVM) : table de couples connus (FFE0/FFE1 ; 18F0 + 2AF0/2AF1 ; Nordic UART 6E400001/2/3 ; ISSC 49535343-FE7D-4AE5-8FA9-9FAFD205E455 et sa caractéristique 49535343-1E4D-4BD9-BA61-23C647249616) et fonction `choose(services)` : d'abord un couple connu complet (notify + write ou write-without-response), sinon premier service offrant notify et write (même caractéristique acceptée). Types d'entrée simples (UUID en `String` normalisée minuscule, ensemble de propriétés en enum) pour être testés sans Android.
- `ClassicSppTransport` : RFCOMM `00001101-0000-1000-8000-00805F9B34FB`, `cancelDiscovery()` avant `connect()`, lecture `Dispatchers.IO` en `callbackFlow`, écriture synchronisée avec flush, `close()` idempotent. Fabrique depuis une adresse MAC.
- `BleTransport` (Kable) : connexion depuis l'`Advertisement` gardé par la fabrique, `discoverServices()`, sélection par `BleProfiles`, MTU demandé 247 puis repli, notifications → `incoming` (chunks tels quels), écritures découpées en `(mtu-3)` en `withoutResponse` si la caractéristique le supporte sinon `withResponse`, jamais de framing métier (le `>` ELM fait foi côté `:protocol`).
- `AndroidElmScanner` : `pairedClassicDevices()` depuis `bondedDevices` (liste vide et aucun crash si adaptateur absent/éteint ou permission manquante — le contrôle d'erreur est consigné, pas d'exception) ; `scan()` BLE via le scanner Kable (dédupliqué par identifiant, nom ou adresse en libellé).
- `AndroidTransportFactory` : mappe `ElmDevice` → transport (MAC Classic ; dernier advertisement BLE vu, conservé en mémoire de processus). Si l'appareil BLE n'est plus en mémoire : erreur claire « relancer le scan ».
- Tests JVM : `BleProfilesTest` (couple connu prioritaire, repli générique, aucun candidat → null, normalisation de casse d'UUID). Le reste est prouvé par compilation + installation (tâche B) ; BLE/Classic réels se valident à la première session adaptateur (matrice de capacités).

## Tâche B — Écran de session

**Fichiers :** `app/.../MainActivity.kt`, `app/.../SessionViewModel.kt`, `app/.../SessionUiState.kt` (ou état dans le ViewModel), `AndroidManifest.xml` (FileProvider), `res/xml/file_paths.xml`, dépendances (lifecycle-viewmodel-compose) au catalogue.

- Écrans/états (un seul écran scrollable suffit) : (1) **Adaptateur** : boutons « Appareils appairés (Classic) » et « Balayer (BLE) », liste réactive, état des permissions avec bouton « Autoriser Bluetooth » ; tap = connexion. (2) **Session** : après connexion, `ElmSession.initialize()` puis affichage de la matrice de capacités (ATI, STN oui/non, tension 12 V, commandes OK/refusées, protocole détecté). (3) **Avant scan** : checklist de la spec §7.1 en cases à cocher (dehors/ventilé, stationné, READY), interrupteur « Véhicule READY », champs vérité terrain (SOC affiché %, autonomie km) versés au journal (`kind=note`, `phase=verite-terrain`).
- Actions (toutes gardées) : « Lire tension adaptateur » (`ATRV`), console AT (champ texte : seules les commandes commençant par AT/ST partent en `transact` ; un préfixe `22` + 4 hex est routé en `readDataByIdentifier` sur la cible choisie par sélecteur DB/DE/E5 ; toute autre saisie est refusée **dans l'UI** avec message), « Sondes Gen4 » (`gen4Probes()`), « Lancer le scan (phases 1-7) » (désactivé tant que les 3 cases ne sont pas cochées), « Arrêter » (annule le job de scan), « Exporter le journal » (partage du fichier courant via FileProvider).
- Sortie : zone défilante des événements (`ScanEvent`) et des 200 dernières lignes de journal (rendu JSONL abrégé), signaux connus affichés uniquement avec leur statut (« piste communautaire »).
- Journal : `FileJournal(filesDir/sessions/session-<horodatage>.jsonl, sessionId=<même horodatage>)` créé à la connexion, fermé à la déconnexion ; le chemin est affiché.
- Le ViewModel survit à la rotation ; la fermeture de l'app ferme session et transport (`onCleared`).

## Tâche C — Installation et preuves sur le Pixel 8 Pro

- `./gradlew :protocol:test` (attendu : 124 + nouveaux tests JVM verts) puis `./gradlew :app:assembleDebug` verts.
- Installation via `zen` : `scp` de l'APK puis `adb install -r` ; lancement `am start` ; capture d'écran de l'écran « Adaptateur » lue par le meneur (état permissions et liste vide acceptables dans un salon ; la connexion à un adaptateur se prouve à la première séance près de l'auto).
- Commit par tâche, messages en français. Aucun journal réel, aucune capture d'écran du téléphone versée au dépôt (données personnelles).

## Critères de sortie M2 (spec §13)

Connexion réelle aux adaptateurs du propriétaire en Classic et en BLE (au moins celui dont il dispose ; l'autre voie est compilée et sa logique de sélection testée en JVM) ; matrice de capacités affichée ; aucune émission hors liste blanche depuis l'UI (console comprise).
