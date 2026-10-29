# Harnais ELM327-emulator (intégration locale)

Oracle sans voiture de `:protocol` : [ELM327-emulator](https://github.com/Ircama/ELM327-emulator)
d'Ircama, joint en TCP par `TcpElmTransport` (voir
`protocol/src/test/kotlin/ca/mrklar/outlander/protocol/elm/EmulatorIntegrationTest.kt`).

## Licence (vérifiée le 2026-10-29, AVANT installation)

- **Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International
  (CC BY-NC-SA 4.0).**
- Source : fichier `LICENSE` du dépôt, première ligne
  « Attribution-NonCommercial-ShareAlike 4.0 International » —
  https://raw.githubusercontent.com/Ircama/ELM327-emulator/master/LICENSE
  (texte légal : https://creativecommons.org/licenses/by-nc-sa/4.0/legalcode).
  L'API GitHub rapporte `license: Other / NOASSERTION` (SPDX non reconnu
  automatiquement) ; c'est le fichier `LICENSE` qui fait foi.
- Usage ici : développement personnel local uniquement. L'émulateur est
  installé dans `.toolchain/` (gitignorée), **jamais commité ni
  redistribué** ; aucun de son code n'entre dans le dépôt ni dans l'app.

## Installation (locale, contenue dans le dépôt)

```bash
export UV_PYTHON_INSTALL_DIR="$PWD/.toolchain/uv-pythons"
export UV_CACHE_DIR="$PWD/.toolchain/uv-cache"
uv venv --python 3.12 .toolchain/emu-venv   # Python système 3.14 → on épingle 3.12
uv pip install --python .toolchain/emu-venv/bin/python ELM327-emulator
.toolchain/emu-venv/bin/python -m elm -h    # vérifie (v4.0.0 installée le 2026-10-29)
```

## Lancement des tests d'intégration

```bash
scripts/run-emulator-it.sh
```

Le script source `scripts/dev-env.sh`, démarre l'émulateur en TCP
(`python -m elm -n 35000`, interface 127.0.0.1 par défaut), attend
l'ouverture du port, lance
`./gradlew :protocol:test --tests "*EmulatorIntegrationTest*" -Delm.emu.host=127.0.0.1 -Delm.emu.port=35000`
(avec `ELM_EMU_HOST`/`ELM_EMU_PORT` exportés, car une `-D` Gradle ne
descend pas dans la JVM de test), puis tue l'émulateur (trap).

Piège rencontré le 2026-10-29 : lancé sans stdin (arrière-plan sec),
l'émulateur se termine dès l'EOF de sa boucle interactive (`CMD>`) et
la connexion TCP coupe après 2 commandes. Le harnais lui donne donc un
FIFO dont le shell garde le bout d'écriture ouvert sans rien écrire.

## Comportement observé (scénario par défaut, 2026-10-29)

Init complète d'`ElmSession.initialize` acceptée : `ATI` → « ELM327 v1.5 »,
`STI` → « STN1100 v1.2.3 » (l'émulateur se présente en STN), `ATRV` →
13.1 V, toutes les commandes de configuration en `OK` (y compris
`ATCEA`), `ATDPN` → « A6 ». `0100` → `SEARCHING...` puis
`7E8 06 41 00 38 3B 80 13`, classé `POSITIVE` par `ElmCodec`.
