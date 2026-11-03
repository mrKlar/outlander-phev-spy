# Découverte de la séquence de mise en maintenance EPB — Plan

> **Pour les agents exécutants :** suivre ce plan tâche par tâche.
> Les étapes utilisent la syntaxe `- [x]`. Aucune tâche n'autorise
> une émission hors liste blanche : en cas de doute, s'arrêter et
> demander au propriétaire.
>
> **Exécution :** tâches 0 à 2 réalisées le 2026-11-01 (spec
> amendée, `scripts/diff-journaux.py` + tests, capture physique
> par ECU et snapshot `743`, 148 tests `:protocol` verts et
> `:app:assembleDebug` vert dans la session). Travail non
> commité, en attente de relecture du propriétaire. Les tâches
> 3 et 4 attendent la prochaine séance véhicule.

**Objectif :** établir, par observation en lecture seule, (1) quel
ECU porte l'état du frein de stationnement électrique (EPB) sur
l'Outlander PHEV 2024 cible, (2) si la séquence manuelle
constructeur de la génération précédente (Outlander III 2020)
produit sur ce véhicule un état « maintenance » identifiable et
réversible, et (3) quels DID en lecture prouvent cet état — sans
jamais commander les freins depuis l'app.

**Architecture :** trois voies indépendantes qui doivent converger
avant toute conclusion : identification UDS des répondants et diff
d'octets entre états consignés (voie principale, outillée par
l'app existante étendue), écoute CAN passive `listen-only` pendant
la séquence manuelle (WiCAN, hors app), et voie documentaire
(manuel d'atelier GN/NS22, plateforme Nissan T33 en hypothèse
seulement). L'app ne gagne aucune capacité d'écriture.

**Tech Stack :** Kotlin/JVM (`:protocol`), Compose (`:app`),
Python 3 bibliothèque standard seulement pour l'outil de diff
hors ligne, WiCAN-PRO Advanced en enregistrement passif.

**Spec :** `docs/superpowers/specs/2026-10-29-outlander-phev-spy-design.md`
(notamment §5 liste blanche, §7.2 « Observation EPB ») et
`docs/reverse-engineering/epb-mode-service.md` (état de la
recherche au 2026-10-31). Les deux se lisent avant de commencer,
avec `docs/reverse-engineering/seance-2026-10-31.md` et la règle
de preuve négative de `docs/reverse-engineering/campagne-soh-cellules.md`.

## Contraintes globales

- Lecture seule stricte : seules les requêtes de la liste blanche
  partent vers le véhicule (modes OBD `01, 02, 03, 07, 09, 0A` ;
  UDS `0x10` sous-fonctions `01`/`03` en physique, `0x19`
  sous-fonctions `01`/`02`/`0A`, `0x22`, `0x3E`), via `UdsClient`
  et son garde. Jamais `0x27`, `0x2E`, `0x2F`, `0x31`, `0x14`,
  ni aucun service de la liste noire (spec §5).
- On ne devine jamais un identifiant de routine sur le véhicule.
  Découvrir un identifiant par écoute passive ne l'autorise pas :
  le réémettre exigerait un nouveau design de sécurité et un
  amendement de la spec (critères dans `epb-mode-service.md`).
- Toute nouvelle cible ou plage de lecture est d'abord inscrite
  dans la spec (§7.2), puis codée, puis testée sans voiture.
- `NO DATA` signifie `no_data` : jamais « ECU absent », jamais
  « DID inexistant », jamais « bloqué » sans la preuve négative
  complète (contrôle positif même séance, voies fonctionnelle
  et physique, sessions, états véhicule, second adaptateur).
- Jamais de capture contenant un VIN réel dans git. Les journaux
  restent dans `captures/` (gitignoré) ; tout extrait partagé ou
  commité est anonymisé puis relu.
- Véhicule stationné en sécurité pour toutes les manipulations
  EPB : cales aux roues avant, sol plat, personne sous le
  véhicule, maintien de charge 12 V si la séance dure, adaptateur
  retiré en fin de séance. Ne jamais repousser un piston sans
  état maintenance prouvé par un DID d'état (tâche 6).
- Ordre de vérification après tout changement de code :
  `source scripts/dev-env.sh && ./gradlew :protocol:test`, puis
  `./gradlew :app:assembleDebug`. Les deux verts dans la session.
- Documentation et messages de commit en français.

## Pourquoi les captures du 2026-10-31 n'ont rien montré

Faits du compte rendu de séance, à ne pas réinterpréter :

1. Le snapshot EPB ne lit que `F186`/`F187`/`F189`, les DTC
   `19 01/02/0A` et les DID batterie Gen4 : aucune donnée d'état
   propre à l'EPB n'y figure. Les deux snapshots de la séance
   15 h 13 ne différaient que par les DID batterie.
2. Les cibles sont les ECU logiques 29 bits `DB, DE, E5, DA, DC,
   DD, DF` (`CIBLES_EPB`). Or `DB` = BMU, `DE` = chargeur
   (« OBC27 »), `E5` = inverseur arrière (« INV-RE ») par `F194` :
   aucun n'est un candidat freinage. `DA, DC, DD, DF` ne sont pas
   identifiés. Les répondants légiférés `0x15`, `0x2D`, `0xE2`
   (vus sur `0100`) ne sont pas dans les cibles. L'ECU 11 bits
   `743`/`763` (« P33_SW_07.07.R1 ») n'est lu par aucun snapshot.
3. La « capture complète » balaye `0100–01FF` en fonctionnel en
   attendant `DB` : l'attribution par ECU des autres répondants
   dépend alors du journal brut et du tampon du clone. Un diff
   par ECU exige des balayages physiques, un canal à la fois.
4. L'essai manuel du propriétaire a produit l'état A (voyant
   rouge + commutateur clignotants après 10 cycles), puis un
   voyant jaune fixe sans bruit de moteur. Le manuel 2020 dit
   que le succès est justement « voyant jaune fixe » sans bruit
   annoncé à ce stade : l'état B a peut-être été atteint sans
   être reconnu. Personne ne l'affirme sans le diff de la tâche 4.

## Hypothèses et observations discriminantes

| # | Hypothèse | Observation qui la soutient | Observation qui la réfute |
|---|---|---|---|
| H1 | L'ECU EPB/ESC est l'un des répondants 29 bits non identifiés (`DA, DC, DD, DF, 15, 2D, E2`) | Son `F187`/`F194` ou sa liste DTC supportés (`19 0A`) nomment le freinage (DTC `C1xxx`) ; un DID y change entre serré/desserré | Aucun de ces ECU ne change entre serré/desserré en A/B/A répété |
| H2 | L'ECU EPB est le répondant 11 bits `743` | Un DID `743` change entre serré/desserré, stable à état constant | Aucun changement reproductible sur `743` |
| H3 | L'état EPB est filtré par la passerelle et invisible en diagnostic OBD | H1 et H2 réfutées avec contrôle positif, mais des trames corrélées apparaissent en écoute BCM/châssis (tâche 5) | Un DID d'état est trouvé par H1 ou H2 |
| H4 | La séquence manuelle 2020 vaut sur la GN : A après 10 cycles, B après AUTO HOLD, voyant jaune fixe = succès | Un champ bascule à l'entrée en B, tient 2 min, revient à la sortie ; message cluster conforme au manuel | La séquence verbatim (pédale maintenue incluse) ne produit ni le message ni aucun basculement, deux essais |
| H5 | La GN n'a aucune séquence manuelle ; seul un outil (MUT-III SE / J2534) entre en maintenance | H4 réfutée et documentation GN (tâche 6) sans méthode au commutateur | H4 soutenue |

Aucun verdict « inaccessible » avant épuisement des axes de la
règle de preuve négative. Le verdict par défaut est
« non concluant ».

---

### Tâche 0 — Amendement de la spec (préalable à tout code)

**Fichiers :**
- Modifier : `docs/superpowers/specs/2026-10-29-outlander-phev-spy-design.md` (§7.2, puce « Observation EPB »)

- [x] **Étape 1 :** ajouter au §7.2 les lectures de ce plan, sans
  toucher à la liste blanche §5 : identification `F180–F19F` et
  DTC `19 01/02/0A` en physique 29 bits sur les répondants
  `DA, DB, DC, DD, DE, DF, E5, 15, 2D, E2` (ceux vus répondre,
  séance du 2026-10-31) ; mêmes lectures en physique 11 bits sur
  `743` ; balayages physiques bornés `22 0100–01FF` canal par
  canal pour le diff EPB. Préciser que l'écoute CAN passive
  `listen-only` (WiCAN) est hors app, sans aucune trame émise.
- [ ] **Étape 2 :** relire l'amendement contre `AGENTS.md` :
  aucun service nouveau, aucune écriture. Commit :
  `docs(spec): lectures d'identification et diff EPB par ECU`.

### Tâche 1 — Outil de diff hors ligne des journaux

Sans diff fiable, les captures terrain restent illisibles : un
journal de séance compte ~1 000 événements et les DID batterie
dérivent en permanence (faux candidats).

**Fichiers :**
- Créer : `scripts/diff-journaux.py`
- Créer : `scripts/test_diff_journaux.py`

**Interfaces :**
- Consomme : des fichiers journal JSONL v1 (schéma spec §9) et
  les notes d'étape EPB (`phase = "epb-observation"`) qui
  découpent le journal en segments d'état.
- Produit : un tableau texte, une ligne par
  `(en-tête répondant, DID, offset d'octet)` dont la valeur
  diffère entre au moins deux segments, avec la valeur par
  segment. Les lignes dont la valeur change aussi entre deux
  lectures du même segment sont marquées `instable` et triées
  après les lignes stables.

- [x] **Étape 1 : écrire le test d'abord.** Deux journaux JSONL
  synthétiques (aucun VIN, charges inventées) écrits dans un
  dossier temporaire : un DID change entre segments et reste
  stable dans chaque segment (attendu en tête de sortie), un DID
  change à chaque lecture (attendu marqué `instable`), un DID ne
  change jamais (absent de la sortie). Format des événements :
  copier la structure réelle depuis `Journal.kt` (champs `seq`,
  `phase`, `request`, `status`, `reassembled`), pas l'inventer.
- [x] **Étape 2 :** lancer `python3 scripts/test_diff_journaux.py` ;
  attendu : échec (le script n'existe pas encore).
- [x] **Étape 3 : implémenter** `scripts/diff-journaux.py`
  (bibliothèque standard seulement : `json`, `sys`,
  `collections`). Il ne lit que les réponses positives
  réassemblées des requêtes `22xxxx` ; la clé est
  `(en-tête, DID)` et la charge comparée est celle après
  `62 <DID>`.
- [x] **Étape 4 :** test vert. Puis essai réel sur deux journaux
  de `captures/2026-10-31/` (lecture locale, rien de commité) :
  la sortie doit faire ressortir les DID batterie connus et
  rester lisible (< 300 lignes stables). Commit (à faire) :
  `feat(scripts): diff octet par octet des journaux par état`.

### Tâche 2 — Capture EPB attribuable par ECU (code, TDD)

**Fichiers :**
- Modifier : `protocol/src/main/kotlin/ca/mrklar/outlander/protocol/scan/ScanPlan.kt` (`CIBLES_EPB`, `corpsEpbObservation`)
- Modifier : `app/src/main/kotlin/ca/mrklar/outlander/phevspy/SessionViewModel.kt` (`lancerCaptureEpbComplete`)
- Test : `protocol/src/test/kotlin/ca/mrklar/outlander/protocol/scan/ScanExecutorTest.kt`

**Interfaces :**
- Consomme : `sweepDidRange(channel, range)` existant (balayage
  physique borné) et `epbObservationSnapshot` existant.
- Produit : une capture complète = snapshot existant, puis pour
  chaque canal de `CIBLES_EPB` un `sweepDidRange(canal,
  0x0100..0x01FF)` physique, puis le même snapshot d'identifiants
  sur le canal 11 bits `743`. Durée annoncée à l'UI recalculée
  honnêtement (mesurée sur transport scripté, pas devinée).

- [x] **Étape 1 : test d'abord** sur `ScriptedElmTransport` :
  la capture complète émet les lectures d'identification sur
  chaque canal `CIBLES_EPB` dans l'ordre, un balayage physique
  par canal (en-tête `ATSH` du canal avant ses `22xxxx`), et
  les lectures `743` en protocole 11 bits ; rejouer toutes les
  écritures dans `RequestGuard` : zéro refus, zéro service hors
  `0x22`/`0x19`/`0x10`/`0x3E`.
- [x] **Étape 2 :** `./gradlew :protocol:test` ; attendu :
  le nouveau test échoue (comportement absent).
- [x] **Étape 3 : implémenter** en réutilisant `sweepDidRange`
  et le canal 11 bits déjà défini pour la présence (`743`→`763`).
  Ajouter `0x15`, `0x2D`, `0xE2` à `CIBLES_EPB` seulement si
  l'amendement de la tâche 0 les couvre ; commentaire citant la
  séance du 2026-10-31 comme source de ces répondants.
- [x] **Étape 4 :** `:protocol:test` vert, puis
  `:app:assembleDebug` vert. Commit :
  `feat(protocol): capture EPB physique par ECU et cible 743`.

### Tâche 3 — Identifier l'ECU qui porte l'EPB (terrain, sans maintenance)

Aucune séquence de maintenance dans cette tâche : seulement
serré/desserré normal, que le propriétaire fait de toute façon.

**Fichiers :**
- Créer : `docs/reverse-engineering/seance-epb-identification.md` (compte rendu, après la séance)

- [ ] **Étape 1 : séance d'identification.** Véhicule actif
  (ATRV ~14,6 V), scan complet de l'app (contrôle positif :
  légiféré et DID batterie doivent répondre, sinon on arrête et
  on ne conclut rien). Pour chaque répondant, relever
  `F187`, `F189`, `F194` et la liste des DTC supportés
  (`19 0A`) : un ECU freinage se trahit par ses DTC `C1xxx`
  supportés même sans défaut présent (`19 01`/`02` à zéro).
- [ ] **Étape 2 : A/B/A serré → desserré → serré.** À chaque
  état, consigner l'étape dans l'app (voyants, bruit, durée du
  moteur d'étrier) puis « Capture complète » (tâche 2). Deux
  cycles complets si le 12 V le permet.
- [ ] **Étape 3 : analyser** avec `scripts/diff-journaux.py`
  (tâche 1) sur le journal exporté. Un candidat « état EPB »
  doit : changer entre serré et desserré, revenir au resserrage,
  être stable à état constant. Écarter tout champ qui suit le
  SOC ou la tension pack (comparer aux DID batterie du même
  segment).
- [ ] **Étape 4 : compte rendu.** Tableau des répondants
  identifiés (en-tête, `F194`, nature), verdicts H1/H2 chiffrés
  (DID, offset, valeurs par état, séance citée). Si aucun
  candidat : verdict « non concluant », H3 reste ouverte, la
  tâche 5 devient la voie principale. Commit :
  `docs(epb): identification des répondants et diff serré/desserré`.

### Tâche 4 — Éprouver la séquence manuelle 2020, état par état (terrain)

Précondition : tâche 3 faite (on sait où regarder, ou on sait
qu'on ne voit rien en OBD et la tâche 5 écoute en parallèle).
La séquence testée est celle du manuel Outlander III 2020,
verbatim, y compris le point que l'essai du 2026-10-31 a pu
manquer : **pédale de frein enfoncée et maintenue** du début
jusqu'à la coupure du contact.

**Fichiers :**
- Créer : `docs/reverse-engineering/seance-epb-sequence.md` (compte rendu)

États à capturer, dans l'ordre, sans déconnecter l'app.
À chaque état : note d'étape dans l'app (texte exact des
messages au cluster, voyants, bruits et leur durée, ATRV,
READY ou contact seul), photo du cluster si possible, puis
« Capture complète ».

| # | État | Action du propriétaire avant la capture |
|---|---|---|
| S0 | Référence | Repos, EPB serré, contact ON moteur arrêté |
| S1 | Desserré normal | Un desserrage (pédale de frein, commutateur EPB bas) |
| S2 | État A candidat | 10 cycles serré/desserré, ≥ 2 s entre actions, pédale maintenue, jusqu'au clignotement rouge + commutateur |
| S3 | État B candidat | Un desserrage, puis dans les 7 s ≥ 10 appuis AUTO HOLD, puis un desserrage |
| S4 | Persistance | Attendre 2 min sans toucher ni à l'EPB ni au commutateur (tirer annule le mode, manuel 2020) |
| S5 | Sortie | Contact ON, tirer l'EPB ; attendre voyants fixes |
| S6 | Sain | 2 applications/libérations normales |

- [ ] **Étape 1 : dérouler S0→S6.** Si S2 ne produit pas le
  clignotement après un essai verbatim complet : attendre 60 s
  (manuel), un second essai, puis arrêter — H4 perd un point,
  on ne force pas. Si S3 produit voyant jaune fixe et message
  « PARKING BRAKE SERVICE REQUIRED » : c'est la signature de
  succès du manuel 2020 ; l'absence de bruit de rétraction à
  ce stade est conforme au manuel, pas un échec.
- [ ] **Étape 2 : diff S1→S2, S2→S3, S3→S4, S4→S5** avec
  l'outil de la tâche 1, restreint d'abord à l'ECU candidat de
  la tâche 3. Critères d'un champ « mode maintenance » :
  bascule en S3 (pas en S1), stable en S4, retour en S5.
  Critères d'un champ « position actionneur » : suit
  serré/desserré dès S0/S1 et sature différemment en S3.
- [ ] **Étape 3 : répétition.** Un second déroulé complet un
  autre jour, mêmes captures S1/S3/S5 minimum. Un champ qui ne
  se répète pas n'est pas un candidat.
- [ ] **Étape 4 : verdicts H4/H5 dans le compte rendu**, avec
  les messages cluster mot pour mot et les valeurs des champs
  candidats par état et par jour. Préciser noir sur blanc si
  le « voyant clé jaune » du 2026-10-31 correspond à la
  signature S3. Commit :
  `docs(epb): séquence manuelle 2020 éprouvée état par état`.

### Tâche 5 — Écoute CAN passive pendant la séquence (conditionnelle)

À faire si la tâche 3 ne trouve aucun DID d'état (H3), ou en
parallèle de la tâche 4 si le WiCAN est disponible — l'écoute
ne coûte rien et horodate ce que le diff ne voit pas.

- [ ] **Étape 1 : écoute aux broches OBD-II.** WiCAN en
  `listen-only`, enregistrement candump/BLF sur microSD
  pendant un déroulé S0→S6 complet, notes d'horloge à chaque
  changement d'état/voyant. Attendu probable d'après
  `meatpiHQ/wican-fw#468` : aucun trafic spontané — un fichier
  vide avec contrôle positif (nos propres requêtes/réponses
  visibles si l'app tourne en même temps via un second
  adaptateur est impossible sur la même prise : choisir,
  écoute seule ou diagnostic seul, jamais de Y bricolé).
- [ ] **Étape 2 : seulement si l'étape 1 est muette et avec
  l'accord explicite du propriétaire**, écoute sur le bus BCM
  (connecteur gris 40 broches, guide Fortin 119701) via un
  faisceau en T du commerce. Conditions de
  `epb-mode-service.md` : `listen-only`, aucune trame émise,
  aucune terminaison ajoutée, aucun fil coupé, broches
  confirmées au multimètre et sur le schéma avant branchement,
  aucun calculateur débranché sous tension.
- [ ] **Étape 3 : analyse.** Corréler les IDs qui changent aux
  horodates des états S2/S3/S5. Un ID candidat EPB doit
  changer à chaque serrage/desserrage normal (S0/S1) — sinon
  ce n'est pas l'actionneur. Les logs bruts restent hors git
  (ils peuvent contenir le VIN en trames) ; seuls des extraits
  anonymisés et relus entrent au compte rendu. Commit :
  `docs(epb): écoute passive OBD puis BCM pendant la séquence`.

### Tâche 6 — Voie documentaire et observation d'un outil légitime

En parallèle des tâches terrain ; c'est la seule voie qui peut
nommer la séquence **diagnostique** (MUT-III), que ce plan
n'émettra de toute façon jamais.

- [ ] **Étape 1 : manuel GN.** Le propriétaire obtient la page
  « maintenance mode » NS22/GN sur mitsubishitechinfo.com
  (abonnement atelier ; action propriétaire, aucun contournement
  d'accès). Questions fermées à trancher : la méthode au
  commutateur existe-t-elle pour la GN ? Quelle fonction
  spéciale MUT-III SE, quelles préconditions, quelle sortie ?
- [ ] **Étape 2 : corroboration T33.** Chercher dans la
  documentation Nissan Rogue/X-Trail T33 (même plateforme) le
  « work support » EPB de CONSULT : à consigner comme hypothèse
  d'ECU et de nom de fonction, jamais comme fait Mitsubishi.
- [ ] **Étape 3 : observation d'un outil légitime (si
  l'occasion existe).** Si un atelier ou un outil J2534
  légitime du propriétaire exécute l'entrée en maintenance,
  WiCAN en `listen-only` sur la prise pendant l'opération :
  on observe les échanges sans émettre. Le journal d'écoute
  nomme l'ECU, la session et la routine — à consigner comme
  fait observé. Réémettre cette routine reste interdit tant
  que les critères de `epb-mode-service.md` (« Critère pour
  aller plus loin qu'un guide ») ne sont pas tous réunis et la
  spec amendée en conséquence. En particulier, la piste vidéo
  « désactiver la Security Gateway par menu + accélérateur »
  n'est pas à reproduire : si un état passerelle temporaire
  existe, on l'observe (nouveaux répondants, NRC qui changent),
  on ne le contourne pas.
- [ ] **Étape 4 : synthèse documentaire** dans
  `docs/reverse-engineering/epb-mode-service.md` : ce qui est
  prouvé pour MY2024, ce qui reste de la génération précédente,
  ce qui est réfuté. Commit :
  `docs(epb): voie documentaire GN et observation outil légitime`.

### Tâche 7 — Conclusion : DID d'état promu ou constat documenté

**Fichiers (selon le résultat) :**
- Modifier : `protocol/src/main/kotlin/ca/mrklar/outlander/protocol/vehicle/Signals.kt` (seulement si un DID d'état est prouvé)
- Modifier : `docs/reverse-engineering/epb-mode-service.md` (toujours)

- [ ] **Cas DID prouvé** (critères des tâches 3–4 remplis deux
  jours distincts) : ajouter la `SignalDefinition` avec
  `source` citant les séances et `confidence =
  CORRELE_TABLEAU_DE_BORD` au maximum (jamais `VERIFIE` sans
  corrélation étalonnée, spec §8.2), test de décodage d'abord,
  `:protocol:test` puis `:app:assembleDebug` verts. Le livrable
  utilisateur reste un **guide** : l'app affiche la séquence
  manuelle prouvée pas à pas et lit l'état avant/après ; elle
  refuse d'afficher « pistons repoussables » tant que le champ
  position n'est pas lui aussi prouvé. Aucun bouton
  d'actionnement.
- [ ] **Cas H3/H5** : aucun code. Consigner le constat avec la
  taxonomie de la campagne (ce qui a été épuisé, contrôles
  positifs cités) : le volet EPB se referme en « guide de la
  procédure documentée + pas de lecture d'état possible par
  nos voies », ce qui est un résultat, pas un échec.
- [ ] **Dans tous les cas** : mettre à jour la section
  « Prochaine séance » du compte rendu de séance et l'état du
  `README.md`. Commit :
  `docs(epb): verdict de la découverte de séquence` (plus
  `feat(protocol): DID d'état EPB corrélé` le cas échéant).

## Auto-revue du plan

- Couverture : identification de l'ECU (tâche 3), séquence
  manuelle (tâche 4), invisibilité passerelle (tâche 5),
  séquence diagnostique (tâche 6, observation seulement),
  livrable (tâche 7). Les tâches 0–2 sont les préalables
  spec, outillage et attribution par ECU sans lesquels les
  captures du 2026-10-31 se répéteraient à l'identique.
- Liste blanche : chaque émission prévue est `0x22`, `0x19`,
  `0x10 01/03` ou `0x3E` via le garde existant ; l'écoute
  passive n'émet rien. Aucune tâche ne demande `0x31`.
- Ordre : 0 → 1 → 2 → 3 → 4 ; 5 et 6 en parallèle dès que le
  matériel ou les documents sont là ; 7 en dernier.
  Les tâches 3 et 4 peuvent tenir dans la même séance véhicule
  que le point SOC bas de la campagne batterie (même contrôle
  positif, même 12 V) : regrouper pour économiser les séances.

