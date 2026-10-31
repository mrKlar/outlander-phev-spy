# EPB — recherche du mode service du frein de stationnement électrique

Volet de recherche ajouté à la demande du propriétaire le 2026-10-31.
Objectif : comprendre comment l'Outlander PHEV 2024 entre et sort du
mode service/maintenance du frein de stationnement électrique (EPB)
pour le remplacement des plaquettes arrière, et déterminer ce qu'une
application pourrait un jour offrir sans compromettre la sécurité.

## Frontière de sécurité

Ce volet est, à ce stade, **observation et lectures seulement**.

- Aucune commande d'actionnement n'est ajoutée à l'application.
- Aucun balayage de `RoutineControl (0x31)`, `InputOutputControl
  (0x2F)`, `WriteDataByIdentifier (0x2E)` ou `SecurityAccess (0x27)` :
  ces services restent sur la liste noire de la spec (§5).
- On ne devine jamais un identifiant de routine sur le véhicule. Une
  routine inconnue peut serrer/desserrer les étriers, initialiser un
  capteur ou lancer une fonction sans rapport avec l'EPB.
- Un éventuel bouton d'actionnement exigerait un chantier séparé :
  routine d'entrée **et de sortie** sourcée chez le constructeur,
  lecture du statut, gestion d'erreur, double confirmation explicite,
  véhicule sécurisé (cales, chandelles au besoin, personne sous le
  véhicule sans support approprié), maintien de charge 12 V et
  procédure de récupération si la séquence échoue au milieu.

En attendant, la seule fonctionnalité applicative envisageable sans
nouveau design est un **guide de la procédure manuelle vérifiée pour
le millésime 2024**, affichant les étapes sans émettre de commande
d'actionnement, plus la lecture des états EPB si des DID sont prouvés.

## Ce qui est public, classé par force de preuve

### Ancienne génération — source constructeur, non transposable telle quelle

Le bulletin Mitsubishi `MSB-16EX35-502` (2016) vise notamment
l'Outlander PHEV `GG0W`. Il décrit la sélection du mode maintenance
de l'EPB avec le MUT-III ou par une séquence de commutateurs ; les
extraits indexés mentionnent notamment le frein de stationnement
électrique puis le commutateur **Brake Auto Hold au moins 10 fois**,
avant de pouvoir repousser le piston pour remplacer plaquettes ou
disque arrière.

Source : <http://mmc-manuals.ru/msb/BASE/003/2016/MSB-16EX35-502/E/MSB-16EX35-502.pdf>

Ce document prouve que Mitsubishi a historiquement offert deux voies
(outil constructeur et séquence) sur l'ancien modèle. Il ne prouve ni
que la même séquence convient au 2024, ni quel ECU ni quelle routine
le MUT-III invoque sur la 4e génération.

### Génération ZM 2022+ — pistes vidéo, non corroborées

Une vidéo intitulée « Mitsubishi ZM Outlander EPB Release – Brake Pad
Replacement (2022–Current) » prétend qu'il faut désactiver
temporairement la Security Gateway par un menu du véhicule avant
qu'un outil de diagnostic commercial puisse entrer en mode
maintenance de l'EPB ; son descriptif mentionne notamment deux
pressions complètes sur l'accélérateur et la réactivation par
l'interrupteur EPB à la fin.

Source : <https://www.youtube.com/watch?v=2brM2AfhCOc>

Une seconde vidéo sur un Outlander 2024 montre le remplacement des
freins arrière « sans scan tool » en retirant le module actionneur et
en rétractant le mécanisme manuellement, tout en précisant que ce
n'est pas un substitut approuvé par le constructeur.

Source : <https://www.youtube.com/watch?v=Q71ltyCWB2A>

Traitement dans ce projet :

- la première est une **hypothèse à vérifier** (existence et rôle
  d'un état temporaire de passerelle, conditions d'entrée/sortie) ;
- la seconde est un contournement mécanique, **écarté** comme
  méthode du projet ;
- aucune des deux ne suffit à coder une émission vers le véhicule.

### Ce que dit notre recherche 2023+

Le freinage et l'EPB de la 4e génération sont vraisemblablement liés
à l'architecture Alliance/ESC, mais notre cartographie actuelle ne
nomme pas encore les répondants `0xDB`, `0xDE` et `0xE5`. Tant que
l'ECU qui porte l'EPB n'est pas identifié par ses DID
d'identification (`F1xx`) et corrélé aux états observés, parler d'une
« routine EPB » précise serait inventer.

## Questions de recherche

1. Quel ECU logique porte les états et défauts de l'EPB : ESC/ABS
   intégré, BCM, PHEV-ECU ou module dédié ?
2. Quels DID **en lecture** changent de façon reproductible entre :
   frein serré, frein desserré, Auto Hold actif/inactif et mode
   maintenance sélectionné manuellement par le propriétaire ?
3. Existe-t-il un état temporaire de passerelle sur le 2024
   nord-américain, observable sans écriture (nouveaux répondants,
   NRC qui changent, DID qui deviennent lisibles) ?
4. La documentation de service officielle du millésime décrit-elle
   une fonction spéciale MUT-III SE, son nom, ses préconditions et
   sa procédure de sortie/réinitialisation ?
5. Les outils J2534/aftermarket qui réussissent parlent-ils au même
   ECU et dans la même session que nos lectures, ou utilisent-ils un
   contournement BCM/passerelle hors de notre périmètre ?

## Protocole d'observation proposé

Le propriétaire sait déjà exécuter la séquence manuelle. On
l'utilise comme **vérité terrain**, sans la reproduire dans l'app
tant qu'elle n'est pas confirmée pour MY2024 par une source
constructeur.

Pour chaque état ci-dessous, véhicule stationné en sécurité,
capture en lecture seule (identifications connues, DTC en lecture,
DID candidats déjà répondants) avant/après, avec horodatage et note
d'état au journal :

| # | État | Ce qu'on attend observer |
|---|---|---|
| 1 | Repos, EPB serré | Référence |
| 2 | Repos, EPB desserré | Bits d'état et positions actionneurs |
| 3 | Juste après l'entrée manuelle en mode maintenance | État maintenance, voyants, nouveaux répondants/DID |
| 4 | En mode maintenance après 2 minutes | Persistance, tensions, éventuels NRC nouveaux |
| 5 | Juste après la sortie manuelle du mode | Réinitialisation et retour des états |
| 6 | Après 2 applications/libérations normales | État sain de référence |

À chaque état : noter aussi les voyants/messages au tableau de
bord, le bruit des actionneurs (durée approximative), la tension
12 V et si le véhicule est en READY ou seulement contact.

Analyse : diff octet par octet entre états adjacents ; un champ
« mode maintenance » doit changer entre 2→3, rester stable en 4 et
revenir en 5. Un champ de position d'actionneur doit évoluer entre
1 et 2 et saturer différemment en 3. Tout candidat suit ensuite les
critères de promotion de `campagne-soh-cellules.md`, y compris la
règle de preuve négative.

## Critère pour aller plus loin qu'un guide

Un actionnement dans l'app ne sera envisagé que si **toutes** ces
conditions sont réunies :

- procédure d'entrée et de sortie issue de la documentation de
  service officielle du bon millésime/marché, ou démontrée par un
  outil légitime dont on peut observer les échanges sans matériel
  divulgué illicitement ;
- identifiants exacts de session/routine/statut, réponses positives
  et négatives connues, comportement en cas d'interruption ;
- lecture d'état fiable avant et après, permettant de refuser
  l'action si l'état n'est pas celui attendu ;
- nouveau design de sécurité relu séparément, tests d'abord sur
  émulateur, puis essai véhicule roues sécurisées avec procédure de
  récupération.

À défaut, le volet reste un guide manuel + lectures d'état, ce qui
est déjà utile et ne met ni le véhicule ni son propriétaire en
danger.
