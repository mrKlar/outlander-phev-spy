# M0+M1 — Toolchain et cœur protocole : plan d'implémentation

> **Pour les agents exécutants :** chaque tâche est autonome, suit un cycle test d'abord (rouge → vert → commit), et se termine par un livrable testable seul. Lire la spec et `AGENTS.md` avant toute tâche. Exécution par sous-agents, revue du meneur entre tâches.

**But :** prouver le cœur `:protocol` (session ELM, ISO-TP, UDS lecture seule, journal, décodeurs, plan de scan) en JVM pure, sur oracles publiés, émulateur et rejeu — avant qu'un seul octet parte vers le véhicule — et livrer un APK squelette installable sur le Pixel 8 Pro.

**Architecture :** trois modules Gradle (`:protocol` JVM pur, `:transport-android`, `:app` Compose). Ce plan couvre M0 (toolchain + squelette) et M1 (`:protocol` complet + intégration émulateur). M2 (transports réels, UI) fera l'objet d'un plan séparé.

**Stack :** Kotlin 2.4.21, Gradle 9.7.1, JDK Temurin 21 (cible bytecode 17), AGP 9.4.1, Compose BOM 2026.09.00, coroutines, kotlin.test/JUnit5. Aucune autre dépendance runtime dans `:protocol` hors coroutines.

**Spec :** `docs/superpowers/specs/2026-10-29-outlander-phev-spy-design.md` (faire foi ; rapports dans `docs/recherche/`).

## Contraintes globales (issues de la spec et de l'environnement)

- Lecture seule stricte : liste blanche de la spec §5.1, liste noire §5.2, exception fermée `2101`/`2102`→`761` §5.3. Aucune tâche ne peut l'affaiblir.
- `:protocol` reste Kotlin/JVM pur (JDK seul, `java.io`/`java.net` permis). Zéro API Android.
- Versions uniquement dans `gradle/libs.versions.toml`.
- Toolchain contenue dans le dépôt : tout vit sous `.toolchain/` (gitignoré) — JDK, Gradle, Android SDK, `GRADLE_USER_HOME`, `ANDROID_USER_HOME`. Aucune écriture hors du dépôt. Les builds passent par `scripts/dev-env.sh` (sourcé) qui exporte `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `GRADLE_USER_HOME`, `ANDROID_USER_HOME` et le `PATH`.
- Documentation et messages de commit en français ; préfixes conventionnels (`feat`, `test`, `chore`, `docs`).
- Aucun VIN réel commité ; `*.jsonl` reste ignoré sans exception (fixture anonymisée = `git add -f` délibéré après relecture).
- Rien n'est émis vers un véhicule dans ce plan : aucun adaptateur requis, seuls tests JVM et émulateur local.
- Vérification avant de déclarer une tâche finie : `./gradlew :protocol:test` vert dans la session (plus `:app:assembleDebug` à la tâche 9).

## Carte des fichiers

| Fichier | Responsabilité | Tâche |
|---|---|---|
| `scripts/dev-env.sh` | Variables d'environnement de la toolchain contenue | 0 |
| `docs/m0-bootstrap.md` | Versions réellement installées vs épinglées, écarts consignés | 0, 9 |
| `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle/wrapper/*` | Scaffold Gradle + catalogue de versions | 1 |
| `protocol/build.gradle.kts` | Module JVM pur (Kotlin JVM, coroutines, kotlin.test) | 1 |
| `protocol/src/main/kotlin/ca/mrklar/outlander/protocol/transport/ElmTransport.kt` | `ElmTransport`, `ElmScanner`, `ElmDevice`, `ElmTransportKind` | 2 |
| `protocol/src/main/kotlin/ca/mrklar/outlander/protocol/transport/ReplayTransport.kt` | Rejeu de chunks RX capturés, enregistrement des écritures | 2 |
| `protocol/src/main/kotlin/ca/mrklar/outlander/protocol/transport/TcpElmTransport.kt` | Transport TCP (émulateur/dev uniquement) via `java.net` | 8 |
| `protocol/src/main/kotlin/ca/mrklar/outlander/protocol/elm/ElmCodec.kt` | Tokeniseur flux ELM → `ElmResponse` classée | 3 |
| `protocol/src/main/kotlin/ca/mrklar/outlander/protocol/elm/ElmSession.kt` | Acteur sérialisé, init §6.1, `AdapterCapabilities` | 4 |
| `protocol/src/main/kotlin/ca/mrklar/outlander/protocol/isotp/IsoTp.kt` | Parse des lignes CAN + réassemblage tolérant | 5 |
| `protocol/src/main/kotlin/ca/mrklar/outlander/protocol/uds/RequestGuard.kt` | Liste blanche, refus avant émission | 6 |
| `protocol/src/main/kotlin/ca/mrklar/outlander/protocol/uds/UdsClient.kt` | `EcuChannel`, client UDS + OBD légiféré brut | 6 |
| `protocol/src/main/kotlin/ca/mrklar/outlander/protocol/journal/Journal.kt` | Schéma JSONL v1, émetteur JSON, journals mémoire/fichier, anonymisation VIN | 7 |
| `protocol/src/main/kotlin/ca/mrklar/outlander/protocol/vehicle/Signals.kt` | `SignalDefinition`, registre, décodeur affine, définitions Gen4 + anciennes | 7 |
| `protocol/src/main/kotlin/ca/mrklar/outlander/protocol/scan/ScanPlan.kt` | Phases 1-8 de la spec §7, rythme, critères d'arrêt | 8 |
| `protocol/src/test/kotlin/...` (miroir des packages) | Tests unitaires, golden files, propriété liste blanche, session simulée | 2-8 |
| `protocol/src/test/resources/fixtures/legacy-2101-anko.txt` | Trame oracle ancien modèle (verbatim du rapport 01) | 5 |
| `scripts/run-emulator-it.sh`, `scripts/elm-emulator/` | Harnais d'intégration ELM327-emulator via TCP | 8 |
| `transport-android/build.gradle.kts`, `app/build.gradle.kts`, `app/src/main/...` | Squelettes Android minimaux (aucune logique métier) | 9 |

Écarts assumés vs spec, tranchés ici : (a) `TcpElmTransport` vit dans `:protocol` (`java.net` est disponible des deux côtés ; les tests JVM en ont besoin) plutôt que dans `:transport-android` ; (b) dans `SignalDefinition`, `byteOffset` compte à partir du premier octet **après** l'en-tête de réponse positive (`62 XX XX` pour `0x22`, `61 01` pour le legacy), et `scale`/`offset`/`byteOffset`/`byteLength` sont nullables — une définition sans équation décode `null` (spec §8.1 : décodage brut journalisé) ; (c) M0 se termine sur `assembleDebug` vert ici, l'`adb install` sur le Pixel se fera avec le propriétaire à la maison (procédure en tâche 9).

---

### Tâche 0 — Toolchain contenue et vérification des versions

**Livrable :** `.toolchain/` fonctionnel (JDK 21, Gradle 9.7.1, Android SDK), `scripts/dev-env.sh`, `docs/m0-bootstrap.md`, `.toolchain/` ajouté à `.gitignore`.

- [ ] **Vérifier l'existence des versions épinglées** (chacune doit répondre HTTP 200 ; sinon prendre le correctif stable immédiatement supérieur du même train et consigner l'écart dans `docs/m0-bootstrap.md`) :
  - `curl -sI https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.4.1/gradle-9.4.1.pom`
  - `curl -sI https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/2.4.21/kotlin-gradle-plugin-2.4.21.pom`
  - `curl -sI https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2026.09.00/compose-bom-2026.09.00.pom`
  - `curl -s https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core/maven-metadata.xml` → relever la dernière version stable (≥ 1.10.2) ; c'est elle qui entre au catalogue.
  - `curl -sI https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip` (sinon relever le numéro courant sur la page officielle des command-line tools).
- [ ] **Installer** dans `.toolchain/` : JDK via `curl -L https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse`, Gradle via `https://services.gradle.org/distributions/gradle-9.7.1-bin.zip`, cmdline-tools dézippés en `.toolchain/android-sdk/cmdline-tools/latest/`. Consigner les `sha256sum` des archives dans `docs/m0-bootstrap.md`.
- [ ] **SDK** : `sdkmanager --list` pour confirmer les libellés, accepter les licences (`yes | sdkmanager --licenses`), installer `"platform-tools" "platforms;android-37.0" "build-tools;37.0.0"`.
- [ ] **Écrire `scripts/dev-env.sh`** (chemins relatifs à la racine du dépôt) et ajouter `/.toolchain/` à `.gitignore`.
- [ ] **Vérifier** : `source scripts/dev-env.sh && java -version && gradle --version && sdkmanager --list_installed` → JDK 21 Temurin, Gradle 9.7.1, les 3 paquets SDK listés.
- [ ] **Commit** : `chore: toolchain contenue (JDK 21, Gradle 9.7.1, SDK 37) et dev-env` — uniquement `scripts/dev-env.sh`, `docs/m0-bootstrap.md`, `.gitignore`.

### Tâche 1 — Scaffold Gradle, `:protocol` vide vert

**Livrable :** `./gradlew :protocol:test` vert avec un test de fumée. **Fichiers :** racine Gradle, catalogue, wrapper, `protocol/build.gradle.kts`, `protocol/src/test/kotlin/ca/mrklar/outlander/protocol/SmokeTest.kt`.

- [ ] Catalogue (`gradle/libs.versions.toml`) : `kotlin = "2.4.21"`, `agp = "9.4.1"`, `compose-bom = "2026.09.00"`, `kotlinx-coroutines` = version relevée en tâche 0 ; plugins `kotlin-jvm`, `kotlin-compose`, `android-application`, `android-library` ; librairies `kotlinx-coroutines-core`, `kotlinx-coroutines-test`.
- [ ] `settings.gradle.kts` : `rootProject.name = "outlander-phev-spy"`, `include(":protocol")` seul (les modules Android arrivent en tâche 9). `gradle.properties` minimal (UTF-8, `org.gradle.configuration-cache=false` pour éviter les surprises AGP 9 en M0).
- [ ] `protocol/build.gradle.kts` : plugin Kotlin JVM, `jvmToolchain(21)`, cible bytecode 17 (`JvmTarget.JVM_17`), dépendances coroutines (+ test), `tasks.test { useJUnitPlatform() }`, dépendance de test `kotlin("test")`.
- [ ] Générer le wrapper avec le Gradle de `.toolchain` : `gradle wrapper --gradle-version 9.7.1`.
- [ ] **Rouge→vert** : écrire `SmokeTest` (`assertEquals(4, 2 + 2)`), lancer `./gradlew :protocol:test`, attendu : vert.
- [ ] **Commit** : `chore: scaffold Gradle et module :protocol vide`.

### Tâche 2 — Interfaces transport + ReplayTransport

**Livrable :** interfaces de la spec §4.3 compilées + `ReplayTransport` testé. **Produit :** `ElmTransport { val incoming: Flow<ByteArray>; suspend fun write(ByteArray); suspend fun close() }`, `ElmTransportKind { BLE, CLASSIC_SPP, TCP, REPLAY }`, `ElmDevice(id, displayName, kind)`, `ElmScanner`, `ReplayTransport(chunks: List<ByteArray>, gateOnWrite: Boolean = true)` exposant `val written: List<ByteArray>`.

- [ ] **Tests d'abord** (`ReplayTransportTest`) : (1) les chunks arrivent dans l'ordre, inchangés, après une écriture quand `gateOnWrite=true` ; (2) sans écriture, aucun chunk n'est émis sous `withTimeoutOrNull(100)` ; (3) `written` enregistre chaque écriture octet pour octet ; (4) après `close()`, le flow se termine.
- [ ] Implémenter avec un `Channel` illimité alimenté à la première écriture (ou par une fonction `pump()` publique si `gateOnWrite=false`).
- [ ] `./gradlew :protocol:test` vert. **Commit** : `feat(protocol): interfaces transport et ReplayTransport`.

### Tâche 3 — Codec ELM (tokeniseur + classification)

**Livrable :** `ElmCodec` transformant un flux d'octets quelconque en `ElmResponse` complètes (délimitées par le prompt `>`).
**Produit :** `ElmStatus { POSITIVE, NRC, NO_DATA, TIMEOUT, ELM_ERROR, BUFFER_FULL }` ; `ElmResponse(command, rawText, lines, status, elmTokens, durationMs)` (spec §4.3) ; `class ElmCodec { fun feed(chunk: ByteArray): List<String> ; fun classify(command: String, rawText: String, durationMs: Long): ElmResponse }` où `feed` retourne les blocs terminés par `>` (texte brut verbatim, sans le `>`), tolérant un `>` en milieu de chunk ou en début de chunk suivant.

Règles de classification (exactes) :
- Lignes = découpage sur `\r`/`\n`, lignes vides jetées. Si la première ligne égale la commande envoyée (écho), elle est retirée des `lines` mais reste dans `rawText`.
- Tokens reconnus mot pour mot après trim : `SEARCHING...`, `NO DATA`, `CAN ERROR`, `BUFFER FULL`, `?`, `OK`, `STOPPED`, `UNABLE TO CONNECT`, `FB ERROR`, `DATA ERROR`, `<DATA ERROR`, lignes commençant par `BUS INIT:`. Tout token rencontré va dans `elmTokens` et sort des `lines`.
- Statut, par priorité : `BUFFER FULL` présent → `BUFFER_FULL` ; sinon lignes de données non vides → `POSITIVE` (le NRC se classe plus haut, couche UDS ; `ElmStatus.NRC` n'est posé que si l'unique ligne de données, espaces retirés, commence par `7F` — cas des réponses sans en-têtes) ; sinon `NO DATA` présent → `NO_DATA` ; sinon `OK` seul → `POSITIVE` ; sinon → `ELM_ERROR` (inclut `?`, `CAN ERROR`, `UNABLE TO CONNECT`, erreurs).

- [ ] **Golden files d'abord** (`ElmCodecTest`, vecteurs en code) : réponse simple `7E8 06 41 00 98 3B 80 13\r>` ; `SEARCHING...\r` suivi de données ; `NO DATA\r>` ; `CAN ERROR\r>` ; `BUFFER FULL\r>` après lignes partielles ; `?` ; écho `0100\r4100983B8013\r>` (commande `0100`, écho retiré) ; multi-ECU deux en-têtes `7E8…`/`7E9…` ; prompt reçu seul dans un chunk ; deux réponses dans un même chunk. Chacun asserté sur `status`, `lines`, `elmTokens` exacts.
- [ ] Implémenter (`feed` à accumulateur `StringBuilder`, décodage ISO-8859-1 pour ne perdre aucun octet).
- [ ] Vert. **Commit** : `feat(protocol): codec ELM et classification des réponses`.

### Tâche 4 — `ElmSession` sérialisée + séquence d'init + matrice de capacités

**Livrable :** session point d'émission unique côté ELM, init de la spec §6.1, journalisation systématique. **Dépend :** tâches 2, 3 et 7 (journal) — faire la 7 avant celle-ci si l'ordre libre le permet, sinon brancher d'abord sur `InMemoryJournal` minimal de la tâche 7. Ordre d'exécution imposé : 0, 1, 2, 3, **7a** (journal seul), 4, 5, 6, 7b (signaux), 8, 9.
**Produit :** signatures de la spec §4.3 (`ElmSession.transact(command, timeoutMs, continuesWhile)`, `initialize(): AdapterCapabilities`, `AdapterCapabilities(ati, sti, atrvVolts, okCommands, deniedCommands, detectedProtocol)`).

Comportement exact :
- Un `Mutex` sérialise : jamais deux `transact` en vol. Chaque `transact` écrit `command + "\r"`, collecte via `ElmCodec` jusqu'au prompt, borne l'attente totale à `timeoutMs` (`withTimeoutOrNull` → réponse synthétique `TIMEOUT` avec le brut reçu jusque-là). Les chunks RX/TX horodatés (monotone injectable, `() -> Long`) sont conservés pour le journal.
- `continuesWhile` : après une première réponse classée, si le prédicat retourne vrai, la session continue de collecter le bloc suivant **sans réécrire**, concatène les lignes, attente bornée par le même budget `timeoutMs` global. Au plus 10 blocs enchaînés (garde anti-boucle `78`).
- Chaque transaction (y compris les AT de l'init) est appendue au journal avant retour à l'appelant : `kind=at` si commande `AT`/`ST`, sinon rempli par l'appelant via un paramètre `kind`/`phase` transmis à `transact` (défauts `manuel`/`at`).
- `initialize()` joue, dans l'ordre, avec `OK`/`?` consignés : purge (drain non bloquant 300 ms du flux entrant), `ATZ` (timeout 2 500 ms), `ATI`, `STI`, `ATRV`, `ATE0`, `ATL0`, `ATS1`, `ATH1`, `ATCAF1`, `ATCFC1`, `ATAT0`, `ATSTFF`, `ATCEA`, puis `ATSP0` et un `0100` d'auto-détection, puis `ATDPN` (stocké dans `detectedProtocol`). `ATRV` est parsé en volts (`"12.4V"` → 12.4, null si illisible). `okCommands`/`deniedCommands` reçoivent chaque commande de configuration testée ; `ATCP18` et `ATSP7` sont eux-mêmes testés par le plan de scan quand il en a besoin (tâche 8), pas ici.
- `fun setTimeoutHex(stHex: String)` : petit utilitaire journalisé envoyant `ATSTxx` (les phases passent de `FF` à `64`/`7D`).

- [ ] **Tests d'abord** avec un `ScriptedElmTransport` (source de test : attend une écriture, répond les chunks scriptés associés, échoue si écriture inattendue) : (1) init complète tous `OK` → capacités attendues, 15 lignes `at` au journal ; (2) `ATCEA` répond `?` → consigné dans `deniedCommands`, init continue ; (3) concurrence : 5 `transact` lancés en parallèle n'entrelacent jamais leurs écritures ; (4) timeout : aucun prompt → `TIMEOUT` en ≤ timeoutMs + marge ; (5) `continuesWhile` : premier bloc `…7F 22 78>` puis second bloc de données → une seule `ElmResponse` fusionnée, aucune réécriture entre les deux.
- [ ] Implémenter. Vert. **Commit** : `feat(protocol): session ELM sérialisée, init et matrice de capacités`.

### Tâche 5 — ISO-TP : parse des lignes CAN et réassemblage tolérant

**Livrable :** `isotp/IsoTp.kt` prouvé par la trame oracle de l'ancien modèle.
**Produit :** `data class CanFrame(val headerHex: String, val bytes: ByteArray)` ; `fun parseCanLines(lines: List<String>): List<CanFrame>` ; `sealed interface Reassembly { data class Complete(val payload: ByteArray); data class Incomplete(val expectedBytes: Int, val receivedBytes: Int); data class NotIsoTp(val payload: ByteArray) }` ; `fun reassemble(frames: List<CanFrame>): Map<String, Reassembly>` (clé = `headerHex`).

Règles de parse (format espacé, `ATS1` imposé par notre init) :
- Ligne `7E8 06 41 00 …` ou `18DAF1DB 03 62 …` : premier token de 3 ou 8 caractères hex = en-tête, reste = octets.
- Ligne indexée `0: 62 F1 90 …` / `1: …` : en-tête absent → `headerHex = ""`, l'ordre des lignes fait foi.
- Token non hex ou de longueur autre que 2 dans les octets → ligne ignorée (jamais d'exception).

Règles de réassemblage, par en-tête, dans l'ordre reçu :
1. Si le premier octet a un nibble haut `0x1` (First Frame PCI) : longueur totale `((b0 and 0x0F) shl 8) or b1`, charge = octets après les 2 octets PCI ; chaque trame suivante doit commencer par `0x2n` avec `n` séquentiel modulo 16 à partir de 1 (sinon `Incomplete`) ; complétude quand la charge cumulée atteint la longueur (tronquer l'excédent de remplissage).
2. Sinon si trame unique et nibble haut `0x0` et `(b0 and 0x0F) == bytes.size - 1` : Single Frame PCI → charge après l'octet PCI.
3. Sinon si trame unique et `b0 == bytes.size - 1` (présentation `ATCAF1` avec octet de longueur, sans PCI) : charge après l'octet de longueur.
4. Sinon : `NotIsoTp` charge brute telle quelle. Une charge incomplète n'est jamais retournée comme `Complete`.

- [ ] **Fixture oracle d'abord** : créer `protocol/src/test/resources/fixtures/legacy-2101-anko.txt` = copie verbatim des 8 lignes publiées (`docs/recherche/2026-10-29-01-ancien-outlander-phev-pid.md`, lignes 43-50 : `762 10 37 61 01 82 83 0F 8B` … `762 27 00 00 0F 8A 00 02 00`).
- [ ] **Tests** : (1) oracle → `Complete` de 55 octets commençant par `61 01`, octets d'index 29-30 du payload (= items 27+28 après `61 01`) égaux à `01 54` ; (2) Single Frame PCI `7E8 05 62 F1 90 4A 41` → charge `62 F1 90 4A 41` ; (3) présentation longueur `7E8 04 41 00 98 3B` → charge `41 00 98 3B` ; (4) multi-ECU : deux en-têtes entrelacés → deux entrées séparées ; (5) CF hors séquence (`21` puis `23`) → `Incomplete` ; (6) FF seule sans CF → `Incomplete(expected=55, received=6)` ; (7) lignes indexées `0:`/`1:` → concaténation dans l'ordre.
- [ ] Implémenter. Vert. **Commit** : `feat(protocol): réassemblage ISO-TP tolérant, oracle ancien modèle vert`.

### Tâche 6 — `RequestGuard` (liste blanche) + `UdsClient`/`ObdClient`

**Livrable :** l'unique point d'émission de requêtes non-AT, garde prouvée de façon exhaustive. **Dépend :** 4, 5.
**Produit :** `EcuChannel(txId: Long, rxId: Long, fcHeader: Long, extended29Bit: Boolean, functional: Boolean)` ; objets canaux prédéfinis dans `EcuChannel` (compagnon) : `LEGISLATED_11` (tx 0x7DF, rx 0=tout, functional=true), `FUNCTIONAL_29` (tx 0x18DB33F1, rx 0=tout, functional=true), `LEGACY_BMU` (0x761/0x762, fc 0x761), `physical11(tx,rx)`, `logical29(addr: Int)` → tx `0x18DA00F1 or (addr shl 8)`, rx `0x18DAF100 or addr`, fc = tx, extended=true, functional=false ; constantes `BMU_DB = logical29(0xDB)`, `ECU_DE`, `ECU_E5`. `UdsReply` de la spec §4.3. `RequestGuard.check(channel, request: ByteArray): GuardResult` (autorisé, ou refus typé avec motif).

Règles du garde (spec §5.1-5.3, ni plus ni moins) :
- Premier octet dans {`0x01,0x02,0x03,0x07,0x09,0x0A`} → autorisé (légiféré).
- `0x22` → autorisé, requête exactement 3 octets.
- `0x10` → autorisé si 2 octets et sous-fonction ∈ {`0x01,0x03`} **et** `channel.functional == false`.
- `0x19` → autorisé si sous-fonction ∈ {`0x01,0x02,0x0A`}.
- `0x3E` → autorisé si requête exactement `3E 00` ou `3E 80`.
- `0x21` → autorisé **uniquement** si canal == `LEGACY_BMU` et requête == `21 01` ou `21 02`.
- Tout le reste → refus. Le refus lève `RequestRefusedException` et est journalisé par l'appelant (événement, pas de transaction) ; rien n'est écrit.

`UdsClient(session)` : `suspend fun programChannel(channel)` émet (journalisé `kind=at`) selon le canal et ne reprogramme pas si le canal courant est identique : 11 bits → `ATSH` + hex3(tx), `ATCRA` + hex3(rx) (si `rx == 0` : aucun `ATCRA` émis, `cra=null` journalisé), canal physique → `ATFCSH` + hex3(fc), `ATFCSD300000`, `ATFCSM1` ; canal fonctionnel (`functional == true`) → `ATFCSM0` (flow control automatique de l'ELM : le répondant n'étant pas connu d'avance, aucun FC utilisateur ne peut être préprogrammé ; capacité multi-trames dégradée et journalisée comme telle) ; 29 bits → mêmes commandes avec les 3 octets bas espacés (ex. `ATSH DA DB F1`, `ATCRA DA F1 DB`, `ATFCSH DA DB F1`). `suspend fun setFlowControlData(hex: String)` pour la FC bridée (`300A0A`).
Méthodes (toutes : garde → programme → `transact` en hex sans espaces, majuscules → parse ISO-TP → classification) : `readDataByIdentifier(channel, did)`, `testerPresent(channel)`, `diagnosticSessionControl(channel, sub)`, `readDtc(channel, sub, mask: Int?)` (masque omis pour la sous-fonction `0x0A` : requête de 2 octets), `legacyRead2101()`, `legacyRead2102()` (condition « 2101 positif d'abord » imposée par le plan de scan, le garde impose le canal et l'octet exact). Classification : charge commençant par `7F <service> <nrc>` → `Negative` ; charge positive attendue (`0x62`+DID, `0x7E 00`, `0x50 <sub>`, `0x59 <sub>`, `0x61 01/02`) → `Positive(données après en-tête de réponse)` ; statut ELM `NO_DATA` → `NoData` ; `BUFFER_FULL`/`TIMEOUT`/`ELM_ERROR`/charge `Incomplete` → `ElmFailure`. `transact` reçoit un `continuesWhile` vrai tant que la charge partielle classée est un NRC `0x78`.
`ObdClient(session)` : `suspend fun request(channel, modePidHex: String): Map<String, UdsReply>` — garde sur le premier octet, réponse éclatée par en-tête (multi-ECU légiféré).

- [ ] **Test de propriété d'abord** (`RequestGuardTest`) : pour les 256 valeurs du premier octet, et pour `0x10/0x19/0x3E` les 256 sous-fonctions, sur 3 canaux (légiféré, physique 11, logique 29) : le verdict doit égaler une table de vérité écrite dans le test, indépendante de l'implémentation (ensembles littéraux repris de la spec §5). Cas nommés en plus : `2101` refusé hors `LEGACY_BMU`, `2103` refusé partout, `1003` refusé en fonctionnel, `27…`/`14…`/`2E…`/`04…`/`08…` refusés, `2201D1` autorisé, `22` seul (1 octet) refusé.
- [ ] **Tests `UdsClient`** (transport scripté) : programmation 29 bits émet exactement `ATSH DA DB F1`, `ATCRA DA F1 DB`, `ATFCSH DA DB F1`, `ATFCSD300000`, `ATFCSM1` avant `22F190` ; réponse `18DAF1DB 10 14 62 F1 90 …` multi-trames → `Positive` sans l'en-tête `62 F1 90` ; `7F 22 31` → `Negative(0x22, 0x31)` ; `7F 22 78` puis positive → une seule réponse `Positive` ; refus du garde → zéro octet écrit sur le transport (asserté sur le log d'écritures).
- [ ] Implémenter. Vert. **Commit** : `feat(protocol): liste blanche prouvée et client UDS lecture seule`.

### Tâche 7 — Journal JSONL v1 (7a) et signaux/décodeurs (7b)

**7a — Journal** (à faire avant la tâche 4). **Produit :** `JournalEvent` (champs de la spec §9.1 : `seq, tsWall, tsMonoMs, phase, kind, adapter, elm, vehicle, request, response, durationMs, retryOf, txChunks, rxChunks, note`), `DiscoveryJournal` + `InMemoryJournal` (tests) + `FileJournal(path, sessionId, clockWall, clockMono)` append + flush ligne à ligne, émetteur JSON maison (`journal/JsonWriter.kt` : échappement correct des chaînes, nombres, nulls ; aucune dépendance de sérialisation), `VinAnonymizer.anonymizeLine(line: String, vin: String): String` remplaçant les formes hex du VIN (espacée majuscule, compacte majuscule, compacte minuscule) par l'hex de `?`×17 (`3F`), et toute occurrence ASCII directe par `?????????????????`.

- [ ] **Tests** : (1) ligne produite pour un événement figé égale une chaîne JSON attendue exacte (ordre des champs du schéma, `schema:1`) ; (2) `FileJournal` sur fichier temporaire : 3 appends → 3 lignes relues, chacune parseable par un mini-extracteur du test sur `seq` (0,1,2) ; (3) anonymisation : ligne contenant le faux VIN `JA4J24A58RZ000001` en hex espacé → ne contient plus ni le VIN ni son hex, longueur de la zone remplacée inchangée ; (4) `status:"no_data"` conservé tel quel.
- [ ] Implémenter. Vert. **Commit** : `feat(protocol): journal JSONL v1 et anonymisation VIN`.

**7b — Signaux.** **Produit :** `SignalConfidence`, `SignalDefinition` (spec §8.1 + champs d'équation nullables), `object AffineDecoder : SignalDecoder` (`data` = charge après en-tête de réponse ; assemblage big-endian non signé sur `byteLength` octets à `byteOffset` ; `null` si équation incomplète ou données trop courtes), `object KnownSignals` : définitions de la spec §8.3 sur `BMU_DB` (`bmu.soc.did01d1`, `bmu.socmax.did01d0`, `bmu.hvVoltage.did0106`, `bmu.cellMax.did0178`, `bmu.cellMin.did0179`, `bmu.unknown.did010b`, `bmu.unknown.did022a`, `bmu.unknown.didf0d0`) toutes `COMMUNAUTAIRE_NON_VALIDE`, sans équation (champs d'équation nuls), y compris `01D1` (l'octet `B` n'est pas défini : ne pas encoder `B*0.64−19.2`), source `"spec §8.3 / R2 profil communautaire oct. 2025"` ; définitions anciennes sur `LEGACY_BMU` requête `2101` (SOC item 0 : offset 0, 1 octet, ×0.5, −5, `%` ; tension pack items 8-9 : offset 8, 2 octets, ×0.1, `V` ; capacité items 27-28 : offset 27, 2 octets, ×0.1, `Ah` ; charge restante items 29-30 : offset 29, 2 octets, ×0.1, `Ah`) source `"spec §3.1 F4 / R1 anko"`, statut `COMMUNAUTAIRE_NON_VALIDE`.

- [ ] **Tests** : (1) pipeline complet fixture → ISO-TP → données après `61 01` → définition capacité = 34.0 Ah exactement, SOC = (0x82/2 − 5) = 60.0 ; (2) définition sans équation (`did01d1`) → `null` sur données arbitraires ; (3) données trop courtes → `null`, pas d'exception ; (4) le registre ne contient aucune constante interdite : test qui échoue si une définition embarque `300` V/80 cellules comme échelle ou nom (garde-fou symbolique : aucun `id` ne contient `soh`).
- [ ] Implémenter. Vert. **Commit** : `feat(protocol): définitions de signaux data-driven et décodeur affine`.

### Tâche 8 — Plan de scan (phases 1-8) + transport TCP + intégration émulateur

**Livrable :** le déroulé complet de première session de la spec §7, exécuté de bout en bout contre un transport scripté et, en intégration gardée, contre ELM327-emulator réel via TCP. **Dépend :** 4, 6, 7b.
**Produit :** `ScanConfig(pacingMs: Long = 150, identificationTimeoutMs: Long = 1500, sweepTimeoutMs: Long = 600, atrvFloorVolts: Double = 12.0)` ; `ScanEvent` (progression : phase commencée/terminée, refus, arrêt et sa cause) ; `class ScanExecutor(session, capabilities, uds, obd, journal, config, clockMono, delayFn)` avec `fun runPhases(beforeStep: (suspend () -> Unit)? = null): Flow<ScanEvent>` et `suspend fun sweepDidRange(channel, range: IntRange, startAfter: Int? = null, maxCount: Int = 500)`.

Déroulé exact (spec §7.2 ; chaque requête passe par les clients des tâches 6, donc par le garde ; le rythme `pacingMs` est appliqué entre transactions véhicule) :
1. `init` : déjà fait par l'appelant (`ElmSession.initialize`) ; l'exécuteur reçoit les `AdapterCapabilities`.
2. `legifere-11` puis `legifere-29` : passage protocole (`ATSP6` / `ATSP7`+`ATCP18` — `ATCP` ou `ATSP7` refusés par l'adaptateur ⇒ phase 29 bits sautée avec événement journalisé, jamais d'échec de session), canal fonctionnel (`ATSH7DF` / en-tête `18DB33F1` via `logical29` fonctionnel dédié tx `0x18DB33F1`, rx 0=toutes réponses `18DAF1xx` éclatées par en-tête), requêtes `0100`, chaînage `0120/0140/0160/0180` tant que le dernier bit du masque le commande, `0900 0902 0904 0906 090A 03 07 0A 0101 0142`, chaque PID Mode 01 déclaré une fois, `015B` seulement si son bit est déclaré dans `0140`. Émettre un `ScanEvent` par ECU répondant distinct.
3. `heritage-2101` : `ATSP6`, un seul `legacyRead2101()` ; si `Positive`, un `legacyRead2102()` ; sinon abandon consigné.
4. `presence-11` : paires de la spec §6.3 dans l'ordre (normatives 7E0-7E7/+8, puis `797→79A`, `79B→7BB`, `784→78C`, `743→763`, `740→760`) : `testerPresent`, à défaut `diagnosticSessionControl(01)` ; jamais `3E80` en sonde.
5. `cible-29` : pour 0xDB, 0xDE, 0xE5 : physique (`testerPresent`, puis `22F190`) ; si `NoData` sur les deux, variante fonctionnelle (requête `22F190` sur le canal fonctionnel, filtre de réception déjà ouvert, réponse cherchée sous l'en-tête `18DAF1xx`).
6. `identification` : pour chaque répondant : `22F186, 22F190, 22F187, 22F189, 22F191, 22F18C`, reste de `F180`–`F19F`, `1901FF`, `1902FF`, `190A` (via `readDtc` : `190A` n'a pas de masque — requête 2 octets).
7. `did-gen4` : sur le répondant `0xDB` s'il existe (sinon événement « cible absente », phase sautée) : les 8 requêtes de `KnownSignals` Gen4, chacune deux fois avec au moins `pacing` (l'intervalle « quelques minutes » est affaire d'orchestration d'app, pas de l'exécuteur ; l'exécuteur expose `runGen4Probes()` réappelable) ; `7F 22 31` sur DB ⇒ un essai consigné « hypothèse » sur DE puis E5 s'ils ont répondu à l'étape 6.
8. `balayage` (méthode séparée, jamais dans `runPhases` par défaut) : `sweepDidRange` un DID par requête, `ATST64` posé/restitué `ATSTFF`, reprise via `startAfter`, collecte des DID ayant répondu `7F`/`7E`/`33` ; retest optionnel en session étendue : `10 03` physique, keepalive `3E 80` toutes les 2 s via une coroutine d'entretien bornée à la durée du retest, retour `10 01` explicite dans un `finally`.
Gardes permanents (spec §5.5) : compteur de `CAN ERROR` consécutifs (3 ⇒ arrêt de la phase, événement d'arrêt) ; `ATRV` relu au début de chaque phase et dès que 30 s monotones se sont écoulées depuis la dernière lecture (< plancher hors READY ⇒ arrêt ; l'état READY est un paramètre `vehicleReady: Boolean` de `runPhases`, défaut `true` documenté) ; boucle `78` bornée par la session ; `BUFFER_FULL` ou charge incomplète ⇒ une retente unique après `ATFCSD300A0A`, `retryOf` renseigné dans le journal, puis restauration `ATFCSD300000`.

- [ ] **Test de session scriptée d'abord** (le test roi de M1) : scénario complet sur `ScriptedElmTransport` — init OK ; 11 bits légiféré `NO DATA` ; 29 bits `0100` répond sous `18DAF1DB` et `18DAF1DE` ; `2101` → `NO DATA` ; `0xDB` : `3E00`→`7E00`, `22F190` → faux VIN multi-trames, `22F186` → `62 F1 86 01`, `2201D1` → deux charges différentes entre les deux appels de `runGen4Probes` ; `0xDE` répond au légiféré mais ses DID `F1xx` restent `NO DATA`. Assertions : séquence des phases dans les événements, aucune écriture du transport ne contient de requête hors liste blanche (rejeu du log d'écritures dans `RequestGuard`, commandes AT exclues), journal contient des lignes `no_data` pour les silences (jamais `positive`), aucun événement ne déclare un ECU « absent ».
- [ ] **Tests des gardes** : 3 `CAN ERROR` scriptés ⇒ arrêt de phase avec cause ; `ATRV` scripté à 11,8 V avec `vehicleReady=false` ⇒ arrêt avant la phase suivante ; `BUFFER FULL` ⇒ exactement une retente, `ATFCSD300A0A` émis entre les deux.
- [ ] **TcpElmTransport** : `java.net.Socket`, threads via `Dispatchers.IO`, `incoming` en `callbackFlow` fermant sur EOF/erreur ; test boucle locale avec un mini-serveur socket écho-prompt dans le test (pas d'émulateur requis).
- [ ] **Harnais émulateur** : `scripts/elm-emulator/` — vérifier la licence dans le dépôt Ircama/ELM327-emulator **avant** installation (consigner le résultat dans `docs/m0-bootstrap.md`) ; installation locale uniquement, jamais commitée ni redistribuée : `uv venv .toolchain/emu-venv && uv pip install --python .toolchain/emu-venv ELM327-emulator` (à défaut de paquet PyPI fonctionnel : clone `--depth 1` dans `.toolchain/elm327-emulator-src`, l'un comme l'autre consigné). `scripts/run-emulator-it.sh` : lance l'émulateur en TCP sur le port 35000 (`python -m elm -n 35000`, adapter à l'aide réelle `-h`), attend l'ouverture du port, lance `./gradlew :protocol:test -Delm.emu.host=127.0.0.1 -Delm.emu.port=35000`, tue l'émulateur. Tests d'intégration gardés par `Assumptions.assumeTrue` sur ces propriétés système : init complète + `0100` + une lecture `22F190` classée sans exception (le verdict attendu dépend du dictionnaire par défaut de l'émulateur : `Positive` ou `NoData`/`Negative` documenté dans le test selon l'observation réelle, jamais inventé).
- [ ] Vert (`:protocol:test` inconditionnel **et** `scripts/run-emulator-it.sh`). **Commit** : `feat(protocol): plan de scan phases 1-8, transport TCP, intégration émulateur`.

### Tâche 9 — Squelettes Android et APK (fin M0)

**Livrable :** `:app:assembleDebug` vert, APK prêt à sideloader, `docs/m0-bootstrap.md` complété. **Dépend :** 0, 1, 8 (ordre de vérification seulement).

- [ ] Ajouter `:transport-android` (librairie Android vide hormis un fichier `package.kt` d'ancrage) et `:app` (AGP 9 : pas de plugin `kotlin.android` séparé, plugin Compose seul ; `compileSdk 37`, `targetSdk 36`, `minSdk 31` ; `applicationId "ca.mrklar.outlander.phevspy"` ; `MainActivity` affichant « Outlander PHEV Spy — M0 : squelette, voir la spec » ; manifest déclarant `BLUETOOTH_SCAN` (`neverForLocation`) et `BLUETOOTH_CONNECT` sans encore les demander). Si AGP 9.4.1 bloque le scaffold malgré la doc du train, écart consigné et repli autorisé sur le dernier AGP 8.x stable + plugin Kotlin Android explicite — décision consignée dans `docs/m0-bootstrap.md` et le message de commit, jamais silencieuse.
- [ ] `./gradlew :protocol:test` puis `./gradlew :app:assembleDebug` verts (ordre d'`AGENTS.md`). Relever le chemin et le `sha256sum` de l'APK dans `docs/m0-bootstrap.md` ; rappeler dans ce fichier la sauvegarde hors machine de `.toolchain/android-user-home/.android/debug.keystore` (action propriétaire, case à cocher laissée ouverte).
- [ ] Procédure de sideload écrite dans `docs/m0-bootstrap.md` (branchement USB ou Wi-Fi : `adb install -r app/build/outputs/apk/debug/app-debug.apk` ; ou transfert du fichier et installation manuelle sur le Pixel). L'installation réelle sur le Pixel 8 Pro se fera avec le propriétaire : ne pas la déclarer faite.
- [ ] **Commit** : `feat: squelettes Android (app Compose, transport-android) et APK debug`.

## Revue de couverture (meneur, après exécution)

- Spec §4 modules/interfaces → tâches 1-7 (transports réels et UI : M2, hors plan, assumé). §5 liste blanche/noire/exception → tâche 6 + test exhaustif. §6 init/capacités/adressage/FC/ISO-TP → tâches 4, 5, 6, 8. §7 phases 1-8 + interprétation → tâche 8 (l'interprétation humaine reste un compte rendu M3). §8 signaux → tâche 7b. §9 journal → tâche 7a (+ dérivation CSV : reportée après M1, le JSONL fait foi). §10 tests sans voiture → tâches 3, 5, 6, 8 (les cinq couches ; rejeu = `ReplayTransport` tâche 2). §11 toolchain → tâches 0, 9. §12 risques → gardes tâche 8, consignes tâches 0/9. §13 jalons M0/M1 → totalité du plan. §14 → consigné (clone : matrice tâche 4 ; Pixel : tâche 9).
- Aucun élément M2+ n'est requis pour fermer ce plan : `:protocol:test` vert + intégration émulateur verte + `assembleDebug` vert = M0/M1 terminés.
