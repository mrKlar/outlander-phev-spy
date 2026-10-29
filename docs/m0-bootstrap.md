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
