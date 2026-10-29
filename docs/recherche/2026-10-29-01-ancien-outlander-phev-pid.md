# Protocole diagnostique de l’ancien Outlander PHEV (2013-2022, GG2W/GG3W) — état public des connaissances

Recherche web seulement, aucun essai véhicule. **Fait sourcé** = retrouvé tel quel dans une source citée. **Inférence** = calcul ou rapprochement de l’auteur du rapport, signalé comme tel. Aucun PID n’a été inventé : quand le décodage n’est pas publié, c’est écrit « non documenté ».

Avertissement de cadrage : tout ce qui suit concerne l’ancien modèle. Le site officiel de PHEV Watchdog limite lui-même la compatibilité à « Mitsubishi Outlander PHEV (up to 2021) » [Watchdog — index](https://phevwatchdog.net/index.php). Rien de ce document ne prouve que le 2024 (4e génération) parle le même protocole — voir §5, point 8, pour un indice contraire déjà public.

---

## 1. PHEV Watchdog (Daniel Santos) — comment elle obtient les données

**Faits sourcés**

- PHEV Watchdog est une app Android de monitoring pour l’Outlander PHEV, écrite par Daniel Santos, qui relaye en forme lisible des données obtenues via un adaptateur OBD2 [pétition Change.org citant l’app et son auteur](https://www.change.org/p/outlander-phev-drivers-around-the-world-mitsubishi-outlander-phev-battery-degradation-acknowledgement-request-to-mitsubishi-japan) ; [site officiel](https://phevwatchdog.net/index.php).
- L’app est **fermée** : ni le site officiel ni la FAQ ne publient les requêtes CAN/OBD, les adresses ECU ou les formules [FAQ officielle](https://phevwatchdog.net/help/faq.php) ; [index officiel](https://phevwatchdog.net/index.php). Les requêtes du §2 viennent donc des travaux publics antérieurs (anko), pas d’une documentation Watchdog.
- Un membre du forum affirme : « Watchdog and EvBatMon come from his [anko’s] research as far as I know » — formulation prudente (« as far as I know »), donc confiance **moyenne**, pas une confirmation de Daniel Santos [myoutlanderphev.com — People with EvBatMon or PHEV Watchdog](https://www.myoutlanderphev.com/threads/people-with-evbatmon-or-phev-watchdog-or-other-please-help.3508/page-2).
- anko affirme que la valeur Ah affichée par « the Dog / EvBatMon » est l’item 18 de la BMU data list du MUT-III (« Battery current capacity ») [myoutlanderphev.com — Drive battery degradation and the out of whack BMU](https://www.myoutlanderphev.com/posts/44115/). Le manuel d’atelier 2020 confirme l’existence et le sens de cet item 18 : « Battery capacity (Ah) with drive battery fully charged » [Service Manual 2020 — BMU data list](http://mmc-manuals.ru/manuals/outlander_phev/online/Service_Manual_2020/M1/html/M154945550019700ENG.html).
- Le SOH affiché est un **ratio calculé par l’app**, pas une valeur brute lue : la FAQ définit le SOH comme la capacité actuelle exprimée en % de la condition idéale, et la capacité en Ah [FAQ officielle](https://phevwatchdog.net/help/faq.php). Le forum identifie le dénominateur historique utilisé par Watchdog et EvBatMon : **38 Ah** — ex. 29,9 Ah / 38 Ah ≈ 78,68 %, valeur affichée à l’époque par les deux apps [myoutlanderphev.com — wrong SOH amount](https://www.myoutlanderphev.com/threads/evbatmon-and-phev-watchdog-is-showing-the-wrong-soh-amount.4305/). Le même fil conteste ce choix : Mitsubishi aurait indiqué une capacité physique de 40 Ah, les véhicules étant livrés « à 38 Ah » [myoutlanderphev.com — Triple Procedure](https://www.myoutlanderphev.com/threads/triple-procedure-setup-costs-around-80usd.4533/page-2). Le dénominateur correct (38 vs 40) est donc **disputé** pour le pack 12 kWh.
- L’app affiche bien, d’après les rapports d’utilisateurs : tensions de cellules min/max/moyenne, capacité Ah, SOC, températures de cellules, compteurs cumulés (Ah total depuis la naissance, km en EV, cycles estimés) [outlanderforums.com — rapport Watchdog 2022](https://www.outlanderforums.com/threads/bought-used-2022-w-30k-miles-but-soh-of-81-37ah-see-dealer.1188/?post_id=6333&nested_view=1&sortby=oldest) ; [myoutlanderphev.com — Watchdog App necessary](https://www.myoutlanderphev.com/threads/watchdog-app-necessary.6220/). Le mot « estimated » pour les cycles est dans la sortie Watchdog elle-même : c’est un calcul de l’app, pas un compteur BMU lu tel quel — **inférence** à partir du libellé [même rapport 2022](https://www.outlanderforums.com/threads/bought-used-2022-w-30k-miles-but-soh-of-81-37ah-see-dealer.1188/?post_id=6333&nested_view=1&sortby=oldest).
- Contrainte matérielle critique pour notre outil : l’extraction des données batterie exige « a specific type of commands » que les clones ELM327 bon marché ne supportent pas ; la FAQ renvoie à une liste d’adaptateurs éprouvés (OBDLink LX/MX, Vgate iCar, Konnwei KW902…) [FAQ officielle](https://phevwatchdog.net/help/faq.php) ; [index — liste adaptateurs](https://phevwatchdog.net/index.php). Le site précise aussi : **« The App will not work with BLE — makes sure you have a Bluetooth Classic device »**, et que l’adaptateur doit supporter « the latest version of the ELM protocol » [index officiel](https://phevwatchdog.net/index.php). **Inférence** : « specific type of commands » = très vraisemblablement les commandes de flow control ELM327 documentées au §2 (`ATFCSH/ATFCSD/ATFCSM`), mais Watchdog ne le dit pas explicitement.
- Historique (**fait**) : avant Watchdog, l’app EvBatMon (zzcoopej) a été développée explicitement sur les « Anko codes » pour afficher capacité max puis les 80 tensions de cellules [myoutlanderphev.com — fil anko, posts zzcoopej](https://www.myoutlanderphev.com/threads/extracting-useful-data-from-your-obdii-port.1796/). caniOn (i-MiEV) a refusé un portage Outlander, correspondance citée dans le même fil [même fil](https://www.myoutlanderphev.com/threads/extracting-useful-data-from-your-obdii-port.1796/).

## 2. Requêtes OBD connues (mode 21, adressage direct ECU)

### 2.1 Mécanisme d’accès — le point le plus important

**Faits sourcés.** On ne parle pas au BMU via l’adresse fonctionnelle `7DF` ni via `7E0` : il faut adresser directement l’ECU en ISO-TP 11 bits, avec **flow control ELM327 configuré manuellement**, sinon seule la première trame de la réponse multi-trames arrive [Torque Forum — derekt, 19/12/2016](https://torque-bhp.com/community/main-forum/mitsubishi-outlander-phev-flow-control/) ; [fil anko](https://www.myoutlanderphev.com/threads/extracting-useful-data-from-your-obdii-port.1796/).

Séquence ELM327 publiée par anko pour le BMU (confirmée par des tiers qui la recopient, ex. demande WiCAN) :

```
ATFCSH761      <- flow-control header = adresse requête BMU
ATFCSD300000   <- flow-control data
ATFCSM1        <- flow-control mode 1
ATSH761        <- header d'émission = 761
2101           <- requête mode 21, PID 01
```

Sources : [fil anko](https://www.myoutlanderphev.com/threads/extracting-useful-data-from-your-obdii-port.1796/) ; reprise mot pour mot dans [wican-fw issue #277](https://github.com/meatpihq/wican-fw/issues/277).

Exemple de réponse brute publié (réponse sur `762`, début ISO-TP `10 37 61 01…`, soit 0x37 = 55 octets de charge utile — **décodage ISO-TP = inférence standard**, les octets bruts sont le fait) :

```
762 10 37 61 01 82 83 0F 8B
762 21 24 0F 88 03 0C 6E 52
762 22 03 4D 1C 01 99 00 00
762 23 00 00 00 01 00 01 30
762 24 0F 0F 01 54 00 CC 9C
762 25 FE 00 03 0F 88 86 8B
762 26 64 00 00 00 00 00 00
762 27 00 00 0F 8A 00 02 00
```

Source : [fil anko](https://www.myoutlanderphev.com/threads/extracting-useful-data-from-your-obdii-port.1796/). anko numérote les « data items » à partir de 0 **après** l’en-tête de réponse `61 01` (schéma de numérotation reproduit dans son post) — toute réimplémentation doit respecter cette convention, sinon décalage de 2 octets. C’est un **fait de sa notation**, à revalider sur trame réelle.

Exemple de contrôle (**fait + arithmétique**) : items 27+28 = `01 54` = 340 → /10 = **34,0 Ah** de capacité max, exactement l’exemple commenté par anko [fil anko](https://www.myoutlanderphev.com/threads/extracting-useful-data-from-your-obdii-port.1796/).

### (a) Tableau des requêtes connues

| Requête hex | ECU (req → rép) | Réponse attendue | Décodage / formule (notation anko : items depuis 0 après `61 xx`) | Source URL | Confiance |
|---|---|---|---|---|---|
| `ATFCSH761` + `ATFCSD300000` + `ATFCSM1` + `ATSH761`, puis requête | BMU 761 → 762 | Trames ISO-TP complètes ; sans ça, 1re trame seule | Pré-requis ELM327, pas un décodage | [anko](https://www.myoutlanderphev.com/threads/extracting-useful-data-from-your-obdii-port.1796/) ; [Torque Forum](https://torque-bhp.com/community/main-forum/mitsubishi-outlander-phev-flow-control/) | Élevé |
| `2101` | BMU 761 → 762 | Multi-trames, ex. `10 37 61 01…` | Item 0 (1 octet) : **SOC % = valeur/2 − 5** (« SOC réel », peut dépasser 100 — ex. 105,5 % rapporté) | [anko](https://www.myoutlanderphev.com/threads/extracting-useful-data-from-your-obdii-port.1796/) | Élevé |
| `2101` | BMU 761 → 762 | idem | Items 8+9 (16 bits BE) : **tension totale pack V = valeur/10** | [anko](https://www.myoutlanderphev.com/threads/extracting-useful-data-from-your-obdii-port.1796/) | Élevé |
| `2101` | BMU 761 → 762 | idem | Items 14+15 : **courant charge/décharge A = « Total − 1… »** — formule telle qu’extraite du forum, **visiblement tronquée/suspecte**, ne pas utiliser sans revalidation | [anko](https://www.myoutlanderphev.com/threads/extracting-useful-data-from-your-obdii-port.1796/) | Faible |
| `2101` | BMU 761 → 762 | idem | Items 27+28 : **capacité courante / « battery health » Ah = valeur/10** (= MUT item 18) | [anko](https://www.myoutlanderphev.com/threads/extracting-useful-data-from-your-obdii-port.1796/) ; [manuel MUT](http://mmc-manuals.ru/manuals/outlander_phev/online/Service_Manual_2020/M1/html/M154945550019700ENG.html) | Élevé |
| `2101` | BMU 761 → 762 | idem | Items 29+30 : **charge restante Ah = valeur/10** (= MUT item 19, rapprochement = inférence forte) | [anko](https://www.myoutlanderphev.com/threads/extracting-useful-data-from-your-obdii-port.1796/) ; [manuel MUT](http://mmc-manuals.ru/manuals/outlander_phev/online/Service_Manual_2020/M1/html/M154945550019700ENG.html) | Élevé (formule) / Moyen (lien item 19) |
| `2102` (corrigé de `1202` par anko) | BMU 761 → 762 | Longue réponse multi-trames (~256 items utiles) | **Tension cellule V = valeur 16 bits / 1000**, 2 octets par cellule, items de départ : 0,2,4,…,14, puis 24,…,38, puis 48,…,62, 72,…,86, 96,…,110, saut à 144,…,158, 168,…,182, 192,…,206, 216,…,230, 240,…,254 (« mind the gaps ») | [anko](https://www.myoutlanderphev.com/threads/extracting-useful-data-from-your-obdii-port.1796/) | Élevé pour la formule ; Moyen pour la liste complète des offsets (recopiée d’un post de forum) |
| `2101` | OBC 765 → 766 | Multi-trames | Items 0+1 : **nombre de charges à 100 V** (brut) ; items 2+3 : **nombre de charges à 200 V** (brut) | [anko](https://www.myoutlanderphev.com/threads/extracting-useful-data-from-your-obdii-port.1796/) | Moyen (exemple unique d’anko) |
| `2102` | Moteur AV 753 → 754 ; Moteur AR 755 → 756 | Multi-trames | Items 2+3 : **RPM = valeur − 20000** | [anko](https://www.myoutlanderphev.com/threads/extracting-useful-data-from-your-obdii-port.1796/) | Moyen |
| `2107`, `2111`, `2113`, `2114`, `2115`, `2116`, `2117` | BMU 761 → 762 | Existence affirmée, longueur demandée mais non publiée dans le fil consulté | **Décodage non documenté** dans les sources publiques consultées | [Torque Forum — cintakc](https://torque-bhp.com/community/main-forum/mitsubishi-outlander-phev-flow-control/) | Faible (existence) / Nul (décodage) |
| `2101`, `2110` | CMU (ex. CMU11, rép. `6BF` dans un exemple `1A87/1A9C`) | Non publiée | **Décodage non documenté** | [Torque Forum — cintakc/anko](https://torque-bhp.com/community/main-forum/mitsubishi-outlander-phev-flow-control/) | Faible |
| `2101`, `2120`, `2122`, `2123`, `213D`, `2161`, `21A0`, `21A1`, `21A2`, `21A9` | ECU PHEV `7E2` → `7EA` (pas le BMU) | Non publiée | **Décodage non documenté** ; ne pas confondre `7E2` (ECU PHEV) et `761` (BMU) | [Torque Forum — cintakc](https://torque-bhp.com/community/main-forum/mitsubishi-outlander-phev-flow-control/) | Faible |
| `1A87`, `1A9C` (mode 1A) | BMU 762, CMU `6BF`, PHEV `7EA` | Ex. `762 10 16 5A 87…` / `10 15 5A 9C…` | Identification ECU (réponses brutes publiées, pas de table de décodage) | [Torque Forum — anko](https://torque-bhp.com/community/main-forum/mitsubishi-outlander-phev-flow-control/) | Moyen |
| Températures modules/cellules via OBD | BMU (présumé) | Watchdog les affiche ; le MUT les liste par CMU (items températures) | **Aucun PID ni formule OBD retrouvés** dans les sources consultées. L’hypothèse « `2103` = températures » circule par analogie mais **sans source claire retrouvée — à traiter comme rumeur** | [manuel MUT](http://mmc-manuals.ru/manuals/outlander_phev/online/Service_Manual_2020/M1/html/M154945550019700ENG.html) ; absence constatée dans [fil anko](https://www.myoutlanderphev.com/threads/extracting-useful-data-from-your-obdii-port.1796/) | Non documenté |
| Kilométrage, cycles, historique capacité via OBD | BMU (présumé) | Le MUT expose des historiques (items 400-421 : capacité mesurée, ODO des mesures…) | **Aucun décodage OBD public retrouvé** ; Watchdog estime les cycles (voir §1) | [manuel MUT](http://mmc-manuals.ru/manuals/outlander_phev/online/Service_Manual_2020/M1/html/M154945550019700ENG.html) | Non documenté |

**Corroborations utiles (faits)**

- Le manuel MUT distingue item 1 « Charge state (Control) » et item 2 « Charge state (Display)… with slight variation for meter », items 3/5 tensions cellule max/min, 7 tension totale, 12 courant batterie, 18 capacité courante, 19 capacité restante — cohérent avec le fait que le SOC d’anko (« réel ») diffère du SOC tableau de bord [manuel MUT](http://mmc-manuals.ru/manuals/outlander_phev/online/Service_Manual_2020/M1/html/M154945550019700ENG.html) ; écart SOC réel vs affiché rapporté par gwatpe et relevés chiffrés (34,5 % OBD à jauge ~vide ; 98 % à pleine charge ; pack 307→327 V) [fil anko](https://www.myoutlanderphev.com/threads/extracting-useful-data-from-your-obdii-port.1796/).
- Torque Pro, à l’époque, ne savait pas émettre `ATFCSH` par PID personnalisé : c’est la raison documentée de l’échec des PID Torque « classiques » sur ce véhicule, et la solution passait par la chaîne d’initialisation véhicule [Torque Forum](https://torque-bhp.com/community/main-forum/mitsubishi-outlander-phev-flow-control/). Le fichier d’exemple de cintakc était hébergé sur un lien yadi.sk (non récupéré lors de cette recherche) [même fil](https://torque-bhp.com/community/main-forum/mitsubishi-outlander-phev-flow-control/) — donc **pas de CSV Torque vérifiable en main**.
- Un fil 2024 documente des captures MUT3 à l’entrée du menu BMU (`761/762`, `7DF`, `60E`) sur un MY14.5, piste de revalidation mais non exploitée ici faute de décodage publié dans l’extrait consulté [myoutlanderphev.com — MUT3 readings](https://www.myoutlanderphev.com/threads/mut3-readings-during-enter-bmu-ecu-menu-my14-5-761-762-7df-and-60e-codes.6914/).

## 3. Structure batterie de l’ancien modèle (calibration)

**Faits sourcés**

- Pack d’origine : **80 cellules en série, tension totale 300 V, 12,0 kWh**, dérivé de la technologie i-MiEV [communiqué Mitsubishi repris par electric-vehiclenews](http://www.electric-vehiclenews.com/2012/12/mitsubishi-outlander-phev-plug-in.html) ; même formulation dans le press kit 2018 (12 kWh) [Mitsubishi Cars media](http://media.mitsubishicars.com/en-US/releases/2018-mitsubishi-outlander-phev-press-kit).
- À partir du MY2019 : **13,8 kWh**, toujours annoncé « 300 V Lithium ion battery pack » [InsideEVs](https://insideevs.com/news/336779/2019-mitsubishi-outlander-phev-gets-15-bigger-battery-range-improves/) ; [Electrek + fiche technique](https://electrek.co/2018/06/19/mitsubishi-slightly-increases-battery-capacity-in-new-2019-outlander-phev/) ; [doc MMC MY19 archivée](https://web.archive.org/web/20181030090729/http://www.2018-mitsubishi-motors.eu/Files/Documents/EN/new_MY19_OutlanderPHEV.pdf).
- Relevés réels cohérents avec 80 cellules : pleine charge pack 327 V, soit 4,09 V/cellule (calcul de l’utilisateur) [fil anko](https://www.myoutlanderphev.com/threads/extracting-useful-data-from-your-obdii-port.1796/) ; rapport concessionnaire : cellule max 4,096 V, min 4,09 V, total 327,4 V [myoutlanderphev.com — Battery Condition Report](https://www.myoutlanderphev.com/threads/battery-condition-report-question.4649/).

**Inférences arithmétiques (à étiqueter comme telles dans le code)**

- Tension nominale par cellule = 300/80 = **3,75 V**.
- Capacité nominale pack 12 kWh à 300 V = **40 Ah** — ce qui recoupe le « 40 Ah physique » du débat 38 vs 40 du §1, et les resets BMU qui « show 40Ah… will now drop to 38Ah » [YouTube — BMU reset](https://www.youtube.com/watch?v=K5P_MpAnxbg) (source faible, mais convergente).
- Pour le pack 13,8 kWh : 13800/300 = **46 Ah théoriques**. Un rapport Watchdog 2022 affiche 37 Ah pour 81 % de SOH, ce qui implique un dénominateur ≈ 45,7 Ah — **simple division à partir d’un post de forum**, pas une valeur publiée par Watchdog [outlanderforums.com](https://www.outlanderforums.com/threads/bought-used-2022-w-30k-miles-but-soh-of-81-37ah-see-dealer.1188/?post_id=6333&nested_view=1&sortby=oldest). À confirmer avant tout affichage SOH MY2019+.
- Organisation en modules : le projet open-source du §4 décrit **10 CMU × 8 cellules** (= 80) sur le bus interne [wodor/OutlanderPHEVBMS](https://github.com/wodor/outlanderphevbms/blob/HEAD/AGENTS.md), alors que la table MUT 2020 énumère des températures pour « CMU ID01 » à « CMU ID11 » [manuel MUT](http://mmc-manuals.ru/manuals/outlander_phev/online/Service_Manual_2020/M1/html/M154945550019700ENG.html). **Contradiction non résolue** (voir §5) — ne pas coder en dur « 10 modules » côté OBD sans revalidation. Les « gaps » de la réponse `2102` (blocs de 8 cellules séparés par des zones non-cellules) sont compatibles avec un regroupement par CMU de 8, mais c’est une **inférence**.

## 4. Projets open-source

**OVMS — fait négatif important.** OVMS v3 supporte le « Mitsubishi Trio » : i-MiEV / Citroën C-Zero / Peugeot iOn (type véhicule `MI`), pas l’Outlander PHEV [documentation OVMS Mitsubishi Trio](https://docs.openvehicles.com/en/latest/components/vehicle_mitsubishi/docs/index.html) ; [source docs sur GitHub](https://github.com/openvehicles/open-vehicle-monitoring-system-3/blob/HEAD/vehicle/OVMS.V3/components/vehicle_mitsubishi/docs/index.rst). Aucun module Outlander PHEV n’a été trouvé dans OVMS lors de cette recherche ; une recherche tierce récente conclut de même que les candidats évidents (OBDb Outlander PHEV, OVMS) n’offrent rien d’utilisable pour ce modèle [scainner RESEARCH.md](https://github.com/cxalem/scainner/blob/HEAD/packages/uds-map/RESEARCH.md). Conséquence : pas de liste de CAN IDs « en écoute passive » OVMS transposable à l’Outlander PHEV. Pour l’i-MiEV, la communauté documente des IDs véhicule comme `373` (tension/courant) et `374` (SOC) sur le CAN voiture [myimiev.com — Battery current sensor](https://myimiev.com/threads/battery-current-sensor.4718/) — **spécifique i-MiEV, non démontré pour l’Outlander**.

**Bus interne BMU↔CMU (récupération de packs, hors prise OBD).** Les projets `Tom-evnut/OutlanderPHEVBMS` et ses forks (dont wodor, sur ESP32) lisent directement les modules sur le CAN interne du pack à 500 kbit/s, IDs standard 11 bits, chaque CMU émettant statuts/températures et tensions par groupes de 4 cellules en mV big-endian, commande d’équilibrage périodique sur `0x3C3` [wodor/OutlanderPHEVBMS — AGENTS.md](https://github.com/wodor/outlanderphevbms/blob/HEAD/AGENTS.md). **Réserve** : la table d’IDs de ce dépôt (`0x011/0x012/0x013`, `0x021…`) ne concorde pas avec d’autres descriptions publiques du même bus (le PDF de Tom de Bree parle d’IDs `6x2/6x3` pour les tensions) [Decode BMS Canbus — Tom de Bree](https://github.gohj99.site/Tom-evnut/OutlanderPHEVBMS/raw/refs/heads/master/Decode%20BMS%20Canbus.pdf). De toute façon, une analyse tierce souligne que ce protocole CMU vit sur un **bus EV/BMU interne séparé**, pas sur le bus diagnostique accessible à la prise OBD [scainner RESEARCH.md](https://github.com/cxalem/scainner/blob/HEAD/packages/uds-map/RESEARCH.md). Utile pour comprendre la structure du pack ; **inutilisable tel quel pour un outil ELM327**.

**OBDb.** Il existe un dépôt `obdb/mitsubishi-outlander-phev`, mais décrit par des tiers comme un stub/ensemencement récent sans décodage BMU exploitable au moment de leur revue [OBDb — README](https://github.com/obdb/mitsubishi-outlander-phev/blob/HEAD/README.md) ; [scainner RESEARCH.md](https://github.com/cxalem/scainner/blob/HEAD/packages/uds-map/RESEARCH.md). Aucune formule n’en a été tirée pour ce rapport.

**python-OBD / divers.** Aucun projet GitHub public parlant au BMU `761/762` de l’Outlander en mode 21 n’a été retrouvé ; les demandes existent (ex. WiCAN pour i-MiEV/Outlander reprenant les commandes d’anko, avec timeouts rapportés) [wican-fw issue #277](https://github.com/meatpihq/wican-fw/issues/277). Côté i-MiEV, la communauté confirme aussi l’adressage direct `0x761` pour le BMU plutôt que `0x7E0` [myimiev.com — CAN bus/MUT/J2534](https://myimiev.com/threads/can-buss-mut-and-sae-j2534-pass-through-programming-of-ecus.5244/page-2) — convergence utile, mais les décodages i-MiEV (ex. IDs `6E1…` du CAN voiture) ne sont **pas** démontrés transposables [myimiev.com — Decyphering CAN data](https://myimiev.com/threads/decyphering-imiev-and-ion-car-can-message-data.763/).

## (b) Sources consultées

1. Fil fondateur anko (2015, 5 pages, pages 1-3 exploitées) — https://www.myoutlanderphev.com/threads/extracting-useful-data-from-your-obdii-port.1796/
2. Torque Forum — Mitsubishi Outlander PHEV Flow Control (derekt, cintakc, anko, 2016-2017) — https://torque-bhp.com/community/main-forum/mitsubishi-outlander-phev-flow-control/
3. PHEV Watchdog — site officiel — https://phevwatchdog.net/index.php
4. PHEV Watchdog — FAQ officielle — https://phevwatchdog.net/help/faq.php
5. Service Manual Outlander PHEV 2020 (miroir mmc-manuals.ru) — BMU data list — http://mmc-manuals.ru/manuals/outlander_phev/online/Service_Manual_2020/M1/html/M154945550019700ENG.html
6. myoutlanderphev.com — débat SOH 38/40 Ah — https://www.myoutlanderphev.com/threads/evbatmon-and-phev-watchdog-is-showing-the-wrong-soh-amount.4305/
7. myoutlanderphev.com — fil dégradation/BMU (lien item 18 ↔ Watchdog) — https://www.myoutlanderphev.com/posts/44115/
8. outlanderforums.com — rapport Watchdog complet sur un 2022 — https://www.outlanderforums.com/threads/bought-used-2022-w-30k-miles-but-soh-of-81-37ah-see-dealer.1188/
9. Communiqué Mitsubishi 2012 (80 cellules, 300 V, 12 kWh), reprise — http://www.electric-vehiclenews.com/2012/12/mitsubishi-outlander-phev-plug-in.html
10. Mitsubishi Cars media — press kit 2018 (12 kWh) — http://media.mitsubishicars.com/en-US/releases/2018-mitsubishi-outlander-phev-press-kit
11. InsideEVs — passage à 13,8 kWh MY2019 — https://insideevs.com/news/336779/2019-mitsubishi-outlander-phev-gets-15-bigger-battery-range-improves/
12. Electrek — fiche technique MY2019 (300 V, 13,8 kWh) — https://electrek.co/2018/06/19/mitsubishi-slightly-increases-battery-capacity-in-new-2019-outlander-phev/
13. OVMS — documentation Mitsubishi Trio (i-MiEV only) — https://docs.openvehicles.com/en/latest/components/vehicle_mitsubishi/docs/index.html
14. OVMS GitHub — docs vehicle_mitsubishi — https://github.com/openvehicles/open-vehicle-monitoring-system-3/blob/HEAD/vehicle/OVMS.V3/components/vehicle_mitsubishi/docs/index.rst
15. wodor/OutlanderPHEVBMS (fork Tom-evnut) — protocole CMU interne — https://github.com/wodor/outlanderphevbms/blob/HEAD/AGENTS.md
16. Tom de Bree — Decode BMS Canbus (PDF) — https://github.gohj99.site/Tom-evnut/OutlanderPHEVBMS/raw/refs/heads/master/Decode%20BMS%20Canbus.pdf
17. scainner RESEARCH.md — revue tierce (OBDb/OVMS vides pour Outlander PHEV) — https://github.com/cxalem/scainner/blob/HEAD/packages/uds-map/RESEARCH.md
18. OBDb mitsubishi-outlander-phev (stub) — https://github.com/obdb/mitsubishi-outlander-phev/blob/HEAD/README.md
19. wican-fw issue #277 (reprise des commandes anko) — https://github.com/meatpihq/wican-fw/issues/277
20. wican-fw issue #468 (Outlander PHEV **2023**, adressage 29 bits — hors périmètre, cité pour le §5.8) — https://github.com/meatpihq/wican-fw/issues/468
21. myimiev.com — adressage direct BMU 0x761 (contexte i-MiEV) — https://myimiev.com/threads/can-buss-mut-and-sae-j2534-pass-through-programming-of-ecus.5244/page-2
22. myoutlanderphev.com — rapport batterie concessionnaire (tensions de référence) — https://www.myoutlanderphev.com/threads/battery-condition-report-question.4649/

Consultées sans résultat exploitable (absence = résultat) : recherches « Torque Pro custom PID Outlander » sur les forums généralistes, « phevwatchdog PID 2102/2103 » (aucune formule température retrouvée), listages OVMS pour un module Outlander (inexistant).

## (c) Zones d’incertitude explicites

1. **Températures via OBD : décodage inconnu.** Ni PID, ni offsets, ni formule n’ont été retrouvés. Toute valeur du type « 2103, octet −40 » serait une invention à ce stade. Watchdog les obtient pourtant (les utilisateurs voient les températures monter en CHAdeMO [myoutlanderphev.com — Abused CHAdeMO](https://www.myoutlanderphev.com/threads/abused-chademo.4737/)), donc l’information existe sur le bus diagnostique — elle n’est simplement pas publiée.
2. **Formule du courant batterie (`2101` items 14+15)** : telle qu’extraite (« Total − 1 »), elle est inutilisable. Signe, offset et échelle exacts à redéterminer expérimentalement avant tout affichage en ampères.
3. **Numérotation des items anko** : elle suppose un comptage à partir de 0 après l’écho `61 xx`. Si notre pile ELM327/ISO-TP présente les octets autrement (en-têtes `762 21…` inclus ou non), tous les offsets glissent. À valider sur la trame d’exemple du §2.1 avant le premier essai réel.
4. **Liste des offsets cellules `2102`** : recopiée d’un post de forum, avec un saut inexpliqué (110 → 144). L’ordre cellule↔offset et l’appariement aux CMU ne sont pas documentés ; seuls min/max/moyenne sont sûrs sans validation.
5. **Dénominateur SOH** : 38 Ah (apps) vs 40 Ah (physique, pack 12 kWh) ; pour le 13,8 kWh, ≈46 Ah seulement inféré. Un SOH affiché sans indiquer son dénominateur est trompeur — c’est exactement le reproche du forum à Watchdog/EvBatMon [débat SOH](https://www.myoutlanderphev.com/threads/evbatmon-and-phev-watchdog-is-showing-the-wrong-soh-amount.4305/).
6. **Nombre de CMU/modules** : 10×8 (open-source pack) contre des items MUT « CMU ID01…ID11 ». Possibles causes : variantes d’années, CMU supplémentaire, ou artefact de la table BMU(SUB). Non tranché.
7. **Kilométrage, cycles, historique de capacité** : présents côté MUT (items 400+), aucun décodage mode 21 public. Les « cycles » Watchdog sont estimés par l’app.
8. **Transposabilité au 2024 : probablement faible.** Indice public hors périmètre mais décisif pour la suite : sur un Outlander PHEV **2023**, un utilisateur obtient le SOC en **CAN 29 bits** avec un PID mode 22 (`2201D1`, équation `B*0.64−19.2`) et un filtre `ATCRA18DAF1DB` — adressage et service totalement différents du GG2W [wican-fw issue #468](https://github.com/meatpihq/wican-fw/issues/468). Le protocole GG2W du présent rapport doit donc être traité comme une **hypothèse de départ à invalider**, pas comme un acquis pour le 2024 (plateforme partagée avec Nissan, ce qui rend un héritage type `18DAF1xx`/mode 22 plausible — **inférence**, à vérifier sur le véhicule).
9. **BLE** : l’échec documenté de Watchdog en BLE ne dit pas si le protocole l’interdit ou si seuls les adaptateurs BLE courants gèrent mal le flow control ELM. Pour notre cible BLE + Classic, c’est un risque matériel à tester tôt, avec au minimum un adaptateur de la liste éprouvée comme référence [index Watchdog](https://phevwatchdog.net/index.php).

---

**En une phrase pour démarrer le code** : sur GG2W, tout tient en deux requêtes mode 21 adressées au BMU `761→762` avec flow control ELM manuel — `2101` (SOC, tension pack, Ah courant/restant) et `2102` (80 tensions de cellules /1000) — le reste (températures, historique, SOH exact) est soit fermé (Watchdog), soit non publié, et le 2024 semble déjà parler un autre dialecte (29 bits, mode 22) qu’il faudra découvrir sur le véhicule.