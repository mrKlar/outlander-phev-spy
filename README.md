# Outlander PHEV Spy

Application Android personnelle (Kotlin + Compose), dans l'esprit de LeafSpy, pour lire l'état de la batterie de traction d'un Mitsubishi Outlander PHEV 2024 (4e génération) via un adaptateur ELM327 en BLE ou Bluetooth Classic.

Le protocole batterie de cette génération n'est pas documenté publiquement de façon fiable : le projet est d'abord un outil de découverte. Il interroge le véhicule en UDS sur CAN 29 bits, journalise tout en brut, et ne décode que ce qui a été corrélé au tableau de bord du véhicule cible.

## État

Jalons M0 à M3 terminés (2026-10-29 au 2026-10-31) : toolchain contenue, cœur protocole prouvé sans voiture, transports BLE et Bluetooth Classic réels, première session sur le véhicule cible. Jalon M4 (décodeurs corrélés) en cours.

Premier dialogue réel le 2026-10-31 : les DID batterie Gen4 répondent en session par défaut, sans NRC de verrouillage observé. Deux signaux sont promus « corrélés au tableau de bord » : le SOC (DID `01D1`, deux points de jauge) et l'odomètre (DID `F0D0`). La tension pack, les cellules max/min et les 96 tensions de cellules individuelles (DID `0114`–`0173`) sont lisibles et cohérentes entre elles, mais restent des candidats non étalonnés. Aucun SOH n'est affiché (aucun dénominateur sourcé).

Suite : captures à SOC plus bas pour éprouver l'échelle SOC, et découverte en lecture seule de l'état EPB selon le plan dédié (voir Documentation). Les journaux de session réels restent hors dépôt (`captures/`, gitignoré : ils contiennent le VIN).

## Avertissement sécurité

- **Lecture seule, par construction.** L'application n'émet que des requêtes de lecture figurant sur une liste blanche codée en dur et vérifiée par test (modes OBD de lecture, UDS `0x22`/`0x19`/`0x3E`, sessions `0x10` limitées). Aucune écriture, aucun effacement de DTC, aucun accès sécurité, aucun contournement de passerelle.
- **Usage à vos risques.** Interroger un véhicule par la prise OBD reste une opération que vous menez sous votre responsabilité. Un scan maintient les calculateurs éveillés et sollicite la batterie 12 V ; un adaptateur laissé branché draine le +12 V permanent de la broche 16.
- **Jamais en roulant.** Ne jamais lancer ni surveiller un scan en conduisant. Sessions à l'arrêt uniquement, véhicule stationné, dehors ou dans un lieu ventilé : en état READY, le moteur thermique peut démarrer seul.
- Retirer l'adaptateur en quittant le véhicule.

## Documentation

- [Synthèse décisionnelle et rapports de recherche](docs/recherche/) — état des connaissances : ancien protocole, génération 2024, scan UDS sûr, écosystème Android.
- [Spécification de design](docs/superpowers/specs/2026-10-29-outlander-phev-spy-design.md) — architecture, liste blanche, protocole d'interrogation, plan de première session véhicule, journal JSONL, jalons.
- [Séance du 2026-10-31](docs/reverse-engineering/seance-2026-10-31.md) — premier dialogue réel : DID lisibles, promotions, défauts ELM corrigés.
- [Campagne SOH et cellules](docs/reverse-engineering/campagne-soh-cellules.md) — protocole de captures corrélées et critères de promotion.
- [EPB — recherche du mode service](docs/reverse-engineering/epb-mode-service.md) — état de la recherche, observation seulement ; exécution : [plan de découverte](docs/superpowers/plans/2026-11-01-decouverte-sequence-maintenance-epb.md).
- [AGENTS.md](AGENTS.md) — règles pour les agents de codage qui interviennent sur ce dépôt.

Dépôt **public**, projet personnel en cours de développement. Aucune licence pour l'instant (droits réservés par défaut) ; le choix d'une licence libre reste à faire. Documentation et commits en français.
