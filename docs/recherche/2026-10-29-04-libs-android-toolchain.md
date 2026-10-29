# Recherche — App Android ELM327 BLE + Classic, découverte OBD/UDS lecture seule (Outlander PHEV 2024)

Date de consultation des sources : **29 octobre 2026**. Les dates de release/commit ci-dessous sont des **faits vérifiés via l'API GitHub** à cette date. Les verdicts sont étiquetés **[Opinion]**.

---

## (a) Libs candidates

| Lib / projet | Rôle | Maintenance · Licence (fait daté) | Verdict |
|---|---|---|---|
| [eltonvs/kotlin-obd-api](https://github.com/eltonvs/kotlin-obd-api) | Couche ELM327/OBD-II pure Kotlin JVM (`InputStream`/`OutputStream`, coroutines) | v1.4.1 du 2026-02-24 · Apache-2.0 · JitPack (`com.github.eltonvs:kotlin-obd-api`) | **Utilisable partiellement, pas comme cœur UDS** [Opinion] |
| [pires/obd-java-api](https://github.com/pires/obd-java-api) (+ ancien [android-obd-reader](https://github.com/pires/android-obd-library)) | Ancêtre Java de kotlin-obd-api, Mode 01 | Dépôt **archivé**, dernier push 2017-07-16 · Apache-2.0 | **Rejeter** |
| [tzebrowski/ObdMetrics](https://github.com/tzebrowski/ObdMetrics) | Framework Java OBD complet (batch PID, diagnostics), Maven Central `io.github.tzebrowski:obd-metrics` | Release 9.30.0 du 2024-06-10, dépôt toujours actif · Apache-2.0 | Référence/étude seulement (Java, lourd, centré PID Mode 01) [Opinion] |
| [fr3ts0n/AndrOBD](https://github.com/fr3ts0n/AndrOBD) | App Android complète (BT/USB/Wi-Fi, base PID/DTC, mode démo/replay, plugins, export CSV) | Push 2026-10-19, très actif, sur [F-Droid](https://f-droid.org/en/packages/com.fr3ts0n.ecu.gui.androbd/) · **GPL-3.0** | **Référence d'architecture uniquement** — copier du code impose GPL à toute distribution; utilisable tel quel comme outil de capture terrain |
| [JuulLabs/kable](https://github.com/JuulLabs/kable) | BLE Kotlin coroutines/Flow, multiplateforme (scan inclus) | v0.45.0 du 2026-09-15 · Apache-2.0 · API encore 0.x | **Choix BLE recommandé** (épinglé, derrière notre interface) [Opinion] |
| [NordicSemiconductor/Android-BLE-Library](https://github.com/NordicSemiconductor/Android-BLE-Library) | BLE Android production (file d'opérations GATT sérialisée, callbacks puis coroutines depuis 2.3) | v2.11.0 du 2025-09-11 · licence permissive Nordic (BSD-3, voir `LICENSE` du dépôt) · **pas de scan** → lib compagne [Android-Scanner-Compat-Library](https://github.com/NordicSemiconductor/Android-Scanner-Compat-Library) | Alternative BLE la plus éprouvée; plus verbeuse (sous-classe `BleManager` par appareil) [Opinion] |
| [dariuszseweryn/RxAndroidBle](https://github.com/dariuszseweryn/RxAndroidBle) | BLE en RxJava 2/3 | v1.19.1 du 2025-06-06 · Apache-2.0 | Rejeter ici : impose RxJava là où tout le reste est en Flow [Opinion] |
| API native `BluetoothGatt` ([doc Android BLE](https://developer.android.com/develop/connectivity/bluetooth/ble/ble-overview)) | BLE bas niveau, zéro dépendance | Toujours maintenue (plateforme) | Rejeter en direct : 1 opération GATT à la fois, erreurs 133, callbacks — c'est précisément ce qu'absorbent Kable/Nordic [Opinion] |
| [Ircama/ELM327-emulator](https://github.com/Ircama/ELM327-emulator) | Émulateur ELM327 Python (série virtuelle ou TCP), multi-ECU, scénarios | Push 2026-09-17 · licence à confirmer dans le dépôt (`NOASSERTION` côté API GitHub) | **Choix test sans voiture** (voir §c) |

### Verdict global protocole [Opinion]

**Écrire notre propre couche AT/ISO-TP/UDS en Kotlin pur.** Raisons :

1. Aucune lib maintenue ne fait la découverte UDS : kotlin-obd-api et ObdMetrics sont centrés Mode 01/PID et DTC Mode 03. Notre besoin (lecture seule mais exploratoire) exige des commandes brutes arbitraires (`ATSH`, `ATCRA`, `22 xxxx`, `19 02`, en-têtes CAN `ATH1`, réponses négatives `7F .. 31/78`, multi-ECU, réassemblage ISO-TP depuis les lignes ELM horodatées/indexées) — hors de leur modèle « commande typée → valeur ».
2. Le cœur à écrire est petit et c'est justement la partie qui doit être testable sans téléphone : tokeniseur du flux ELM (prompt `>`, écho, `SEARCHING...`, `NO DATA`, `CAN ERROR`, `BUFFER FULL`, `?`, espaces/CR variables selon `ATS/ATL/ATE`), sérialisation stricte « une commande en vol », réassemblage multi-trames.
3. kotlin-obd-api reste utile comme **référence de séquence d'initialisation** (`ATZ/ATE0/ATL0/ATS0/ATH1/ATSP6/ATAT1…` visible dans son README) et éventuellement pour quelques PID Mode 01 de sanity check — mais l'importer comme dépendance JitPack pour ça n'en vaut pas la peine [Opinion]. La forker n'apporte rien : on forkerait un modèle de commandes qu'on va contourner en « raw » partout.

---

## (b) Transport BLE + Classic : recommandation [Opinion, sur faits ci-après]

### Interface commune

```kotlin
interface ElmTransport {
    val incoming: Flow<ByteArray>          // chunks arbitraires, NE PAS supposer d'alignement trame
    suspend fun write(bytes: ByteArray)     // sérialisé par l'appelant (session ELM half-duplex)
    suspend fun close()
}
interface ElmScanner { fun scan(): Flow<ElmDevice> }  // BLE scan + appareils Classic appairés/découverte
```

Trois implémentations : `ClassicSppTransport`, `BleTransport` (Kable), `TcpTransport` (tests/émulateur, voir §c). La session ELM (file de commandes, timeouts, parsing jusqu'au `>`) vit au-dessus, dans le module pur.

### Bluetooth Classic (SPP/RFCOMM)

- **Fait** : UUID SPP standard `00001101-0000-1000-8000-00805F9B34FB`, via `createRfcommSocketToServiceRecord()` — la [doc `BluetoothDevice`](https://developer.android.com/reference/kotlin/android/bluetooth/BluetoothDevice.html) le recommande explicitement pour les « serial boards ». On obtient de vrais `InputStream/OutputStream` : c'est le transport le plus simple.
- Appairage : lister `bondedDevices` d'abord (cas normal, l'utilisateur appaire l'ELM dans les réglages système; PIN clones usuels `1234`/`0000` — fait empirique, non normatif); `startDiscovery()` + `createBond()` seulement en secours. Penser à `cancelDiscovery()` avant `connect()` (sinon connexion lente/échouée).
- Les adaptateurs « sérieux » Classic (ex. OBDLink MX/LX) sont SPP classiques; pas d'UUID proprio à gérer côté Classic.

### BLE

- **Fait** : il n'existe **aucun UUID BLE OBD standardisé**. Familles rencontrées dans les adaptateurs et le code ouvert (ex. portages EcuTweaker) : `FFE0/FFE1` (clones type HM-10, service/caractéristique unique RX+TX), `18F0` avec `2AF0` (notify) / `2AF1` (write), services proprios Vgate (`E7810A71-…`/`BEF8D6C9-…`), parfois Nordic UART `6E400001/2/3`, services proprios OBDLink sur les modèles BLE (MX+).
- **Recommandation [Opinion]** : ne jamais coder en dur un seul couple d'UUID. Après `discoverServices()`, appliquer : (1) table de couples connus ci-dessus; (2) repli générique = premier service ayant une caractéristique notify/indicate + une write/write-without-response (éventuellement la même). Demander un MTU élevé (247 ou 512) et découper les écritures en `mtu-3` octets; en réception, concaténer tout — le framing vient du `>` ELM, pas des paquets BLE.
- **Choix de lib [Opinion]** : Kable en premier choix (scan + connexion en Flow, s'aligne avec Compose/coroutines), version épinglée exactement car 0.x. Nordic si la stabilité en production prime sur l'ergonomie. Dans les deux cas, isolée derrière `BleTransport` pour pouvoir changer.

### Permissions par version (faits — [doc officielle Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions))

| Version | Scan BLE / découverte Classic | Connexion (GATT ou RFCOMM) |
|---|---|---|
| API ≥ 31 (Android 12+) | `BLUETOOTH_SCAN` (runtime) avec `android:usesPermissionFlags="neverForLocation"` → **dispense de la permission localisation** si on ne dérive jamais de position des scans | `BLUETOOTH_CONNECT` (runtime) — couvre aussi `bondedDevices`, `getName()`, RFCOMM et GATT |
| API ≤ 30 | `BLUETOOTH` + `BLUETOOTH_ADMIN` (déclarées `maxSdkVersion="30"`) **+ `ACCESS_FINE_LOCATION` runtime** (et services de localisation activés chez plusieurs OEM) | Idem (anciennes permissions normales) |

- **Recommandation forte [Opinion]** : app personnelle pour le seul téléphone du propriétaire (récent) → **`minSdk 31`**. Une seule branche de permissions, groupe « Appareils à proximité », zéro justification localisation. `minSdk 26` seulement si un vieux téléphone secondaire est visé — ça ajoute la branche ≤ 30 ci-dessus.
- Session longue en arrière-plan (si un jour) : foreground service type `connectedDevice` (obligatoire à déclarer depuis Android 14) — inutile pour un usage interactif au premier plan.

---

## (c) Tester sans voiture

1. **[Ircama/ELM327-emulator](https://github.com/Ircama/ELM327-emulator) — le seul choix sérieux [Opinion sur faits].** Faits (README du projet) : Python, communication série virtuelle (`/dev/pts`) **ou TCP** (`-n <port>`), dictionnaire `ObdMessage` et **scénarios nommés superposables** qui redéfinissent les réponses par commande, simulation **multi-ECU**, et « stateful UDS communication with ISO-TP Flow Control and Keyword Protocol 2000 ». C'est le seul émulateur logiciel trouvé qui sache scénariser des réponses UDS multi-trames (première trame `10 xx`, trames consécutives `21…2F`, flow control `30 00`) plutôt que de simples PID Mode 01.
   - Usage prévu : tests d'intégration JVM du module pur via `TcpTransport` vers l'émulateur en local; sur le vrai téléphone, même `TcpTransport` vers la machine de dev (même Wi-Fi) pour valider l'UI sans la voiture.
   - Point de vigilance [Opinion] : vérifier empiriquement que ses réponses multi-trames, une fois « vues » côté ELM (`ATH1` etc.), correspondent octet pour octet à ce qu'un vrai ELM327/STN affiche (compteurs de trames, espaces). C'est là que le rejeu (point 3) sert d'oracle indépendant.
2. **Alternatives écartées [Opinion]** : [OBDSim](https://icculus.org/obdgpslogger/) (projet historique d'obdgpslogger, valeurs Mode 01 simulées/aléatoires, pas de script UDS, maintenance ancienne); les simulateurs ECU matériels (type ECUsim 2000 de ScanTool) — payants, centrés Mode 01, aucun intérêt face à l'émulateur d'Ircama pour du UDS scripté. Au niveau CAN brut (si futur adaptateur non-ELM), [python-udsoncan](https://github.com/pylessard/python-udsoncan) + `python-can-isotp` seraient la référence — hors périmètre ELM actuel.
3. **Rejeu de captures dans la lib de décodage — à concevoir dès le départ [Opinion]** :
   - L'app enregistre chaque session réelle en fichier log horodaté : octets bruts TX/RX tels que reçus du transport (avec les frontières de chunks BLE), avant tout parsing.
   - Le module pur expose un transport de rejeu lisant ces fichiers; la suite de tests rejoue les captures Outlander en **re-découpant le flux à des frontières arbitraires** (y compris au milieu d'une ligne et juste avant le `>`) et compare à des « golden files » de signaux décodés.
   - Couvrir par golden files : `7F 22 78` (responsePending suivi de la vraie réponse), `NO DATA`, `CAN ERROR`, réponses multi-ECU, écho activé, `SEARCHING...` initial, lignes avec index ISO-TP ELM (`0:`, `1:`…) vs en-têtes CAN.
   - Captures terrain initiales possibles sans notre app : AndrOBD (GPL mais utilisable comme outil) ou simple terminal série BT, puis anonymiser le VIN avant commit.

---

## (d) Toolchain headless Linux — versions concrètes

Faits datés : [AGP 9.4.0 (septembre 2026)](https://developer.android.com/build/releases/agp-9-4-0-release-notes) est la version stable courante (patch 9.4.1 observé dans les projets récents); AGP 9 exige **JDK 17 minimum** pour le démon Gradle et embarque le support Kotlin (« built-in Kotlin » — le plugin `org.jetbrains.kotlin.android` ne s'applique plus, seul le plugin Compose du même numéro que Kotlin); Compose est versionné par [BOM](https://developer.android.com/develop/ui/compose/bom) — **2026.09.00** est le dernier BOM stable observé; Kotlin stable courant **2.4.x (2.4.21)**; wrappers Gradle observés **9.7.1 → 9.8.1**. Google Play exige `targetSdk 36` depuis fin août 2026 (sans objet en sideload, mais autant aligner).

### Versions à épingler [Opinion de combinaison, chaque version étant un fait]

- JDK : **Eclipse Temurin 21 LTS** (ou 17 LTS) via [adoptium.net](https://adoptium.net/temurin/releases/) — bytecode cible 17 dans les deux cas. Choisir 21 : LTS la plus longue, AGP/Gradle 9 l'acceptent.
- Gradle **9.7.1** (wrapper), AGP **9.4.1**, Kotlin + plugin Compose **2.4.21**, Compose BOM **2026.09.00**.
- `compileSdk 37` (Android 17 stable), `targetSdk 36` (comportements 37 à adopter délibérément plus tard), `minSdk 31` (voir §b).
- Versions centralisées dans `gradle/libs.versions.toml`, aucun numéro en dur dans les `build.gradle.kts`.

### Commandes (sans Android Studio — procédure [doc command-line tools](https://developer.android.com/studio#command-line-tools-only) et [doc sdkmanager](https://developer.android.com/tools/sdkmanager))

```bash
# 1) JDK Temurin 21 (tarball Adoptium), puis :
export JAVA_HOME=$HOME/jdks/temurin-21
export PATH=$JAVA_HOME/bin:$PATH

# 2) Command-line tools : télécharger commandlinetools-linux-<numéro>_latest.zip
#    depuis https://developer.android.com/studio#command-line-tools-only
#    (numéro courant observé : 11076708 — prendre celui de la page + vérifier son SHA-256)
export ANDROID_HOME=$HOME/android-sdk
mkdir -p $ANDROID_HOME/cmdline-tools
curl -LO https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-11076708_latest.zip -d $ANDROID_HOME/cmdline-tools
mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest   # layout exigé par sdkmanager
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH

# 3) Paquets SDK (vérifier le libellé exact de la plateforme avec : sdkmanager --list)
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;37.0.0"

# 4) Bootstrap du wrapper (Gradle installé une fois via SDKMAN! ou l'archive officielle gradle-9.7.1-bin.zip)
gradle wrapper --gradle-version 9.7.1

# 5) Build + installation sur le téléphone (débogage USB ou sans fil activé)
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew :protocol:test     # tests du module pur, aucun SDK Android requis au-delà du JDK
```

### Signature

**Fait** : le build `debug` est signé automatiquement avec la clé debug (`$HOME/.android/debug.keystore`, générée au premier build) — **suffisant pour sideload personnel** via `adb install`, indéfiniment, sans compte Play. Limites [faits de plateforme] : les mises à jour exigent la **même clé** (sauvegarder ce keystore, sinon désinstall/reinstall avec perte des données de l'app); un APK `release` non signé ne s'installe pas — rester en `debug` tant que l'app est personnelle est le choix simple [Opinion].

---

## (e) Architecture — idées à reprendre des apps similaires [Opinion, projets réels cités]

- **[fr3ts0n/AndrOBD](https://github.com/fr3ts0n/AndrOBD)** (GPL — lire, ne pas copier) : service de communication séparé de l'UI, descriptions de PID/DTC **en données externes** plutôt qu'en code, mode démo/replay intégré (exactement notre transport de rejeu), API plugin. C'est le modèle le plus proche et le plus maintenu.
- **[EVNotify](https://github.com/EVNotify/EVNotify)** (app Cordova/Vue historique, communauté Hyundai/Kia EV) : un **fichier de configuration par modèle de voiture** décrivant la séquence AT d'init (`ATSH7E4`…), les requêtes `22xxxx` et le décodage octet par octet. Idée à reprendre telle quelle en Kotlin : définitions de sondes/décodages **déclaratives et data-driven** (en-tête TX, requête, filtre RX, formule), éditables sans toucher au moteur de session — c'est aussi le modèle « custom PID » de Torque (fermé, mais son format CSV est public et connu).
- **[OVMS v3](https://github.com/openvehicles/Open-Vehicle-Monitoring-System-3)** (firmware ESP32 + apps) : registre de **métriques normalisées** (nom, unité) alimenté par des modules par véhicule, et ordonnanceur de polling selon l'état du véhicule (éveillé/endormi, charge…). Idées : découpler « signal décodé nommé » de « requête qui l'a produit », et un poller qui espace les requêtes quand les ECU dorment (les `NO DATA` répétés de PHEV éteint sont un état normal, pas une erreur).
- **LeafSpy** est fermé; le pattern qu'il a popularisé (session ELM unique, requêtes CAN adressées sérialisées, journalisation systématique) se retrouve dans les trois projets ouverts ci-dessus.

### Découpage proposé [Opinion]

| Module | Contenu | Dépendances |
|---|---|---|
| `:protocol` (Kotlin/JVM pur) | Codec AT, parseur de flux ELM (prompt `>`), réassemblage ISO-TP, requêtes/réponses UDS lecture (`22`, `19`, `10` session étendue si nécessaire), définitions de signaux Outlander en données, transport de rejeu + golden tests | JDK seul |
| `:transport-android` | `BleTransport` (Kable), `ClassicSppTransport` (API native RFCOMM), `TcpTransport`, scanneurs, permissions | Android, Kable |
| `:app` (Compose) | Session ELM en **acteur sérialisé** (une commande en vol, timeouts, retry sur `7F..78`), écrans découverte (console AT brute d'abord, signaux décodés ensuite), enregistrement de session pour rejeu | Les deux modules ci-dessus |

Principe directeur : tout ce qui peut être faux sans voiture (parsing, réassemblage, décodage) vit dans `:protocol` et est prouvé par rejeu; tout ce qui exige le matériel (scan, appairage, quirks GATT) est mince et remplaçable.

---

## Limites de cette recherche

- Licence exacte d'ELM327-emulator non confirmée par l'API GitHub (`NOASSERTION`) — vérifier le fichier `LICENSE`/en-têtes dans le dépôt avant tout usage hors test local.
- Les numéros « patch du jour » (AGP 9.4.1, Gradle 9.8.1, libellé `platforms;android-37.0`, numéro du zip cmdline-tools) évoluent vite : les valeurs ci-dessus sont celles observées le 2026-10-29; le jour de l'installation, confirmer chaque libellé par `sdkmanager --list` et la [page des command-line tools](https://developer.android.com/studio#command-line-tools-only) plutôt que de copier-coller à l'aveugle.
- Aucun adaptateur précis n'était nommé dans la mission : la famille d'UUID BLE réellement rencontrée dépendra du dongle acheté (clone FFE0 vs Vgate vs OBDLink) — d'où le repli générique de découverte de services recommandé en §b.