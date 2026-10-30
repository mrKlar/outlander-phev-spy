# M0 — Bootstrap de la toolchain contenue

Installation réalisée le 2026-10-29 (Tâche 0 du plan M0+M1). Toute la toolchain vit sous
`.toolchain/` (gitignorée) : aucune écriture hors du dépôt. Pour l'utiliser :

```bash
source scripts/dev-env.sh
```

`scripts/dev-env.sh` exporte des chemins absolus calculés depuis l'emplacement du script :
`JAVA_HOME`, `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `GRADLE_USER_HOME` (`.toolchain/gradle-home`),
`ANDROID_USER_HOME` (`.toolchain/android-user-home`) et le `PATH`
(`jdk/bin`, `gradle/gradle-9.7.1/bin`, `cmdline-tools/latest/bin`, `platform-tools`).

## Vérification des versions épinglées (avant installation)

| Artefact | URL vérifiée | Code HTTP |
|---|---|---|
| AGP 9.4.1 | `https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.4.1/gradle-9.4.1.pom` | 200 |
| Kotlin Gradle plugin 2.4.21 | `https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/2.4.21/kotlin-gradle-plugin-2.4.21.pom` | 200 |
| Compose BOM 2026.09.00 | `https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2026.09.00/compose-bom-2026.09.00.pom` | 200 |
| cmdline-tools 11076708 | `https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip` | 200 |
| Gradle 9.7.1 (distribution) | `https://services.gradle.org/distributions/gradle-9.7.1-bin.zip` | 307 (redirection normale, suivie par `curl -L`) |

Coroutines : `maven-metadata.xml` de `org.jetbrains.kotlinx:kotlinx-coroutines-core` indique
`latest`/`release` = **1.11.0** au 2026-10-29 (≥ 1.10.2). C'est cette version qui entre au
catalogue (`gradle/libs.versions.toml`, tâches suivantes).

Aucun écart par rapport aux versions épinglées : la règle du correctif supérieur n'a pas eu à s'appliquer.

## Versions demandées vs installées

| Composant | Demandé | Installé |
|---|---|---|
| JDK | 21 (Temurin, dernier GA via api.adoptium.net) | Temurin 21.0.13+9 LTS |
| Gradle | 9.7.1 | 9.7.1 (Kotlin embarqué 2.4.0) |
| cmdline-tools | 11076708 | 11076708 |
| `platform-tools` | (libellé confirmé par `sdkmanager --list`) | 37.0.1 |
| `platforms;android-37.0` | idem | révision 2 |
| `build-tools;37.0.0` | idem | 37.0.0 |

## Archives téléchargées (gardées dans `.toolchain/telechargements/`)

```
2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258  telechargements/commandlinetools-linux-11076708_latest.zip
acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a  telechargements/gradle-9.7.1-bin.zip
d7f5d5cf865a2902b70ff549b97586a7d3f234e016f041ab447ea740c7d66d1c  telechargements/jdk-21.tar.gz
```

(Fichier `jdk-21.tar.gz` = sortie de `https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse`,
contenu `jdk-21.0.13+9/`.)

## Réinstallation depuis zéro

```bash
mkdir -p .toolchain/telechargements .toolchain/gradle-home .toolchain/android-user-home .toolchain/android-sdk
curl -fL -o .toolchain/telechargements/jdk-21.tar.gz https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse
curl -fL -o .toolchain/telechargements/gradle-9.7.1-bin.zip https://services.gradle.org/distributions/gradle-9.7.1-bin.zip
curl -fL -o .toolchain/telechargements/commandlinetools-linux-11076708_latest.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
mkdir -p .toolchain/jdk .toolchain/gradle .toolchain/android-sdk/cmdline-tools
tar -xzf .toolchain/telechargements/jdk-21.tar.gz -C .toolchain/jdk --strip-components=1
unzip -q .toolchain/telechargements/gradle-9.7.1-bin.zip -d .toolchain/gradle
unzip -q .toolchain/telechargements/commandlinetools-linux-11076708_latest.zip -d .toolchain/android-sdk/cmdline-tools/
mv .toolchain/android-sdk/cmdline-tools/cmdline-tools .toolchain/android-sdk/cmdline-tools/latest
source scripts/dev-env.sh
yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses
sdkmanager --sdk_root="$ANDROID_HOME" "platform-tools" "platforms;android-37.0" "build-tools;37.0.0"
```

## Vérification (sortie réelle, 2026-10-29)

```
$ java -version
openjdk version "21.0.13" 2026-10-20 LTS
OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS)
OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing)

$ gradle --version
------------------------------------------------------------
Gradle 9.7.1
------------------------------------------------------------
Build time:    2026-08-19 14:16:09 UTC
Revision:      92f0512e7f06d84621afba191f75e265363890cf
Kotlin:        2.4.0
Groovy:        4.0.32
Ant:           Apache Ant(TM) version 1.10.17 compiled on April 6 2026
Launcher JVM:  21.0.13 (Eclipse Adoptium 21.0.13+9-LTS)
Daemon JVM:    .../.toolchain/jdk (no Daemon JVM specified, using current Java home)
OS:            Linux 7.2.8-9-omarchy amd64

$ sdkmanager --list_installed
Installed packages:
  Path                   | Version | Description                | Location
  -------                | ------- | -------                    | -------
  build-tools;37.0.0     | 37.0.0  | Android SDK Build-Tools 37 | build-tools/37.0.0
  platform-tools         | 37.0.1  | Android SDK Platform-Tools | platform-tools
  platforms;android-37.0 | 2       | Android SDK Platform 37.0  | platforms/android-37.0
```

Taille totale de `.toolchain/` : 1,5 G (jdk 347 M, gradle 165 M, android-sdk 472 M,
telechargements 490 M, android-user-home 3,6 M, gradle-home 120 K).

## Écarts et remarques

- Aucun écart de version.
- `sdkmanager` est invoqué avec `--sdk_root="$ANDROID_HOME"` explicite : plus sûr que la
  déduction depuis l'emplacement du binaire.
- Le brief décrit `scripts/dev-env.sh` à « chemins relatifs à la racine du dépôt » : le script
  calcule en fait des chemins **absolus** depuis son propre emplacement (plus robuste, fonctionne
  quel que soit le répertoire courant), conformément à la consigne du meneur.
- Licences SDK acceptées (`android-sdk/licenses/`, 8 fichiers) : « All SDK package licenses accepted ».

## Émulateur ELM327 (ajout tâche 8, 2026-10-29)

[ELM327-emulator](https://github.com/Ircama/ELM327-emulator) d'Ircama sert
d'oracle sans voiture pour `:protocol` (TCP via `TcpElmTransport`).

Licence vérifiée **avant** installation (2026-10-29) :
**Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International
(CC BY-NC-SA 4.0)** — fichier `LICENSE` du dépôt
(https://raw.githubusercontent.com/Ircama/ELM327-emulator/master/LICENSE,
première ligne « Attribution-NonCommercial-ShareAlike 4.0 International » ;
l'API GitHub dit `Other / NOASSERTION`, SPDX non détecté). Usage
strictement local et personnel : installé dans `.toolchain/` (gitignorée),
jamais commité ni redistribué.

```bash
export UV_PYTHON_INSTALL_DIR="$PWD/.toolchain/uv-pythons"
export UV_CACHE_DIR="$PWD/.toolchain/uv-cache"
uv venv --python 3.12 .toolchain/emu-venv
uv pip install --python .toolchain/emu-venv/bin/python ELM327-emulator  # v4.0.0
.toolchain/emu-venv/bin/python -m elm -h
scripts/run-emulator-it.sh   # démarre l'émulateur TCP :35000, tests, arrête
```

Détails, piège stdin/EOF et comportement observé (`0100` → POSITIVE) :
[scripts/elm-emulator/README.md](../scripts/elm-emulator/README.md).

## Fin M0 — APK squelette (tâche 9)

Réalisé le 2026-10-29. Modules ajoutés : `:transport-android`
(librairie Android, ancrage `TransportAndroid`, vrais transports BLE +
Bluetooth Classic en M2) et `:app` (Compose, écran statique M0 qui
affiche les compteurs calculés depuis `KnownSignals` de `:protocol` :
8 DID Gen4 communautaires non validés, 4 décodeurs anciens oracle).

### Versions réellement utilisées

| Composant | Version | Remarque |
|---|---|---|
| AGP | **9.4.1** (catalogue, inchangé) | Aucun repli : le scaffold AGP 9 a réussi dès la première tentative (pas de plugin `kotlin.android` séparé, plugin Compose seul ; Kotlin intégré à AGP 9 compile aussi `:transport-android`). |
| Kotlin / plugin Compose | 2.4.21 | Inchangé. |
| Compose BOM | 2026.09.00 | Inchangé ; `ui`, `ui-graphics`, `material3`, `ui-tooling-preview` (debug seulement) sans version directe, via le BOM. |
| `androidx.activity:activity-compose` | **1.13.0** | Relevé le 2026-10-29 sur `https://dl.google.com/dl/android/maven2/androidx/activity/activity-compose/maven-metadata.xml` : `latest`/`release` = `1.14.0-alpha03` (alpha), dernière **stable** = **1.13.0**. Ajoutée au catalogue (`androidx-activity`). |
| SDK | compileSdk 37, targetSdk 36, minSdk 31 | AGP a auto-installé `build-tools;36.0.0` (sa version par défaut) dans `.toolchain/android-sdk/` au premier build ; les vérifications `aapt2` ci-dessous utilisent les build-tools 37.0.0 déjà installés. |
| `local.properties` | aucun | `ANDROID_HOME` de `scripts/dev-env.sh` a suffi ; aucun fichier créé. |

### Vérifications (ordre d'`AGENTS.md`, sorties réelles)

```
$ ./gradlew :protocol:test
BUILD SUCCESSFUL in 8s        (124 tests au rapport : 0 échec, 0 erreur,
                               1 ignoré = EmulatorIntegrationTest, émulateur
                               non configuré — comportement attendu hors
                               scripts/run-emulator-it.sh)

$ ./gradlew :app:assembleDebug
BUILD SUCCESSFUL in 32s
56 actionable tasks: 54 executed, 2 up-to-date
```

### APK produit

```
$ ls -lh app/build/outputs/apk/debug/app-debug.apk
-rw-r--r-- 1 n n 29M Oct 29 14:42 app/build/outputs/apk/debug/app-debug.apk
  (taille exacte : 29 668 149 octets)

$ sha256sum app/build/outputs/apk/debug/app-debug.apk
5a2ef08f2b66fe80922eb266fff4de8fee8a6f4487caeadca6cdfc670fef9d33  app/build/outputs/apk/debug/app-debug.apk
```

Badging résumé (`aapt2 dump badging`, build-tools 37.0.0) :

```
package: name='ca.mrklar.outlander.phevspy' versionCode='1' versionName='0.1.0-m0' platformBuildVersionName='17' platformBuildVersionCode='37' compileSdkVersion='37' compileSdkVersionCodename='17'
minSdkVersion:'31'
targetSdkVersion:'36'
uses-permission: name='android.permission.BLUETOOTH_SCAN' usesPermissionFlags='neverForLocation'
uses-permission: name='android.permission.BLUETOOTH_CONNECT'
application-label:'Outlander PHEV Spy'
```

`aapt2 dump permissions` confirme les deux permissions Bluetooth ci-dessus
(déclarées seulement, jamais demandées à l'exécution en M0), plus la
permission interne `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` ajoutée par
AndroidX. `apksigner verify` : `Verifies` (schéma v2, 1 signataire).

### Keystore de debug

Emplacement **réellement** utilisé par AGP 9 (généré au premier build) :

```
.toolchain/android-user-home/debug.keystore   (2 618 octets, alias androiddebugkey)
SHA256 du certificat : 9C:7B:7E:BF:DE:39:DB:93:FA:2A:D1:50:97:86:CE:B7:AD:9D:41:92:7A:B8:AB:2D:40:46:EA:C0:02:75:94:FD
```

Remarque : AGP 9 le place directement sous `$ANDROID_USER_HOME`, pas dans
un sous-dossier `.android/` (qui n'existe pas dans
`.toolchain/android-user-home/`). Le fichier est hors git :
`git check-ignore -v` répond `.gitignore:2:/.toolchain/` et `git status`
ne le montre pas. Si ce fichier est perdu, Android refusera de mettre à
jour l'app installée (signature différente) : il faudra désinstaller puis
réinstaller.

- [ ] Sauvegarder `/home/n/code/outlander-phev-spy/.toolchain/android-user-home/debug.keystore` hors de cette machine (action propriétaire)

### Sideload sur le Pixel 8 Pro

**Installation réelle effectuée le 2026-10-30** : Pixel 8 Pro (Android 17)
branché en USB au portable Windows `zen`, atteint depuis ControlTower par
SSH sur Tailscale. Chaîne prouvée de bout en bout : `adb devices` →
`device product:husky model:Pixel_8_Pro` ; `adb install -r` → `Success` ;
lancement par `am start` ; capture d'écran vérifiée visuellement
(« Outlander PHEV Spy », « DID Gen4 en registre: 8 », « Décodeurs anciens
(oracle): 4 »). Procédure d'origine, pour référence :

Par `adb` (recommandé) :

1. Sur le Pixel : Paramètres → À propos du téléphone → taper 7 fois sur
   « Numéro de build » pour activer les options pour les développeurs.
2. Paramètres → Système → Options pour les développeurs → activer
   « Débogage USB » (ou « Débogage sans fil » puis appairer le téléphone).
3. Brancher le câble USB, accepter l'empreinte RSA sur le téléphone,
   vérifier `adb devices` (l'appareil doit apparaître, état `device`).
4. Depuis la racine du dépôt :

   ```bash
   source scripts/dev-env.sh
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

Alternative sans câble : transférer `app-debug.apk` sur le téléphone
(USB en transfert de fichiers, courriel, Syncthing…), l'ouvrir avec
l'explorateur de fichiers et autoriser l'installation depuis cette source
quand Android le demande.

Au lancement, l'écran M0 doit afficher « Outlander PHEV Spy »,
« DID Gen4 en registre: 8 (pistes communautaires non validées) » et
« Décodeurs anciens (oracle): 4 ». Aucune permission ne sera demandée en
M0. Rappel : application en lecture seule — ne jamais lancer un scan en
roulant.
