# Outlander PHEV Spy

Application Android personnelle (Kotlin + Compose), dans l'esprit de LeafSpy, pour lire l'état de la batterie de traction d'un Mitsubishi Outlander PHEV 2024 (4e génération) via un adaptateur ELM327 en BLE ou Bluetooth Classic.

Le protocole batterie de cette génération n'est pas documenté publiquement de façon fiable : le projet est d'abord un outil de découverte. Il interroge le véhicule en UDS sur CAN 29 bits, journalise tout en brut, et ne décode que ce qui a été corrélé au tableau de bord du véhicule cible.

## État

Spécification rédigée, aucun code source pour l'instant. Les jalons prévus vont du bootstrap de la toolchain (M0) aux décodeurs corrélés (M4) ; le cœur protocole sera prouvé sans voiture, sur émulateur et par rejeu de captures, avant tout branchement au véhicule.

## Avertissement sécurité

- **Lecture seule, par construction.** L'application n'émet que des requêtes de lecture figurant sur une liste blanche codée en dur et vérifiée par test (modes OBD de lecture, UDS `0x22`/`0x19`/`0x3E`, sessions `0x10` limitées). Aucune écriture, aucun effacement de DTC, aucun accès sécurité, aucun contournement de passerelle.
- **Usage à vos risques.** Interroger un véhicule par la prise OBD reste une opération que vous menez sous votre responsabilité. Un scan maintient les calculateurs éveillés et sollicite la batterie 12 V ; un adaptateur laissé branché draine le +12 V permanent de la broche 16.
- **Jamais en roulant.** Ne jamais lancer ni surveiller un scan en conduisant. Sessions à l'arrêt uniquement, véhicule stationné, dehors ou dans un lieu ventilé : en état READY, le moteur thermique peut démarrer seul.
- Retirer l'adaptateur en quittant le véhicule.

## Documentation

- [Synthèse décisionnelle et rapports de recherche](docs/recherche/) — état des connaissances : ancien protocole, génération 2024, scan UDS sûr, écosystème Android.
- [Spécification de design](docs/superpowers/specs/2026-10-29-outlander-phev-spy-design.md) — architecture, liste blanche, protocole d'interrogation, plan de première session véhicule, journal JSONL, jalons.
- [AGENTS.md](AGENTS.md) — règles pour les agents de codage qui interviennent sur ce dépôt.

Dépôt privé, projet personnel. Documentation et commits en français.
