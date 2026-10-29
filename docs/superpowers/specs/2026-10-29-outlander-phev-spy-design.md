# Outlander PHEV Spy — Spécification de design

- Date : 2026-10-29
- Statut : design approuvé, aucun code source à cette date
- Approche retenue : « découverte 29 bits/UDS, décodeurs data-driven, liste blanche lecture seule »
- Source de vérité : [synthèse décisionnelle du 2026-10-29](../../recherche/2026-10-29-00-synthese-decisions.md) et rapports détaillés `01` à `04` du même dossier
- Langue du projet : documentation et commits en français

## 1. But et contexte

### 1.1 Véhicule cible

Le véhicule cible unique de la v1 est un Mitsubishi Outlander PHEV 2024, 4e génération, version Amérique du Nord. Sa batterie de traction documentée fait 20 kWh brut, 350 V nominal, 57,1 Ah. Le pack 22,7 kWh relève des variantes Europe/Japon 2025+ et n'est pas la cible.

Ce véhicule n'appartient pas à l'ancienne plateforme GG2W. Il repose sur la plateforme Alliance CMF-CD, partagée avec le Nissan Rogue/X-Trail. Sa caisse, son BCM, sa clé et sa passerelle sont d'origine Nissan selon le modèle mental retenu, tandis que la supervision PHEV Mitsubishi serait ré-exposée en UDS standard sur CAN 29 bits. Tout héritage de l'ancien protocole Mitsubishi est donc une hypothèse à invalider, jamais un acquis.

### 1.2 Pourquoi pas PHEV Watchdog

PHEV Watchdog est officiellement limité aux Outlander PHEV « up to 2021 ». Il cible l'ancienne génération : adressage 11 bits direct `761→762`, service `0x21`, constantes de pack anciennes (80 cellules, 300 V, 12/13,8 kWh). Aucune de ces constantes ne décrit le pack 20 kWh/350 V du 2024. Réutiliser Watchdog ou ses décodages comme base reviendrait à plaquer un protocole et une topologie qui ne sont pas ceux du véhicule.

La remarque de Watchdog selon laquelle l'application « will not work with BLE » documente une limite de cette application et des adaptateurs BLE courants. Elle ne prouve pas que le protocole du véhicule interdise le BLE. Le présent projet retient BLE et Bluetooth Classic, avec un adaptateur de référence de qualité comme oracle en cas de silence.

### 1.3 Ce que « type LeafSpy » veut dire ici

« Type LeafSpy » désigne ici la forme du produit, pas son protocole : une application Android personnelle, connectée à un adaptateur ELM327, qui lit l'état de la batterie de traction et l'affiche au propriétaire. Le protocole de la Nissan Leaf (11 bits `79B→7BB`, service `0x21`, écoute passive de broadcasts) n'est pas celui attendu sur l'Outlander 2024.

Trois différences structurantes découlent des rapports :

- aucun broadcast exploitable n'a été observé sur la prise OBD du modèle 2023 documenté : `ATMA` ne produit rien, tout passe donc par requête/réponse derrière une passerelle ;
- la voie attendue est l'UDS service `0x22` (ReadDataByIdentifier) sur CAN 29 bits ISO 15765-4 ;
- avant tout affichage « batterie », l'outil est d'abord un outil de découverte : il doit établir, sur le véhicule cible exact, quels ECU répondent, à quelles adresses, et quels DID existent.

En résumé, la v1 construit un outil de découverte 29 bits/`0x22` derrière passerelle, au cœur Kotlin pur prouvé sans voiture par émulateur et rejeu, avec liste blanche lecture seule et journal brut systématique. Aucun décodage Gen4 n'est promu au-delà du statut « piste communautaire » tant que la première session véhicule ne l'a pas corrélé au tableau de bord du véhicule cible.

### 1.4 Décisions déjà prises et non rouvertes

- Kotlin natif et Jetpack Compose pour l'application Android.
- Transports BLE et Bluetooth Classic, plus un transport TCP réservé au développement et aux tests.
- Lecture seule stricte, enforced par liste blanche dans le code (voir section 5).
- v1 = découverte du protocole + décodage des seuls PID/DID validés progressivement.

## 2. Périmètre v1 et non-objectifs explicites

### 2.1 Périmètre v1

- Connexion à un adaptateur ELM327 en BLE ou en Bluetooth Classic.
- Empreinte de l'adaptateur à chaque connexion et matrice de capacités AT, dont le plan de scan tient compte par dégradation.
- Protocole OBD légiféré en adressage 11 bits et 29 bits : inventaire des PID déclarés, Mode 09 (dont VIN), lecture des DTC en modes légiférés.
- Test d'héritage ancien modèle, une seule fois et dans un cadre fermé : `2101` vers `761`, puis `2102` uniquement si `2101` répond (voir section 5.3).
- Détection de présence UDS sur des paires d'adresses plausibles en 11 bits, sans balayage exhaustif.
- Interrogation prioritaire de la cible 29 bits d'adresse logique `0xDB`, puis de `0xDE` et `0xE5`, en service `0x22` et lectures DTC `0x19`.
- Identification de chaque ECU répondant par les DID `F180`–`F19F` avant toute attribution de rôle.
- Lecture des DID Gen4 communautaires connus, affichés au statut « piste communautaire » tant qu'ils ne sont pas corrélés (voir section 8).
- Journal de découverte JSONL complet, une ligne par transaction, exportable en fin de session.
- Console AT brute et statut de scan affichés en premier dans l'interface ; signaux décodés ensuite, avec badge de confiance visible.
- Saisie par le propriétaire des vérités terrain (SOC affiché au tableau de bord, autonomie, état de charge) horodatées dans le journal.
- Décodeurs data-driven : chaque signal est une définition déclarative, jamais une formule codée en dur dans la logique d'interrogation.

### 2.2 Non-objectifs explicites

- Aucune écriture vers le véhicule, sous aucune forme : pas d'effacement de DTC, pas d'écriture de DID, pas de routines, pas de contrôle d'entrées/sorties, pas de reset d'ECU, pas de téléchargement. La liste noire de la section 5.2 est exhaustive et testée.
- Aucun contournement de Secure Gateway : pas de câble bypass BCM, pas d'authentification, pas de SecurityAccess `0x27`, jamais. Si la passerelle filtre le diagnostic non légiféré sur le véhicule cible, ce résultat est documenté comme tel ; il n'est pas contourné en v1.
- Aucun SOH promis : aucun DID de santé ou de capacité résiduelle n'a été trouvé sur la 4e génération à la date de cette spec. L'application n'affiche ni SOH ni capacité résiduelle tant qu'un DID équivalent à l'ancien item 18 MUT n'a pas été identifié et corrélé. Aucun « SOC_MAX » n'est présenté comme proxy de dégradation.
- Aucun foreground service en v1 : usage interactif au premier plan uniquement. Pas de collecte en arrière-plan, pas de polling continu hors session supervisée.
- Aucune écoute passive du bus comme source de données : aucun broadcast exploitable n'est attendu sur la prise OBD, et `ATMA` ne fait pas partie du plan d'interrogation.
- Aucun balayage aveugle en première session au-delà de l'amorce bornée de l'étape 8, et aucun balayage générique du service `0x21`.
- Aucune transposition des constantes de l'ancien modèle : ni 80 cellules, ni 300 V, ni 12/13,8 kWh, ni dénominateur SOH de 38 ou 40 Ah.
- Aucune prise en charge des variantes Europe/Japon 22,7 kWh en v1.
- Aucune publication sur un store : distribution par sideload avec signature debug.

## 3. Faits établis, hypothèses à valider, inconnues

Les rapports sont cités ainsi : **R1** = ancien Outlander/PID connus, **R2** = 2023-2025/batterie/gateway, **R3** = UDS/ELM327/scan sûr, **R4** = Android/libs/tests. Les niveaux de confiance ci-dessous reprennent ceux de la synthèse, sans les durcir.

### 3.1 Faits établis

| # | Fait | Confiance | Source |
|---|---|---|---|
| F1 | Le 2024 n'est pas un GG2W : plateforme Alliance CMF-CD partagée avec le Nissan Rogue/X-Trail ; PHEV Watchdog limité aux Outlander PHEV « up to 2021 » | Élevée | R1, R2 |
| F2 | Batterie cible Amérique du Nord 2023-2024 : 20 kWh brut, 350 V, 57,1 Ah ; le 22,7 kWh est Europe/Japon 2025+ | Élevée | R2 |
| F3 | Ancien protocole Mitsubishi : 11 bits direct `761→762`, service `0x21`, réponse `2101` en `61 01…` multi-trames (ex. First Frame `10 37 61 01…`, 55 octets utiles) | Élevée pour l'ancien modèle | R1 |
| F4 | Décodages anciens (anko, items comptés à partir de 0 après `61 01`) : item 0 SOC % = valeur/2 − 5 ; items 8+9 tension pack = valeur/10 ; items 27+28 capacité Ah = valeur/10 (MUT item 18) ; items 29+30 charge restante Ah = valeur/10 ; `2102` tensions cellules = valeur 16 bits/1000 avec trous entre blocs de 8 | Élevée pour l'ancien modèle ; liste d'offsets cellules : moyenne | R1 |
| F5 | Formule de courant ancienne (items 14+15) tronquée et inutilisable ; températures par OBD non documentées sur l'ancien modèle | Élevée (absence constatée) | R1 |
| F6 | L'OBD légiféré ne donne presque rien côté batterie : seul PID pertinent `0x5B` (charge batterie hybride %, `A×100/255`), seulement s'il est déclaré dans le bitmask de `0140` | Élevée | R3 |
| F7 | Avec `ATCAF1`+`ATCFC1`, l'ELM gère l'ISO-TP côté bus, mais la dérivation automatique de l'ID de Flow Control échoue hors convention ; recette par ECU : `ATFCSH <ID requête physique>`, `ATFCSD 30 00 00`, `ATFCSM1` | Élevée | R3, origine terrain R1 |
| F8 | Les clones « ELM327 v1.5/v2.1 » ont des `ATFC*`/`ATCRA`/`ATCP` parfois absents ou menteurs, des tampons minuscules (`BUFFER FULL` sur longues réponses BLE) et un 29 bits capricieux ; référence convergente : adaptateur STN/OBDLink ou équivalent vLinker | Élevée | R1-R4 |
| F9 | Le risque n°1 est la batterie 12 V : scan = ECU maintenus éveillés ; hors READY, le DC-DC ne recharge pas l'auxiliaire ; la broche 16 est un +12 V permanent, avec plaintes 12 V déjà rapportées sur 2023 avec adaptateur laissé branché | Élevée | R3 ; plaintes terrain R2 |
| F10 | Aucune lib maintenue ne fait la découverte UDS : kotlin-obd-api et ObdMetrics sont centrés Mode 01/DTC typés ; le cœur AT/ISO-TP/UDS doit être écrit maison en Kotlin pur ; OVMS/OBDb n'ont rien d'exploitable pour l'Outlander PHEV | Élevée | R4 ; corroboré R1, R3 |
| F11 | Le test sans voiture est possible : Ircama/ELM327-emulator sait scénariser du UDS multi-trames avec flow control ISO-TP (série virtuelle ou TCP), à compléter par un transport de rejeu de captures réelles | Moyenne-élevée | R4, R3 |

### 3.2 Hypothèses à valider sur le véhicule cible

| # | Hypothèse | Confiance | Source | Validation prévue |
|---|---|---|---|---|
| H1 | La 4e génération répond en CAN 29 bits, UDS `0x22` : protocole ISO 15765-4 29 bits (`ATSP7`), adresse fonctionnelle `18DB33F1`, réponses filtrées `18DAF1DB` (adresse logique présumée BMU `0xDB`), plus `18DAF1DE` et `18DAF1E5` pour d'autres ECU | Moyenne — source unique (Outlander PHEV 2023, WiCAN/ELM327 + Car Scanner), un véhicule/marché, partiellement corroborée par un second propriétaire | R2 ; hypothèse prioritaire R3 ; mentionné R1 | Étapes 2, 5 et 6 de la première session (section 7) |
| H2 | DID Gen4 communautaires sur `0xDB` : `2201D1` SOC (équation empirique `B*0.64−19.2`), `2201D0` « SOC_MAX » selon l'auteur, `220106` tension HV, `220178`/`220179` tensions cellule max/min ; aussi cités sans équation : `22010B`, `22022A`, `22F0D0`. L'auteur demande de tout prendre « with a grain of salt » ; la définition exacte de l'octet `B` et les formules des autres DID sont absentes des rapports | Faible à moyenne | R2 ; `2201D1` aussi cité R1 | Étape 7 : double lecture à quelques minutes d'intervalle, corrélée au SOC tableau de bord |
| H3 | Aucun broadcast exploitable sur la prise OBD : `ATMA` ne produit rien sur le 2023, tout passe par requête/réponse derrière passerelle | Moyenne-élevée | R2 ; cohérent R3 | Assumée par design : aucune fonctionnalité ne dépend d'une écoute passive |
| H4 | Le protocole n'est ni « ancien MUT » (11 bits `761→762`, `0x21`) ni « Leaf/Consult » (11 bits `79B→7BB`, `0x21`) : supervision PHEV Mitsubishi ré-exposée en UDS `0x22` sur 29 bits | Moyenne-élevée (~70 % selon R2) | R2 | Étapes 3 à 5 : le test `2101` falsifie l'héritage, l'étape 5 confirme la voie 29 bits |
| H5 | Secure Gateway présente vraisemblablement (BCM/passerelle partagés avec les Nissan à Central Gateway), mais sur le 2023 documenté, Mode 01/09 et lectures `0x22` BMU ont répondu sans authentification ni bypass | Moyenne | R2, nuancé R3 | Étapes 2 et 5 sur le véhicule cible exact ; risque jugé faible pour Mode 01/09, faible-à-moyen pour `0x22`, élevé pour l'écriture (hors périmètre) |
| H6 | L'ancien chemin `761→762`/`2101` pourrait encore répondre sur le 2024 | Faible pour la transposition au 2024 | R1 (protocole ancien), test recommandé R3 | Étape 3 : un seul essai `2101`, puis abandon si `NO DATA` |
| H7 | `SOC_MAX` (`2201D0`) comme proxy de dégradation | Non validée — interprétation de l'explorateur 2025, qui déclare « I could not find SOH » | R2 | Aucune en v1 : le DID est journalisé en brut, jamais affiché comme santé batterie |

### 3.3 Inconnues résiduelles

Inconnues bloquantes pour la v1, à lever sur le véhicule ou par le choix d'adaptateur :

- `0xDB` est-il bien le BMU du 2024 nord-américain cible, et répond-il comme le 2023 documenté ? Toute la v1 en dépend. Résolution : étapes 5 et 6 de la première session.
- Politique gateway réelle par service/DID : `0x22` en session par défaut partout, ou `1003`/NRC `33` sur une partie, `0x19` filtré ? Résolution : observations journalisées des étapes 5 à 8.
- Validité des DID communautaires et de leurs échelles sur ce VIN : équation `2201D1` (octet `B` non défini dans les rapports), signification exacte de `220106`, `220178`, `220179`, `22010B`, `22022A`, `22F0D0` — formules absentes des rapports au-delà des étiquettes. Résolution : corrélation charge/décharge étalonnée, jamais d'équation figée avant.
- Existence d'un DID de capacité/SOH : non trouvé à ce jour. Bloquant pour toute promesse « santé batterie », gérable pour une v1 découverte + SOC/tensions.
- Tensions de cellules individuelles : seul min/max est évoqué en Gen4 ; une plage par cellule est inconnue. La topologie réelle (nombre de cellules, capteurs de température) n'est pas publiée ; ~95 cellules série est une inférence arithmétique (350 V ÷ ~3,7 V), pas une donnée.
- Capacité réelle de l'adaptateur qui sera utilisé (29 bits, `ATFCS*`, tampon BLE) : le modèle exact est absent des rapports. Sans adaptateur de référence, aucun silence n'est interprétable.

Inconnues gérables, dont la méthode de réponse est connue (étapes 1 à 7) :

- Le légiféré répond-il aussi en 11 bits `7DF` ? Inventaire complet des répondants `7E8`–`7EF` et `18DAF1xx`, Mode 09 complet.
- Identité de `0xDE` et `0xE5` (chargeur ? PHEV-ECU ?) via les DID `F187`/`F189`/`F191`.
- Températures du pack par diagnostic : décodage inconnu sur les deux générations. Candidat naturel du balayage DID (réponses qui varient lentement) ; aucune formule « octet − 40 » ne sera inventée.
- Présentation PCI du firmware de l'adaptateur choisi, syntaxe exacte des en-têtes 29 bits, fidélité octet pour octet de l'émulateur face au réel : réglés par journal brut et rejeu.
- Licence d'ELM327-emulator (`NOASSERTION` GitHub) et libellés SDK du jour d'installation : vérifications ponctuelles avant usage.

Inconnues reportées hors v1 :

- Contextes de réponse (READY vs charge AC vs CHAdeMO vs contact coupé), cadence de polling sûre à long terme, endormissement et 12 V en usage continu, foreground service éventuel.
- Variantes Europe/Japon 22,7 kWh (2025+) : mêmes DID, autres échelles ?
- Historique kilométrage/cycles/capacité : côté MUT ancien, items 400+ sans décodage OBD public ; équivalent Gen4 inconnu. Des « cycles » façon Watchdog resteraient une estimation d'app.
- Fournisseur et chimie des cellules du pack 20 kWh : aucune source ; seule une doc d'atelier GN ou l'étiquette du pack trancherait. Capacité utile de 16-17 kWh : estimation secondaire, confiance faible.
- Courant batterie : formule ancienne inutilisable, équivalent Gen4 non identifié — à découvrir parmi les DID longs qui changent vite, ou absent.

### 3.4 Contradictions entre rapports et arbitrages retenus

- Existence de DID Gen4 : R3 affirme « aucune liste publique » sur la base de dépôts vides ; R2 a trouvé la source primaire terrain (fil WiCAN, profil d'octobre 2025). Arbitrage : croire R2 sur l'existence des pistes, en gardant son niveau de confiance faible ; le plan de scan de R3 reste la méthode de revalidation.
- Héritage ancien : R2 pose « sans aucun héritage de PID » comme principe de planification ; R3 recommande le test `2101` à 3 trames. Arbitrage : architecture pensée pour zéro héritage, test fait une fois tôt car un positif changerait tout.
- Liste blanche R3 vs sa phase `2101` : incohérence interne à R3, résolue par l'exception fermée de la section 5.3.
- Adressage légiféré : R3 construit sa phase 1 en 11 bits d'abord ; R2 rapporte des PID de base obtenus en 29 bits et pose le 11 bits comme inconnue. Arbitrage : croire l'observation R2 ; le plan essaie les deux adressages et traite un silence 11 bits comme non concluant.
- Risque gateway : « faible » (R2) vs ton réservé (R3). Arbitrage : faible-à-moyen, validable seulement sur le véhicule cible ; un `NO DATA` uniforme reste ambigu.
- Séquence d'init ELM : R4 cite la séquence de kotlin-obd-api en exemple ; R3 prescrit `ATS1`, `ATAT0`, `ATSTFF` sur base datasheet. Arbitrage : suivre R3 pour la v1 découverte.
- Place de la session ELM dans R4 : module pur (§b) vs `:app` (§e). Arbitrage : module pur `:protocol`, seule option compatible avec le rejeu et l'émulateur.
- BLE « impossible » pour Watchdog : limite d'application et d'adaptateurs, pas du protocole. Conséquence : l'adaptateur de référence pour interpréter un silence est le meilleur disponible (STN, Classic d'abord), jamais un clone BLE seul.
- Points internes à R1 sans impact 2024 (10 CMU×8 vs items MUT CMU ID01…ID11, dénominateur SOH 38 vs 40 Ah, tables d'IDs du bus interne CMU discordantes) : rien n'en est transposé ; le bus interne BMU↔CMU n'est de toute façon pas accessible à la prise OBD.

## 4. Architecture

### 4.1 Les trois modules Gradle

| Module | Contenu | Environnement |
|---|---|---|
| `:protocol` | Interface `ElmTransport`, codec/tokeniseur du flux ELM, session ELM sérialisée, réassembleur ISO-TP tolérant, client OBD légiféré + UDS lecture avec liste blanche codée en dur, planificateur de scan en phases, définitions véhicule déclaratives et décodeurs, schéma et écriture du journal JSONL, transport de rejeu | Kotlin/JVM pur, JDK seul, aucune dépendance Android |
| `:transport-android` | `ClassicSppTransport` (RFCOMM natif), `BleTransport` (Kable), `TcpTransport` (émulateur/dev), scanners BLE et appareils Classic appairés, permissions Bluetooth | Android |
| `:app` | Compose : choix d'adaptateur, console AT brute et statut de scan d'abord, signaux décodés ensuite avec badge de confiance ; saisies « vérité terrain » ; export des journaux | Android |

Principe directeur (R4) : tout ce qui peut être faux sans voiture vit dans `:protocol`, pour être prouvé en JVM par tests unitaires, émulateur et rejeu avant tout branchement. Le découpage fonctionnel transport / session ELM / protocole OBD-UDS / décodeurs véhicule / journal vit comme packages distincts dans `:protocol`, sans multiplier les modules Gradle prématurément.

Android ne contient que : scan, appairage, GATT et RFCOMM, permissions, stockage des fichiers de session, interface utilisateur.

### 4.2 Packages internes de `:protocol`

Racine des packages : `ca.mrklar.outlander.protocol` (nom de paquet de travail, ajustable à l'implémentation sans effet sur le design).

| Package | Responsabilité |
|---|---|
| `transport` | Interface `ElmTransport`, interface `ElmScanner`, modèle `ElmDevice`, transport de rejeu (`ReplayTransport`) lisant des captures |
| `elm` | Tokeniseur du flux ELM (prompt `>`, écho, `SEARCHING...`, `NO DATA`, `CAN ERROR`, `BUFFER FULL`, `?`, `OK`), `ElmSession` sérialisée, séquence d'init, matrice de capacités adaptateur |
| `isotp` | Réassembleur ISO-TP tolérant (octets PCI présents ou déjà retirés), trames CAN typées, gestion des lignes indexées vs en-têtes CAN |
| `uds` | `EcuChannel`, `UdsClient`, client OBD légiféré, liste blanche et liste noire, classification des réponses et des NRC |
| `scan` | Plan de scan en phases numérotées (section 7), rythme d'émission, critères d'arrêt, tranches reprenables du balayage borné |
| `vehicle` | `SignalDefinition`, registre de signaux, décodeurs data-driven, statuts de confiance |
| `journal` | Schéma JSONL v1, `DiscoveryJournal`, anonymisation VIN, dérivation CSV d'analyse |

### 4.3 Interfaces clés

Les signatures ci-dessous fixent les contrats ; les types auxiliaires (`ElmDevice`, `ElmResponse`, `EcuChannel`, etc.) sont définis dans les mêmes packages.

Transport et découverte d'adaptateurs :

```kotlin
package ca.mrklar.outlander.protocol.transport

import kotlinx.coroutines.flow.Flow

/** Flux d'octets bruts vers/depuis l'adaptateur. Les chunks sont arbitraires :
 *  aucun alignement sur les lignes ELM n'est jamais supposé (R4). */
interface ElmTransport {
    val incoming: Flow<ByteArray>
    suspend fun write(data: ByteArray)
    suspend fun close()
}

enum class ElmTransportKind { BLE, CLASSIC_SPP, TCP, REPLAY }

data class ElmDevice(
    val id: String,            // adresse MAC ou identifiant stable
    val displayName: String,
    val kind: ElmTransportKind,
)

/** BLE découvert par scan + Classic déjà appairés au téléphone. */
interface ElmScanner {
    fun scan(): Flow<ElmDevice>
    suspend fun pairedClassicDevices(): List<ElmDevice>
}
```

Session ELM sérialisée :

```kotlin
package ca.mrklar.outlander.protocol.elm

enum class ElmStatus { POSITIVE, NRC, NO_DATA, TIMEOUT, ELM_ERROR, BUFFER_FULL }

data class ElmResponse(
    val command: String,
    val rawText: String,              // texte verbatim avant tout parsing
    val lines: List<String>,
    val status: ElmStatus,
    val elmTokens: List<String>,      // SEARCHING..., NO DATA, CAN ERROR, BUFFER FULL, ?, OK...
    val durationMs: Long,
)

/** Acteur sérialisé (Mutex) : une seule commande en vol, attente du prompt '>',
 *  timeouts explicites. La session ELM ne connaît que du texte : la sémantique
 *  « réponse intermédiaire, continuer d'attendre » (NRC 0x78 responsePending)
 *  est fournie par l'appelant UDS via le crochet [continuesWhile], sans jamais
 *  renvoyer la requête par-dessus — l'attente reste dans la même transaction. */
class ElmSession(
    private val transport: ElmTransport,
    private val journal: DiscoveryJournal,
) {
    suspend fun transact(
        command: String,
        timeoutMs: Long,
        continuesWhile: (ElmResponse) -> Boolean = { false },
    ): ElmResponse
    suspend fun initialize(): AdapterCapabilities
}

/** Matrice établie à chaque connexion : ATI, STI (≠ '?' ⇒ STN), et le sort
 *  (OK / '?') de chaque commande critique. Le plan de scan se dégrade d'après elle. */
data class AdapterCapabilities(
    val ati: String,
    val sti: String?,                 // non nul et ≠ "?" ⇒ adaptateur STN
    val atrvVolts: Double?,
    val okCommands: Set<String>,      // ex. ATFC*, ATCRA, ATCEA, ATCP, ATSP7 ayant répondu OK
    val deniedCommands: Set<String>,  // commandes ayant répondu '?'
    val detectedProtocol: String?,    // résultat ATDP/ATDPN après auto-détection
)
```

Canal ECU et client UDS lecture seule :

```kotlin
package ca.mrklar.outlander.protocol.uds

/** Un canal = une cible d'interrogation. Les identifiants sont des entiers CAN :
 *  txId 0x761 (11 bits) ou 0x18DADBF1 (29 bits), rxId symétrique,
 *  fcHeader = ID de requête physique sur lequel émettre le Flow Control. */
data class EcuChannel(
    val txId: Long,
    val rxId: Long,
    val fcHeader: Long,
    val extended29Bit: Boolean,
)

sealed interface UdsReply {
    data class Positive(val payload: ByteArray) : UdsReply
    data class Negative(val service: Int, val nrc: Int) : UdsReply  // 7F ss nrc ; 0x78 = réponse intermédiaire attendue via le crochet continuesWhile d'ElmSession
    data object NoData : UdsReply                                    // jamais interprété comme « ECU absent »
    data class ElmFailure(val response: ElmResponse) : UdsReply
}

/** Applique ATSH/ATCRA/ATFCSH/ATFCSD/ATFCSM1 par cible et refuse tout premier
 *  octet hors liste blanche AVANT émission (section 5). */
class UdsClient(private val session: ElmSession) {
    suspend fun readDataByIdentifier(channel: EcuChannel, did: Int): UdsReply          // 22 xx xx
    suspend fun testerPresent(channel: EcuChannel): UdsReply                            // 3E 00 uniquement
    suspend fun diagnosticSessionControl(channel: EcuChannel, subFunction: Int): UdsReply // 10 01 ou 10 03 physique seulement
    suspend fun readDtc(channel: EcuChannel, subFunction: Int, mask: Int): UdsReply     // 19 01/02/0A
    suspend fun legacyRead(channel: EcuChannel, requestHex: String): UdsReply           // exception fermée 2101/2102 → 761
}
```

Définitions de signaux et journal :

```kotlin
package ca.mrklar.outlander.protocol.vehicle

enum class SignalConfidence { COMMUNAUTAIRE_NON_VALIDE, CORRELE_TABLEAU_DE_BORD, VERIFIE }

data class SignalDefinition(
    val id: String,                   // ex. "bmu.soc.did01d1"
    val label: String,
    val channel: EcuChannel,
    val requestHex: String,           // ex. "2201D1"
    val byteOffset: Int,              // dans la charge utile réassemblée, convention documentée par définition
    val byteLength: Int,
    val scale: Double,
    val offset: Double,
    val unit: String,
    val source: String,               // référence citée : rapport, fil, session de corrélation
    val confidence: SignalConfidence,
)

fun interface SignalDecoder {
    fun decode(definition: SignalDefinition, reassembledPayload: ByteArray): Double?
}
```

```kotlin
package ca.mrklar.outlander.protocol.journal

/** append() écrit et flush la ligne immédiatement : une coupure ne perd
 *  au pire que la transaction en cours. */
interface DiscoveryJournal {
    suspend fun append(event: JournalEvent)
    suspend fun close()
}
```

Le décodé est découplé de la requête qui l'a produit : un registre de métriques nommées (idée OVMS, R4) associe chaque `SignalDefinition` à la dernière valeur décodée et à son statut, indépendamment du canal ayant servi à l'obtenir.

### 4.4 Transports Android

- **Bluetooth Classic** : UUID SPP `00001101-0000-1000-8000-00805F9B34FB`, appareils `bondedDevices` d'abord, `cancelDiscovery()` avant `connect()`, RFCOMM natif sans librairie.
- **BLE** : aucun UUID codé en dur seul. Table de couples service/caractéristique connus (`FFE0/FFE1`, `18F0` + `2AF0/2AF1`, Vgate propriétaire, Nordic UART `6E400001/2/3`, OBDLink BLE), puis repli générique notify+write après `discoverServices()`. MTU 247/512 demandé, écritures découpées en `mtu−3`, framing uniquement par le prompt `>` de l'ELM. La famille d'UUID réelle de l'adaptateur du propriétaire reste à confirmer (voir section 14).
- **TCP** : même interface `ElmTransport`, pour l'émulateur Ircama en développement ; jamais exposé comme transport véhicule.

### 4.5 Flux de données : connexion → scan → journal → décodage

1. **Découverte** : `ElmScanner` émet les adaptateurs BLE vus et les Classic appairés ; l'utilisateur choisit dans `:app`.
2. **Connexion** : `:transport-android` ouvre le transport choisi et le remet à `ElmSession` (`:protocol`).
3. **Initialisation** : `ElmSession.initialize()` joue la séquence de la section 6.1, journalise chaque commande AT et produit la `AdapterCapabilities`.
4. **Scan** : le planificateur (`scan`) déroule les phases de la section 7 compatibles avec la matrice de capacités. Chaque requête passe par `UdsClient`, qui configure les en-têtes par canal, vérifie la liste blanche, puis délègue l'aller-retour texte à `ElmSession`.
5. **Journal** : `ElmSession` append chaque transaction au `DiscoveryJournal` avant de rendre la réponse au planificateur — le brut est donc persisté même si le décodage échoue ensuite.
6. **Réassemblage** : `isotp` transforme les lignes ELM en charges utiles (gestion PCI présent/absent), `uds` classe positif/NRC/`no_data`.
7. **Décodage** : les `SignalDefinition` applicables décodent les charges utiles positives ; les valeurs partent vers `:app` avec leur badge de confiance. Une charge utile sans définition reste visible en brut dans la console et le journal.

### 4.6 Librairies retenues et rejetées

| Décision | Librairie | Motif |
|---|---|---|
| Retenue | Kable 0.45.0 épinglée (Apache-2.0) | BLE scan+connexion en `Flow`, aligné coroutines ; API 0.x isolée derrière `BleTransport` |
| Repli si Kable déçoit sur le matériel réel | Nordic Android-BLE-Library 2.11.0 + Scanner-Compat | Plus éprouvée, plus verbeuse |
| Retenue (tests seulement) | Ircama/ELM327-emulator | Seul émulateur UDS multi-trames scriptable ; licence `NOASSERTION` à vérifier dans le dépôt avant tout usage hors test local |
| Non importée | eltonvs/kotlin-obd-api 1.4.1 | Mode 01 typé seulement ; séquence d'init lue en référence, dépendance JitPack non justifiée |
| Rejetées | pires/obd-java-api (archivé 2017), RxAndroidBle (impose RxJava), `BluetoothGatt` en direct, OBDSim et simulateurs matériels | Voir R4 |
| Étude seulement | ObdMetrics (Mode 01), AndrOBD (**GPL-3.0** : outil de capture utilisable tel quel, aucun code copié), OVMS/EVNotify (idées d'architecture) | Voir R4 |

Les choix de sérialisation JSON et d'injection de dépendances sont absents des rapports : ils relèvent de l'implémentation, sans impact protocole, et seront consignés dans le code lors du jalon M0/M1.

## 5. Sécurité lecture seule

La lecture seule est un invariant non négociable : elle est codée en dur dans `UdsClient`, vérifiée par test de propriété (section 10), et aucun chemin de code ne peut émettre une requête hors liste blanche.

### 5.1 Liste blanche exacte

Modes OBD légiférés autorisés (premier octet de la requête) :

| Mode | Usage dans le projet |
|---|---|
| `01` | PID courants, dont `0100`/`0120`/`0140`/`0160`/`0180`, `0101`, `0142`, `015B` si déclaré |
| `02` | Freeze frame (lecture) |
| `03` | DTC confirmés (lecture) |
| `07` | DTC en attente (lecture) |
| `09` | Informations véhicule : `0900`, `0902` (VIN), `0904`, `0906`, `090A` |
| `0A` | DTC permanents (lecture) |

Services UDS autorisés, avec sous-fonctions fermées :

| Service | Sous-fonctions autorisées | Conditions |
|---|---|---|
| `0x10` DiagnosticSessionControl | `01` (session par défaut), `03` (session étendue) | `10 03` uniquement en adressage physique, un seul ECU, dans les conditions de l'étape 8 ; jamais en adressage fonctionnel |
| `0x19` ReadDTCInformation | `01`, `02`, `0A` | Lecture uniquement (ex. `1901FF`, `1902FF`, `190A`) |
| `0x22` ReadDataByIdentifier | DID 16 bits, un DID par requête | Cœur de la découverte |
| `0x3E` TesterPresent | `00` (avec réponse) en sonde de présence ; `80` (réponse supprimée) uniquement en keepalive de session étendue ouverte | Jamais sonder avec `3E80` : une absence de réponse y est indistinguable d'un silence |

### 5.2 Liste noire exacte

Services UDS interdits, codés en refus explicite : `0x27` (SecurityAccess), `0x2E` (WriteDataByIdentifier), `0x31` (RoutineControl), `0x2F` (InputOutputControlByIdentifier), `0x14` (ClearDiagnosticInformation), `0x85` (ControlDTCSetting), `0x28` (CommunicationControl), `0x11` (ECUReset), `0x34`–`0x38` (téléchargement/transfert), `0x23` (ReadMemoryByAddress), `0x3D` (WriteMemoryByAddress), `0x2A` (ReadDataByPeriodicIdentifier), `0x2C` (DynamicallyDefineDataIdentifier), `0x87` (LinkControl), et la sous-fonction `10 02` (session programmation).

Modes OBD légiférés interdits : `04` (effacement DTC/informations), `08` (contrôle de systèmes).

Tout premier octet qui n'est ni dans la liste blanche ni couvert par l'exception de la section 5.3 est refusé, qu'il figure ou non dans la liste noire nommée : la règle est une liste blanche, pas une liste noire.

### 5.3 Exception fermée `2101`/`2102` vers `761`

Le service `0x21` n'est pas blanchi en général. L'exception est explicite et fermée :

- destination unique : canal 11 bits `761→762` ;
- requêtes uniques : `2101` d'abord, un seul essai ; `2102` uniquement si `2101` a répondu positivement (`61 01…`) ;
- aucun balayage `0x21` générique, aucune autre sous-requête `21xx`, aucune autre cible.

Cette exception résout l'incohérence interne de R3 (liste blanche sans `0x21`, mais phase `2101` prescrite) et correspond au test de falsification de l'héritage ancien modèle : probabilité faible, coût de 3 trames, valeur décisive si positif.

### 5.4 Mécanisme de refus avant émission

- `UdsClient` est l'unique point d'émission de requêtes non-AT. Avant tout envoi, il extrait le premier octet de la requête et le confronte à la liste blanche (avec sous-fonction pour `0x10`, `0x19`, `0x3E`) ou à l'exception fermée (service `0x21`, canal `761`, requête exactement `2101` ou `2102` conditionné).
- Un refus lève une erreur typée, journalisée comme événement de session, et rien n'est écrit sur le transport. Le refus est donc observable dans le journal, pas silencieux.
- La console AT brute de `:app` passe par le même garde-fou pour toute ligne qui n'est pas une commande `AT`/`ST` : l'interface ne peut pas servir à contourner la liste blanche.
- Les commandes AT elles-mêmes sont limitées aux familles utilisées par le protocole d'interrogation (section 6) ; aucune commande AT d'écriture en mémoire de l'adaptateur (type `ATPP`/`ATWS`-like) ne fait partie du plan.

### 5.5 Critères d'arrêt de scan

Arrêt immédiat de la tranche en cours, retour à l'état sûr et journalisation de la cause si l'un des événements suivants survient :

- 3 `CAN ERROR` consécutifs ;
- `ATRV` < 12,0 V hors état READY ;
- NRC `0x78` en boucle sans réponse finale ;
- nouveau voyant ou message au combiné, ou tout comportement anormal du véhicule ;
- démarrage du moteur thermique en espace clos (prévenu par la procédure de l'étape 0 : travail à l'extérieur ou garage ventilé uniquement).

En fin de session, quelle qu'en soit la cause : envoi de `1001` si une session étendue a été ouverte, lecture DTC finale, puis retrait physique de l'adaptateur (broche 16 = +12 V permanent).

## 6. Protocole d'interrogation

Rythme général, partout : une seule commande en vol, au moins 100-200 ms de pause après chaque réponse, attentes généreuses en identification (`ATSTFF` = 1020 ms), réduites à 400-500 ms seulement en balayage. Sur NRC `7F xx 78` (responsePending), la session attend la réponse finale et ne renvoie jamais par-dessus.

### 6.1 Initialisation ELM327

Séquence d'init de référence (R3, base datasheet ; la séquence de kotlin-obd-api citée par R4 n'est qu'un exemple de librairie et n'est pas retenue) :

1. Purge du transport pendant 300 ms.
2. `ATZ` — reset ; prompt `>` attendu sous 2 s.
3. Empreinte : `ATI` (identification), `STI` (réponse ≠ `?` ⇒ adaptateur STN), `ATRV` (tension 12 V lue par l'adaptateur).
4. Configuration : `ATE0` (écho off), `ATL0` (linefeeds off), `ATS1` (espaces on, lisibilité découverte), `ATH1` (en-têtes CAN on), `ATCAF1` (formatage CAN auto), `ATCFC1` (flow control CAN on), `ATAT0` (timing adaptatif off, déterministe), `ATSTFF` (timeout maximal 1020 ms, anti-troncature multi-ECU), `ATCEA` (`?` toléré et journalisé).
5. Auto-détection unique : `ATSP0` puis premier `0100` ; le protocole détecté est figé et journalisé via `ATDP`/`ATDPN`. L'auto-détection n'est jamais relancée en cours de session.

Chaque `OK`/`?` de cette séquence alimente la matrice de capacités. `STPX` (commande STN) est traité en bonus détecté, jamais en prérequis.

### 6.2 Matrice de capacités adaptateur

À chaque connexion, le sort de chaque commande critique est consigné : `ATFC*` (flow control manuel), `ATCRA` (filtre de réception), `ATCEA`, `ATCP` (priorité CAN 29 bits), `ATSP7` (protocole 29 bits). Le plan de scan se dégrade d'après cette matrice — par exemple, pas de phase 29 bits si `ATCP` répond `?`. Les clones peuvent répondre `OK` sans appliquer la commande : la matrice est donc une condition nécessaire, jamais une preuve suffisante, et tout silence obtenu avec un clone reste non concluant tant qu'il n'a pas été répété avec l'adaptateur de référence (STN/OBDLink ou équivalent vLinker, Classic d'abord).

### 6.3 Adressage 11 bits

- Légiféré fonctionnel : `ATSP6`, `ATSH7DF`, sans `ATCRA` restrictif, afin de voir tous les répondants `7E8`–`7EF` (chaque en-tête distinct = un ECU émission).
- Héritage ancien modèle : `ATSP6`, `ATFCSH761`, `ATFCSD300000`, `ATFCSM1`, `ATSH761`, `ATCRA762`.
- Présence UDS : paires plausibles seulement, dans l'ordre — normatives `7E0→7E8` … `7E7→7EF` ; Alliance documentées `797→79A`, `79B→7BB`, `784→78C`, `743→763`, `740→760`. Aucun balayage `0x000`–`0x7FF` ; au-delà des paires connues, seulement la plage `0x700`–`0x7FF` avec offsets de réponse +8/+1/+0x20/+3 si nécessaire.
- Un silence en 11 bits est non concluant (R2 a observé les PID de base en 29 bits) : il ne clôt ni la phase légiférée ni la session.

### 6.4 Adressage 29 bits, cible `0xDB`

Hypothèse principale (H1) : ISO 15765-4 29 bits, adresses logiques d'ECU `0xDB` (BMU présumé), `0xDE` et `0xE5`. Les identifiants CAN étendus suivent le schéma ISO 15765 `18DA<dest><src>` pour le physique et `18DB33F1` pour le fonctionnel, le testeur étant `F1`.

Cycle propre pour la cible `0xDB` en adressage physique :

| Commande | Rôle |
|---|---|
| `ATSP7` | Protocole ISO 15765-4 CAN 29 bits |
| `ATCP18` | Octet de priorité CAN `0x18` pour les en-têtes étendus |
| `ATSH DA DB F1` | En-tête de requête physique → `18DADBF1` |
| `ATCRA DA F1 DB` | Filtre de réception → réponses `18DAF1DB` |
| `ATFCSH DA DB F1` | Flow Control émis sur l'adresse physique de requête → `18DADBF1` |
| `ATFCSD 30 00 00` | Données de Flow Control : ContinueToSend, BS=0, STmin=0 |
| `ATFCSM1` | Mode Flow Control défini par l'utilisateur |

Puis sonde `3E00`, puis `22F190`. En cas de silence en physique, variante fonctionnelle : `ATSH DB 33 F1` (→ `18DB33F1`), `ATCRA DA F1 DB`, Flow Control maintenu sur l'adresse physique `18DADBF1`, sonde `22F190` uniquement.

Le même traitement s'applique ensuite à `0xDE` (`ATSH DA DE F1`, `ATCRA DA F1 DE`, FC `18DADEF1`) et à `0xE5` (`ATSH DA E5 F1`, `ATCRA DA F1 E5`, FC `18DAE5F1`) — construction par le schéma R2, à valider sur le véhicule.

La syntaxe exacte des en-têtes 29 bits varie selon les clones : chaque `OK` est validé et journalisé. La forme « sans filtre » 29 bits précise est absente des rapports ; elle sera consignée telle qu'observée lors de la première session, sans être inventée ici.

### 6.5 Flow control manuel

C'est le point dur ELM327 du projet. Avec `ATCAF1`+`ATCFC1`, l'ELM gère l'ISO-TP côté bus, mais sa dérivation automatique de l'ID de Flow Control ne fonctionne que dans la convention « réponse = requête + 1 » (type `761→762`). Elle échoue en adressage fonctionnel 29 bits, où la trame de Flow Control doit partir sur l'adresse physique (`18DADBF1`) alors que la requête est partie sur `18DB33F1`.

Recette par ECU, appliquée systématiquement par `UdsClient` à chaque changement de canal :

1. `ATFCSH <ID requête physique>`
2. `ATFCSD 30 00 00`
3. `ATFCSM1`

Sur réponse longue tronquée (`BUFFER FULL`), la trame est rejetée et la requête est retentée une fois avec Flow Control bridée : `ATFCSD 30 0A 0A` (BS=10, STmin=10 ms), afin de ralentir l'émetteur sous la capacité du tampon de l'adaptateur.

### 6.6 Réassemblage ISO-TP tolérant

Côté hôte, le comportement varie selon le firmware : certains adaptateurs présentent les octets PCI ISO-TP (`10…` First Frame, `21…` Consecutive Frames), d'autres les retirent, d'autres livrent des lignes indexées (`0:`, `1:`…) plutôt que des en-têtes CAN. Le design n'en présume aucun :

- le texte brut de chaque réponse est journalisé verbatim avant parsing ;
- le réassembleur accepte les deux présentations (PCI présent ou déjà retiré) et les deux formes de lignes (indexées ou avec en-têtes CAN, `ATH1`) ;
- la longueur annoncée par la First Frame, quand elle est visible, sert de contrôle de complétude : une charge utile incomplète est classée tronquée, jamais décodée partiellement ;
- une réponse multi-ECU est éclatée par en-tête de réponse avant réassemblage.

L'oracle de ce réassembleur est la trame d'exemple de l'ancien modèle (section 10) : `762 10 37 61 01…` doit produire 55 octets utiles et les items 27+28 = `01 54`, soit 34,0 Ah.

## 7. Plan de première session véhicule

Objectif unique de la première session : transformer les trois inconnues — adressage réel, politique gateway, DID valides — en observations journalisées. Aucun décodage n'est figé pendant cette session.

### 7.1 Procédure maison (conditions et vérité terrain)

Checklist affichée par l'app avant tout lancement de scan :

- véhicule dehors ou garage ventilé, stationné, frein de parc serré, capot fermé, personne sous le véhicule ;
- état READY (alternative IG-ON seulement courte, avec mainteneur 12 V) — jamais de scan en roulant ;
- adaptateur de référence branché ;
- photo ou note du tableau de bord au début et à la fin : SOC affiché %, autonomie, état de charge (AC/CHAdeMO ou aucun). Ces valeurs sont saisies dans l'app et horodatées dans le journal comme vérité terrain ; c'est contre elles, et elles seules, que les DID seront corrélés ;
- `ATRV` relu automatiquement toutes les ~30 s pendant la session ;
- lecture DTC `1902FF` avant et après la première session, pour prouver l'absence d'effet de bord — sans jamais effacer quoi que ce soit ;
- en sortant : retrait de l'adaptateur (broche 16 = +12 V permanent), export du fichier JSONL pour analyse et rejeu au bureau.

### 7.2 Phases numérotées

**Phase 0 — Conditions.** Procédure maison ci-dessus ; READY à l'extérieur.

**Étape 1 — Init et empreinte adaptateur.** Séquence de la section 6.1 : purge 300 ms, `ATZ`, `ATI`/`STI`/`ATRV`, configuration, `ATCEA`, puis `ATSP0` + premier `0100`, protocole figé via `ATDP`/`ATDPN`. Matrice de capacités complète journalisée.

**Étape 2 — Légiféré 11 bits puis 29 bits.** `ATSP6` + `ATSH7DF`, sans `ATCRA` restrictif : `0100`, puis `0120`/`0140`/`0160`/`0180` tant que le bit de chaînage est présent ; `0900`, `0902` (VIN — test du réassemblage ISO-TP sur une réponse longue connue), `0904`, `0906`, `090A` ; `03`, `07`, `0A` ; `0101`, `0142`, tous les PID déclarés une fois en brut, `015B` si le bit `5B` est déclaré dans `0140`. Chaque en-tête `7E8`–`7EF` distinct identifie un ECU émission. Le même bloc est refait en 29 bits (`ATSP7`, `ATCP18`, en-tête fonctionnel `18DB33F1`) pour lister les répondants `18DAF1xx` : passage obligé, le silence 11 bits étant non concluant.

**Étape 3 — Test d'héritage ancien (une fois).** `ATSP6` ; `ATFCSH761` ; `ATFCSD300000` ; `ATFCSM1` ; `ATSH761` ; `ATCRA762` → `2101`. Un seul essai. `2102` seulement si `2101` répond `61 01…`.

**Étape 4 — Présence UDS 11 bits (paires plausibles seulement).** Par cible : `ATSH`/`ATCRA`/`ATFCSH`/`ATFCSD 30 00 00`/`ATFCSM1`, sonde `3E00` (réponse `7E 00`), à défaut `1001` (`50 01…`). Paires dans l'ordre de la section 6.3. Jamais de sonde en `3E80`.

**Étape 5 — Cible 29 bits `0xDB` (hypothèse principale).** Cycle propre de la section 6.4 : physique d'abord (`3E00`, puis `22F190`), variante fonctionnelle ensuite si silence. Même traitement pour `0xDE` puis `0xE5`.

**Étape 6 — Identification de chaque répondant.** Un DID à la fois, en session par défaut : `22F186` (session active, sonde rapide), `22F190` (VIN), `22F187`, `22F189`, `22F191`, `22F18C`, puis la plage `F180`–`F19F` complète ; DTC en lecture `1901FF`, `1902FF`, `190A`. C'est cette étape qui nomme les ECU (`0xDB` = BMU ?) sans deviner. Toute réponse longue tronquée (`BUFFER FULL`) est rejetée et retentée une fois en `ATFCSD 30 0A 0A`.

**Étape 7 — DID Gen4 connus, avant tout balayage aveugle.** Sur l'ECU identifié batterie (prioritairement `0xDB`) : `2201D1`, `2201D0`, `220106`, `220178`, `220179`, puis `22010B`, `22022A`, `22F0D0`. Chacun est lu deux fois à quelques minutes d'intervalle, avec le SOC tableau de bord noté aux mêmes instants : un DID utile doit répondre et varier de façon cohérente. Le mapping DID→`0xDE`/`0xE5` est absent des rapports : si un DID répond `7F 22 31` sur `0xDB`, un essai sur `0xDE`/`0xE5` est journalisé comme hypothèse, pas comme fait.

**Étape 8 — Amorce de balayage borné, seulement si le temps et le 12 V le permettent.** Plage `F100`–`F1FF` sur l'ECU batterie, un DID par requête, tranches de 500 DID maximum, reprenables d'une session à l'autre grâce au journal, `ATST64`/`ATST7D` (400-500 ms). Session étendue (`1003` physique, keepalive `3E80` toutes les 2 s, retour `1001` explicite, un seul ECU ; délai S3 ~5 s comme filet de sécurité) uniquement pour retester les DID ayant donné `7F 22 7F`/`7E`/`33` en session par défaut. Jamais de `0x27`.

Critères d'arrêt : section 5.5.

### 7.3 Interprétation des issues possibles

| Observation | Conclusion | Suite |
|---|---|---|
| `2101` → `61 01…` multi-trames sur `762` | Héritage Mitsubishi partiel contre toute attente | Décoder prudemment (SOC item 0, tension pack items 8+9) et confronter au pack 350 V : si la tension décodée est proche de la réalité, tester `2102` sans présumer 80 cellules ; rien n'est « vérifié » sans corrélation |
| `0xDB` répond en 29 bits, DID `F1xx` et DID Gen4 positifs et variant entre deux lectures | Voie principale confirmée sur ce véhicule ; la gateway ne bloque pas la lecture de base | Corréler sur charge/décharge étalonnée avant de figer la moindre équation ; construire les décodeurs |
| `7F 22 33` sur certains DID | Ces DID existent mais sont verrouillés | Consigner « existe-verrouillé », ne jamais tenter `0x27` ; v1 = légiféré + DID ouverts |
| `7F 22 31` généralisé mais `F1xx` répond | Bonne adresse et bonne session ; cartographie communautaire fausse ou partielle pour ce véhicule/marché | Balayage borné méthodique (étape 8) aux sessions suivantes |
| `7F 22 7F`/`7E` | Service ou DID peut-être réservé à la session étendue | Liste « à retester en `1003` », un ECU à la fois |
| Légiféré parfait, phases UDS en `NO DATA` uniforme (ou seul l'ECU moteur répond) | Symptôme compatible gateway ou artefact d'adaptateur/en-têtes — `NO DATA` ≠ absence | Répéter avec l'adaptateur de référence STN/Classic et des en-têtes vérifiés avant de conclure ; si le résultat se reproduit proprement, c'est un résultat documenté (filtrage du non-légiféré), pas un mur à contourner en v1 |
| Silence aussi en légiféré 29 bits | Problème plus bas : protocole, syntaxe `ATCP`/`ATSH` du clone, état du véhicule | Revenir à l'étape 1 et à la matrice de capacités ; aucune conclusion sur le véhicule |

## 8. Décodeurs data-driven

Inspiration : définitions déclaratives à la EVNotify/Torque/AndrOBD (R4). Aucune formule ne vit dans la logique d'interrogation : chaque sonde est une donnée, le décodeur est générique.

### 8.1 Schéma d'une `SignalDefinition`

| Champ | Type | Rôle |
|---|---|---|
| `id` | `String` | Nom stable du signal dans le registre, ex. `bmu.soc.did01d1` ; le décodé est découplé de la requête qui l'a produit |
| `label` | `String` | Libellé affiché dans l'interface, en français |
| `channel` | `EcuChannel` | En-têtes TX/RX/FC de la cible (équivaut aux champs en-têtes TX/RX/FC des définitions de sonde) |
| `requestHex` | `String` | Requête hexadécimale complète, ex. `2201D1` ; soumise à la liste blanche comme toute émission |
| `byteOffset` | `Int` | Offset du premier octet utile dans la charge utile réassemblée ; la convention de comptage est documentée dans chaque définition (ex. ancien modèle : items comptés à partir de 0 **après** `61 01`) |
| `byteLength` | `Int` | Nombre d'octets du champ (1, 2…) ; assemblage big-endian sauf mention contraire documentée |
| `scale` | `Double` | Valeur physique = `valeur_brute × scale + offset` |
| `offset` | `Double` | Voir `scale` |
| `unit` | `String` | Unité affichée (`%`, `V`, `Ah`…) |
| `source` | `String` | Référence citée obligatoirement : rapport et section, fil communautaire, ou identifiant de session de corrélation |
| `confidence` | `SignalConfidence` | Statut de validation (section 8.2), affiché en badge à côté de chaque valeur |

Une définition dont l'équation fait intervenir autre chose qu'un affine `brut × scale + offset` (par exemple l'équation empirique `B*0.64−19.2` du DID `2201D1`, où la définition exacte de l'octet `B` est absente des rapports) est d'abord enregistrée avec ses seuls champs d'interrogation (`channel`, `requestHex`) et décodée en brut journalisé : l'échelle n'est remplie qu'après observation des octets réels sur le véhicule cible.

### 8.2 Statuts de confiance et règle de promotion

Trois statuts, affichés tels quels dans l'interface :

1. `COMMUNAUTAIRE_NON_VALIDE` — « piste communautaire » : la définition vient d'une source externe non revérifiée sur le véhicule cible. La valeur peut être affichée en brut, jamais présentée comme une mesure.
2. `CORRELE_TABLEAU_DE_BORD` — le DID répond sur le véhicule cible et sa valeur brute varie de façon cohérente avec une vérité terrain journalisée (SOC affiché, autonomie) sur au moins deux lectures à des instants distincts.
3. `VERIFIE` — l'équation complète (offset d'octet, longueur, échelle, unité) a été corrélée sur une charge/décharge étalonnée du véhicule cible, avec la session de corrélation citée dans `source`.

Règles de promotion :

- Aucune formule ne passe en `VERIFIE` sans corrélation sur le véhicule cible.
- La promotion est un changement de définition explicite et relu, jamais un effet automatique du code : elle cite la session et les vérités terrain utilisées.
- Aucun SOH n'est affiché sans dénominateur nommé et sourcé (enseignement du débat 38 vs 40 Ah sur l'ancien modèle, R1) ; en v1, aucun SOH n'est affiché du tout (section 2.2).
- Une valeur au statut `COMMUNAUTAIRE_NON_VALIDE` reste visuellement distincte (badge) partout où elle apparaît.

### 8.3 DID Gen4 connus : pistes communautaires

Tous les DID ci-dessous entrent dans le registre au statut `COMMUNAUTAIRE_NON_VALIDE`, source R2 (profil communautaire d'octobre 2025 sur un Outlander PHEV 2023). Leur auteur demande de les prendre « with a grain of salt ».

| Requête | Étiquette rapportée | Équation rapportée | État des connaissances |
|---|---|---|---|
| `2201D1` | SOC | `B*0.64−19.2` (empirique) | Octet `B` non défini dans les rapports ; aussi cité par R1 |
| `2201D0` | « SOC_MAX » selon l'auteur | Aucune | Interprétation comme proxy de dégradation non validée ; journalisé en brut, jamais affiché comme santé |
| `220106` | Tension HV | Absente des rapports | Échelle inconnue ; à confronter au pack 350 V |
| `220178` | Tension cellule max | Absente des rapports | Échelle inconnue |
| `220179` | Tension cellule min | Absente des rapports | Échelle inconnue |
| `22010B` | Citée sans étiquette exploitable | Aucune | À identifier par observation |
| `22022A` | Citée sans étiquette exploitable | Aucune | À identifier par observation |
| `22F0D0` | Citée sans étiquette exploitable | Aucune | À identifier par observation |

S'y ajoutent les DID d'identification standard de l'étape 6 (`F180`–`F19F`, dont `F186` session active, `F190` VIN, `F187`, `F189`, `F191`, `F18C`), qui servent à nommer les ECU et ne sont pas des signaux batterie.

### 8.4 Interdiction des constantes ancien modèle en dur

Sont interdits dans le code et les définitions, sous quelque forme que ce soit :

- 80 cellules, 300 V, 12/13,8 kWh (topologie et pack de l'ancienne génération) ;
- dénominateur SOH de 38 ou de 40 Ah (déjà disputé sur l'ancien modèle) ;
- tables d'IDs du bus interne CMU et tout décodage du bus interne BMU↔CMU, inaccessible à la prise OBD ;
- la formule de courant ancienne (items 14+15), tronquée et inutilisable ;
- toute formule de température inventée (type « octet − 40 ») : les températures par diagnostic sont un décodage inconnu sur les deux générations.

Les seules constantes véhicule admises en v1 sont les données documentées du pack cible — 20 kWh brut, 350 V, 57,1 Ah (Amérique du Nord 2023-2024) — et elles servent de contrôle de plausibilité, jamais d'entrée de décodage. Le nombre de cellules n'est pas publié ; ~95 cellules série est une inférence arithmétique (350 V ÷ ~3,7 V) et ne doit apparaître ni comme constante ni comme affichage. La capacité utile de 16-17 kWh est une estimation secondaire de confiance faible : elle n'entre dans aucune formule.

Les décodages de l'ancien `2101`/`2102` (section 3.1, F4) ne sont conservés que comme oracle de test du réassembleur et comme définitions au statut `COMMUNAUTAIRE_NON_VALIDE` conditionnées à un positif de l'étape 3. Si l'étape 3 répond, ils sont confrontés au pack 350 V avant tout usage.

## 9. Journal de découverte JSONL

Format retenu : JSONL (R3), complété par R4. Un fichier par session, une transaction par ligne, écrit et flushé immédiatement (`DiscoveryJournal.append()` ne retourne qu'après écriture). Le CSV d'analyse se dérive du JSONL, jamais l'inverse.

### 9.1 Schéma v1, champ par champ

| Champ | Type | Contenu |
|---|---|---|
| `schema` | entier | Version du schéma ; `1` pour cette spec |
| `session_id` | chaîne | Identifiant unique de la session (un fichier = une session) |
| `seq` | entier | Numéro de séquence monotone dans la session, à partir de 0 |
| `ts_wall` | chaîne | Horloge murale ISO 8601 avec fuseau horaire |
| `ts_mono_ms` | entier | Horloge monotone en millisecondes, pour les durées et l'ordre fin |
| `phase` | chaîne | Phase du plan de scan (`init`, `legifere-11`, `legifere-29`, `heritage-2101`, `presence-11`, `cible-29-db`, `identification`, `did-gen4`, `balayage`, `manuel`, `verite-terrain`…) |
| `kind` | chaîne | `at`, `uds` ou `obd` |
| `adapter` | objet | `ati` (réponse `ATI`), `stn` (booléen déduit de `STI`), `transport` (`ble`, `classic`, `tcp`, `replay`) |
| `elm` | objet | Instantané complet de l'état ELM au moment de l'envoi : `protocol`, `sh`, `cra`, `fcsh`, `fcsd`, `caf`, `st_ms` |
| `vehicle` | objet | `state` (READY, IG-ON, charge AC… tel que déclaré par le propriétaire), `atrv_v` (tension 12 V relue, quand disponible) |
| `request` | objet | `tx_id` (identifiant CAN d'émission, hexadécimal), `bytes` (requête hexadécimale), `service` (premier octet), `did` (pour `0x22`, sinon nul) |
| `response` | objet | `raw_text` (texte verbatim avant parsing), `frames` (tableau des trames/lignes reçues), `reassembled` (charge utile réassemblée hexadécimale, ou nulle), `status` (`positive`, `nrc`, `no_data`, `timeout`, `elm_error`, `buffer_full`), `nrc` (code hexadécimal ou nul), `elm_tokens` (tableau : `SEARCHING...`, `NO DATA`, `CAN ERROR`, `BUFFER FULL`, `?`, `OK`…) |
| `duration_ms` | entier | Durée de la transaction, envoi → prompt `>` |
| `retry_of` | entier ou nul | `seq` de la tentative précédente quand la ligne est une répétition (ex. retente après `BUFFER FULL` en FC bridée) |
| `tx_chunks` / `rx_chunks` | tableaux d'objets | Octets bruts TX/RX avec leurs frontières de chunks (notamment BLE) et horodatages monotones, pour alimenter le rejeu re-découpé (complément R4) |

Les événements qui ne sont pas des transactions (refus liste blanche, critère d'arrêt atteint, saisie de vérité terrain par le propriétaire) sont journalisés comme lignes de même schéma avec `kind` et `phase` appropriés et les champs de requête/réponse nuls ou décrivant le refus.

### 9.2 Règles du journal

- **Flush immédiat** : chaque ligne est écrite et flushée avant que la transaction suivante ne parte. Une coupure (12 V, Bluetooth, app tuée) ne perd au pire que la transaction en cours.
- **Brut verbatim** : `raw_text` et les chunks bruts sont conservés tels que reçus, avant tout parsing. Le journal est la source permettant de réécrire un décodeur sans retourner au véhicule.
- **`NO DATA` ≠ absent** : un silence est journalisé `status: "no_data"` et n'est jamais traduit, ni dans le journal ni dans l'interface, en « ECU absent » ou « DID inexistant ». Un `NO DATA` uniforme reste ambigu : gateway, filtre `ATCRA`, syntaxe d'en-tête du clone, ECU endormi sont indistinguables à ce niveau.
- Un NRC est une information : `31` (requestOutOfRange) borne la cartographie, `33` (securityAccessDenied) signifie « le DID existe mais est verrouillé », `7F`/`7E` orientent vers un retest en session étendue. Ils sont conservés avec la même fidélité que les réponses positives.
- Les vérités terrain saisies (SOC affiché, autonomie, état de charge) sont des lignes du journal horodatées des deux horloges, pour être alignables avec les transactions voisines.

### 9.3 Anonymisation VIN avant commit

- Le VIN apparaît fatalement dans les captures (`0902`, `22F190`). Aucune capture contenant un VIN réel n'est commitée dans le dépôt : le dossier `captures/` est ignoré par git et les fichiers de session `*.jsonl` aussi.
- Avant qu'une capture devienne fixture de test ou soit partagée, le VIN est anonymisé (masquage des caractères identifiants en conservant longueur et structure des trames) par l'outil d'anonymisation du package `journal`, et le résultat est revérifié par re-lecture.
- Les fixtures commitées sont donc des dérivées anonymisées, octet pour octet fidèles hors octets du VIN, ce qui préserve leur valeur d'oracle pour le réassembleur et les décodeurs. L'ignore `*.jsonl` reste global et sans exception : une fixture anonymisée s'ajoute volontairement en force (`git add -f`), jamais par un `git add` large — le geste forcé fait partie de la relecture d'anonymisation.

## 10. Stratégie de test sans voiture

Le test sans voiture précède le premier branchement au véhicule. Cinq couches, toutes dans `:protocol` en JVM sauf mention contraire :

1. **Unitaires JVM sur oracle publié.** La trame d'exemple de l'ancien modèle (`762 10 37 61 01…`, R1) est l'oracle indépendant : le réassembleur doit produire 55 octets utiles, et la définition « capacité » de l'ancien modèle doit lire les items 27+28 = `01 54` et produire 34,0 Ah. Ce test valide la convention d'offset (items comptés après `61 01`) et le réassemblage avant tout branchement. Il ne valide rien sur le 2024, et n'a pas à le faire.
2. **Golden files du tokeniseur.** Jeux d'entrées/sorties figés couvrant : `7F 22 78` suivi de la vraie réponse (attente sans renvoi), `NO DATA`, `CAN ERROR`, `BUFFER FULL`, écho activé, `SEARCHING...` initial, réponses multi-ECU (éclatement par en-tête), lignes indexées vs en-têtes CAN, prompt `>` arrivant en milieu de chunk.
3. **Intégration émulateur Ircama via TCP.** `TcpTransport` se connecte à ELM327-emulator lancé en TCP (`-n <port>`) ; scénarios multi-ECU 11 bits et 29 bits, réponses longues de type « tensions cellules » pour exercer flow control manuel et `BUFFER FULL`, NRC variés. La licence de l'émulateur (`NOASSERTION`) est vérifiée dans son dépôt avant tout usage hors test local ; l'émulateur n'est jamais redistribué ni lié à l'app.
4. **Rejeu de captures réelles.** Chaque session réelle devient fixture : le flux RX est re-découpé à des frontières arbitraires (milieu de ligne, juste avant le `>`, chunk par octet) et les décodés sont comparés aux golden files. Le rejeu est l'oracle indépendant octet pour octet de l'émulateur : si les deux divergent, la capture réelle gagne. VIN anonymisé avant tout commit (section 9.3).
5. **Test de propriété de la liste blanche.** Pour toute requête générée dont le premier octet est hors liste blanche (et hors exception `2101`/`2102` vers `761`, et hors sous-fonctions fermées de `0x10`/`0x19`/`0x3E`), `UdsClient` doit refuser avant émission et le transport factice ne doit avoir rien reçu. La propriété couvre les 256 valeurs du premier octet et les sous-fonctions des services conditionnels.

Complément possible avant l'app : des captures initiales peuvent être produites sans notre code, via AndrOBD (outil GPL-3.0 utilisable tel quel, aucun code copié) ou un terminal série Bluetooth, puis nourrir la couche 4.

Commande de vérification du cœur : `./gradlew :protocol:test`. La couche 3 est gardée par la disponibilité locale de l'émulateur et documentée comme telle dans le harnais de test ; les couches 1, 2, 4 et 5 sont inconditionnelles.

## 11. Toolchain épinglée et bootstrap headless

Versions épinglées (faits datés 2026-10-29, R4). Toutes les versions vivent uniquement dans `gradle/libs.versions.toml`.

| Élément | Version épinglée | Remarque |
|---|---|---|
| JDK | Temurin 21 LTS | Bytecode cible 17 |
| Gradle | 9.7.1 | Via le wrapper du dépôt (`./gradlew`) |
| AGP | 9.4.1 | Kotlin intégré : ne plus appliquer `org.jetbrains.kotlin.android`, seul le plugin Compose |
| Kotlin / plugin Compose | 2.4.21 | |
| Compose BOM | 2026.09.00 | |
| Kable | 0.45.0 | Apache-2.0 ; API 0.x isolée derrière `BleTransport` |
| `compileSdk` | 37 | |
| `targetSdk` | 36 | |
| `minSdk` | 31 | Téléphone personnel récent présumé : une seule branche de permissions — `BLUETOOTH_SCAN` avec `neverForLocation` + `BLUETOOTH_CONNECT`, zéro permission de localisation |

Le choix `minSdk 31` présume un téléphone personnel récent. Si un vieux téléphone secondaire était visé, `minSdk 26` réintroduirait la branche API ≤ 30 avec `ACCESS_FINE_LOCATION` (R4) : ce changement ne serait fait que sur décision du propriétaire (voir section 14), pas par anticipation.

Bootstrap headless, sans Android Studio :

```bash
# cmdline-tools installées au préalable ; vérifier chaque libellé le jour J :
sdkmanager --list
sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;37.0.0"

./gradlew :protocol:test          # tests du cœur, sans aucun appareil
./gradlew :app:assembleDebug     # APK debug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Les libellés exacts des paquets SDK évoluent : `sdkmanager --list` fait foi le jour de l'installation, et tout écart avec le tableau ci-dessus est consigné dans le commit de bootstrap.

Signature : la signature debug automatique suffit pour le sideload, à une condition impérative — sauvegarder `$HOME/.android/debug.keystore` hors de la machine de build dès la première installation. Sans cette sauvegarde, une perte du keystore impose une désinstallation complète et la perte des données de l'app à la réinstallation. Le keystore n'est jamais commité (voir `.gitignore`).

Pas de foreground service en v1 : usage interactif au premier plan, ce qui évite aussi toute la machinerie de déclaration associée.

## 12. Risques et mitigations

| Risque | Mécanisme | Mitigation retenue |
|---|---|---|
| Décharge de la batterie 12 V (risque n°1) | Le scan maintient les ECU éveillés ; hors READY, le DC-DC ne recharge pas l'auxiliaire ; la broche 16 est un +12 V permanent ; plaintes 12 V déjà rapportées sur 2023 avec adaptateur laissé branché | Sessions en READY (IG-ON seulement court, avec mainteneur) ; `ATRV` relu toutes les ~30 s ; arrêt immédiat si `ATRV` < 12,0 V hors READY ; retrait de l'adaptateur en fin de session, rappelé par la checklist |
| Démarrage du moteur thermique en READY | En READY, le thermique peut démarrer seul (demande de chauffage, maintien de charge) | Travail à l'extérieur ou garage ventilé uniquement, capot fermé, personne sous le véhicule ; critère d'arrêt en espace clos |
| Clone ELM327 menteur | `ATFC*`/`ATCRA`/`ATCP` absents ou répondant `OK` sans effet, 29 bits capricieux, tampon minuscule (`BUFFER FULL` sur longues réponses, surtout en BLE) | Matrice de capacités à chaque connexion et dégradation du plan ; adaptateur de référence STN/OBDLink (ou équivalent vLinker), Classic d'abord, comme seul oracle d'un silence ; FC bridée `30 0A 0A` sur troncature ; aucun silence de clone n'est interprété comme un fait véhicule |
| Secure Gateway filtrant le non-légiféré | L'Outlander 2022+ partage le BCM/passerelle des Nissan à Central Gateway ; les SGW Nissan filtrent le diagnostic non légiféré, applicabilité au Mitsubishi non confirmée | Risque traité comme faible-à-moyen : lecture `0x22` démontrée sans auth sur un 2023, mais validable seulement sur le véhicule cible ; aucun contournement en v1 (pas de bypass, jamais de `0x27`) ; un filtrage reproduit proprement avec l'adaptateur de référence est un résultat documenté, pas un échec à forcer |
| `NO DATA` ambigu | Gateway, filtre `ATCRA`, syntaxe d'en-tête 29 bits du clone, ECU endormi produisent le même symptôme | Journalisation `no_data` sans interprétation ; en-têtes validés commande par commande ; répétition avec l'adaptateur de référence avant toute conclusion (section 7.3) |
| Émission involontaire hors lecture seule | Erreur de définition de sonde, commande manuelle dans la console, régression future | Liste blanche codée en dur dans l'unique point d'émission, refus avant émission journalisé, test de propriété exhaustif sur le premier octet et les sous-fonctions fermées |
| Décodage faux présenté comme mesure | Échelles Gen4 absentes des rapports, octet `B` de `2201D1` non défini, constantes anciennes tentantes | Statuts de confiance affichés, promotion seulement par corrélation journalisée, interdiction des constantes ancien modèle (section 8.4) |
| Perte des données d'app à la réinstallation | Signature debug ; perte de `$HOME/.android/debug.keystore` | Sauvegarde du keystore dès M0 (section 11) |
| Fuite de VIN dans le dépôt | Le VIN est dans chaque capture complète (`0902`, `22F190`) | `captures/` et `*.jsonl` ignorés par git ; anonymisation obligatoire avant fixture ou partage (section 9.3) |
| Licence de l'émulateur de test | ELM327-emulator en `NOASSERTION` GitHub | Vérification dans le dépôt source avant usage ; usage local de test uniquement, jamais redistribué ni lié à l'app |
| NRC `0x78` en boucle / ECU lent | Une réponse pendante indéfinie bloquerait la session sérialisée | Attente bornée dans `ElmSession`, jamais de renvoi par-dessus, critère d'arrêt de tranche sur boucle `78` |

## 13. Jalons proposés

| Jalon | Contenu | Critère de sortie |
|---|---|---|
| **M0 — Bootstrap toolchain** | Installation headless (section 11), squelette Gradle 3 modules, versions dans `gradle/libs.versions.toml`, sauvegarde de `debug.keystore`, APK vide sideloadé sur le téléphone du propriétaire | `./gradlew :app:assembleDebug` vert et `adb install -r` réussi sur le téléphone cible |
| **M1 — `:protocol` prouvé sur émulateur** | Tokeniseur, `ElmSession`, réassembleur tolérant, `UdsClient` + liste blanche, journal JSONL v1, transport TCP et rejeu ; couches de test 1, 2, 3 et 5 de la section 10 | `./gradlew :protocol:test` vert, dont l'oracle 34,0 Ah et le test de propriété liste blanche ; scénario 29 bits complet joué contre Ircama |
| **M2 — App + transports** | `ClassicSppTransport`, `BleTransport` (Kable), scanners, permissions `minSdk 31` ; UI Compose minimale : choix d'adaptateur, checklist de session, console AT brute gardée par la liste blanche, statut de scan, saisie de vérité terrain, export JSONL | Connexion réelle aux adaptateurs du propriétaire en Classic et en BLE ; matrice de capacités affichée ; aucune émission possible hors liste blanche depuis l'UI |
| **M3 — Première session véhicule** | Déroulé complet des étapes 0 à 7 (étape 8 si le temps et le 12 V le permettent) sur l'Outlander 2024 cible, adaptateur de référence, vérités terrain saisies, DTC avant/après | Journal JSONL de session exporté ; réponses aux trois inconnues (adressage réel, politique gateway, DID valides) consignées dans un compte rendu sous `docs/` ; compte rendu relu contre le tableau d'interprétation de la section 7.3 |
| **M4 — Décodeurs corrélés** | Promotion des définitions selon la section 8.2 à mesure des corrélations (double lecture, puis charge/décharge étalonnée) ; affichage des signaux avec badges ; amorce de balayage borné si la cartographie communautaire s'avère fausse ou partielle | Au moins le SOC et la tension HV affichés au statut `CORRELE_TABLEAU_DE_BORD` ou mieux, ou constat documenté que les DID visés sont verrouillés/filtrés sur ce véhicule |

L'ordre est contraignant : M1 avant tout branchement véhicule, M3 avant toute promotion de décodeur. Aucun jalon n'ajoute de capacité d'écriture.

## 14. Questions ouvertes pour le propriétaire

Ces deux questions sont les seules dont la réponse ne peut venir ni des rapports ni du véhicule par interrogation logicielle. Chacune a un défaut assumé qui permet de commencer M0/M1 sans attendre.

1. **Modèle exact de l'adaptateur ELM327 qui sera utilisé** (et, le cas échéant, de l'adaptateur de référence STN/OBDLink ou équivalent vLinker à acquérir). En dépendent : la famille d'UUID BLE réelle à prioriser dans la table de couples connus, la crédibilité des `ATFC*`/`ATCRA`/`ATCP`, et l'interprétabilité de tout silence. Défaut assumé en attendant : table de couples complète + repli générique notify+write après `discoverServices()`, Classic traité en priorité, et aucun silence interprété avant répétition sur l'adaptateur de référence.
2. **Modèle de téléphone et version d'Android visés.** En dépendent : la confirmation de `minSdk 31` et de l'unique branche de permissions (`BLUETOOTH_SCAN` avec `neverForLocation` + `BLUETOOTH_CONNECT`), ou le retour à `minSdk 26` avec la branche `ACCESS_FINE_LOCATION` pour API ≤ 30. Défaut assumé en attendant : `minSdk 31`, téléphone personnel récent.

Toute autre inconnue du projet relève du plan de résolution déjà écrit : matrice de capacités (adaptateur), étapes 1 à 7 (adressage, gateway, DID), corrélation tableau de bord (échelles), balayage borné (cartographie), section 3.3 (report hors v1 assumé).




