# Campagne SOH et cellules — protocole de captures corrélées

Protocole de la campagne terrain qui suit le chantier « voie fonctionnelle
Gen4 » (campagne 1). Il ne contient aucun VIN, aucune donnée personnelle
et aucune valeur tirée d'un journal réel : seulement les faits du fil
GitHub `meatpiHQ/wican-fw#468` et de la spécification du dépôt
(`docs/superpowers/specs/2026-10-29-outlander-phev-spy-design.md`).

## Objectif et définitions

Objectif : déterminer, par captures corrélées sur le véhicule cible,
quels DID rapportés par la communauté décrivent réellement la batterie
HV, puis relier chaque octet candidat à une vérité terrain avant toute
équation. Aucune équation du profil communautaire n'est encodée à ce
stade : la sémantique des indices « B » de Car Scanner par rapport à
notre charge après en-tête `62 XX XX` n'est pas encore établie.

- **SOH capacité** : rapport entre la capacité actuellement
  utilisable du pack et sa capacité nominale à l'état neuf. Il exige
  un dénominateur nommé et sourcé ; en v1, aucun SOH n'est affiché
  (spec §8.2). La campagne cherche une méthode de mesure, pas un
  affichage.
- **Delta cellules** : différence entre la tension de cellule la plus
  haute et la plus basse au même instant, dans des conditions
  comparables (repos, même SOC approximatif). Candidats rapportés :
  `220178` et `220179`.
- **Températures** : températures du pack ou des modules. Leur
  décodage est inconnu ; aucune formule du type « octet − 40 » n'est
  admise (spec §8.4). On cherche des champs qui varient lentement et
  de façon cohérente avec le froid, la charge et le roulage.

## Ce qui est prouvé et ce qui ne l'est pas

Source primaire : GitHub `meatpiHQ/wican-fw#468`.

Prouvé (sur les véhicules des rapporteurs, pas encore sur le véhicule
cible) :

- 2025-07-06, @amwalters : WiCAN-OBD-PRO v4.2.3, PID de base en
  ISO 15765-4 CAN 29 bits ; `ATMA`, SavvyCAN et candump/slcan sans
  trafic observé.
- 2025-07-07, @meatpiHQ : le véhicule ne diffuse pas de CAN ; seul
  l'OBD2 en requête/réponse est supporté.
- 2025-07-20, @amwalters : SOC fonctionnel via `2201D1`, en-tête
  fonctionnel Car Scanner `18DB33F1`, filtre de réception
  `ATCRA18DAF1DB` pour ne garder que le BMU.
- 2025-10-31, @amwalters : profil « Mitsubishi: Outlander-PHEV
  Gen4 », initialisation `ATSP7;ATST96;`, DID et expressions Car
  Scanner repris dans le registre ; aucun SOH trouvé ; `2201D0`
  n'est qu'une hypothèse nommée `SOC_MAX` par l'auteur, avec
  l'avertissement explicite « take everything with a grain of salt ».
- 2025-11-01, @miroslavvuj : le JSON « seems to work well » — une
  seule corroboration.

Non prouvé :

- Toute équation du profil sur le véhicule cible, SOC compris :
  l'indice « B5 » de Car Scanner n'est pas encore relié à nos
  offsets après `62 XX XX`.
- Les interprétations concurrentes du DID `22010B` (capacité HV en
  kWh ou second SOC) et l'identité exacte de chaque DID DE/E5.
- L'existence d'un SOH lisible par DID. `2201D0` reste une piste,
  jamais une santé affichée.
- Sur le véhicule cible, aucune réponse positive n'a encore été
  obtenue : silence non concluant, jamais « ECU absent » ni « DID
  inexistant ».

## Matrice de captures corrélées

Chaque ligne de la matrice est une séance de capture : sondes Gen4
(fonctionnel d'abord), et au besoin balayage fonctionnel borné
`0100-01FF` vers la cible choisie. Les DID déjà positifs sont relus
à chaque séance pour suivre leurs octets.

| État visé | Condition de capture |
|---|---|
| SOC tableau de bord 100 % | Après charge complète, repos de 30 min |
| SOC 80 % | Repos de 30 min, sans charge en cours |
| SOC 60 % | Repos de 30 min, sans charge en cours |
| SOC 40 % | Repos de 30 min, sans charge en cours |
| SOC 20 % | Repos de 30 min, sans charge en cours |
| Après charge AC | Fin de charge, puis repos de 30 min |
| Après trajet | Retour de trajet, puis repos de 30 min |
| Froid du matin | Véhicule resté dehors la nuit, avant tout roulage |

Vérités terrain obligatoires à **chaque** capture, consignées au
journal via l'écran de session avant ou juste après les lectures :

- SOC affiché au tableau de bord (%) ;
- autonomie affichée (km) ;
- état du véhicule (READY ou non-READY) et charge AC en cours ou
  non ;
- odomètre affiché (km), pour confronter la piste `22F0D0` ;
- durée du repos avant la capture ;
- pour une séance de charge : SOC de début et de fin et énergie
  délivrée mesurée à la borne ou au compteur.

À défaut d'une de ces vérités terrain, la capture reste exploratoire
et ne peut pas servir à promouvoir un DID.

## Méthode de diff d'octets entre états

1. Extraire, pour un même DID et un même en-tête répondant, la
   charge après les 3 octets `62 <DID>` dans chaque capture de la
   matrice.
2. Tabuler octet par octet (offset → valeurs par état) en regard des
   vérités terrain : SOC, repos, charge, froid.
3. Un champ candidat doit varier de façon monotone et reproductible
   avec la grandeur visée (par exemple suivre le SOC de 100 % à
   20 %) et rester stable entre deux captures au même état.
4. Écarter les faux candidats : compteurs qui ne font que croître,
   octets qui changent à chaque lecture au même état (bruit,
   horloge, somme de contrôle), champs qui suivent la durée de
   séance plutôt que la grandeur.
5. Pour chaque hypothèse d'équation (offset, longueur, échelle),
   noter les captures qui la soutiennent et celles qui la réfutent ;
   une seule corrélation ne suffit pas.

## Critères de promotion d'un DID (spec §8.2)

Les statuts de confiance de la spec gouvernent toute promotion :

1. `COMMUNAUTAIRE_NON_VALIDE` : piste externe non revérifiée sur le
   véhicule cible ; valeur en brut journalisé, jamais présentée
   comme une mesure.
2. `CORRELE_TABLEAU_DE_BORD` : le DID répond sur le véhicule cible
   et sa valeur brute varie de façon cohérente avec une vérité
   terrain journalisée (SOC affiché, autonomie) sur au moins deux
   lectures à des instants distincts.
3. `VERIFIE` : l'équation complète (offset d'octet, longueur,
   échelle, unité) a été corrélée sur une charge/décharge étalonnée
   du véhicule cible, avec la session de corrélation citée dans
   `source`.

La promotion est un changement de définition explicite et relu,
jamais un effet automatique du code ; elle cite la session et les
vérités terrain utilisées. Aucun SOH n'est affiché sans
dénominateur nommé et sourcé ; en v1, aucun SOH du tout.

## Recherche de blocs cellules et de températures

Dans les charges longues recueillies (sondes et balayage) :

- **Blocs cellules** : chercher des suites de valeurs de 2 octets
  contiguës dont une interprétation simple donne des tensions
  plausibles entre 2,5 V et 4,4 V, toutes voisines les unes des
  autres au repos. Une suite candidate doit bouger lentement avec
  le SOC entre les captures 100 % et 20 %, et son écart interne
  (max − min de la suite) doit être cohérent avec les candidats
  `220178`/`220179` s'ils répondent. Le nombre de valeurs de la
  suite est une observation, jamais une constante encodée d'avance.
- **Températures** : chercher des octets ou valeurs de 2 octets qui
  séparent nettement la capture « froid du matin » des captures
  « après trajet » et « après charge AC », en variant lentement
  d'une séance à l'autre. Ne retenir un champ que si son sens de
  variation (froid → chaud) se répète sur plusieurs paires de
  captures ; aucune échelle n'est posée avant ce tri.

## Méthode SOH par mesure d'énergie

Faute de SOH lu directement, la campagne mesure la capacité de
façon indirecte :

1. Charge AC complète mesurée : partir d'un SOC bas documenté,
   charger jusqu'à 100 % affiché, relever l'énergie délivrée à la
   borne ou au compteur et les DID bruts avant et après.
2. Conserver les conditions (même borne, même plage de SOC) pour
   rendre les mesures comparables dans le temps ; les pertes de
   conversion restent inconnues, donc la grandeur suivie est
   l'énergie mesurée par plage de SOC, pas un kWh « vrai » du pack.
3. Suivi longitudinal : à chaque séance de charge complète,
   comparer l'énergie mesurée, la valeur brute de `2201D0`
   (hypothèse `SOC_MAX`) et la plage brute de `2201D1` entre SOC bas
   et SOC 100 %. Une dérive conjointe et reproductible de ces trois
   séries est l'indice cherché ; tant qu'elle n'est pas observée,
   aucun des deux DID n'est un SOH.
4. Ne convertir en pourcentage de santé que le jour où un
   dénominateur nommé et sourcé existe (spec §8.2).

## Règles de sécurité

- Lecture seule stricte : seules les requêtes de la liste blanche
  partent vers le véhicule, via le garde du code.
- Jamais de scan en roulant : véhicule stationné, frein de parc
  serré, lieu ventilé, checklist de l'écran complétée.
- Plancher 12 V : hors READY, le scan s'arrête si la tension
  adaptateur passe sous 12,0 V ; ne pas multiplier les séances hors
  READY.
- Adaptateur débranché après chaque séance.

## Confidentialité

- Avant tout partage d'une capture ou d'un extrait : anonymiser le
  VIN et les adresses MAC, puis revérifier par re-lecture qu'il ne
  subsiste aucune de ces données.
- Les journaux bruts restent hors git : le dossier `captures/` et
  les journaux de session `*.jsonl` sont ignorés par le dépôt et ne
  s'y committent jamais.
