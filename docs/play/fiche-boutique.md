# Fiche boutique — ébauche M0 (test fermé)

Textes préparés pour la fiche Google Play. Rien n'a été publié.

## Nom de l'application

Outlander PHEV Spy

- Package : `ca.mrklar.outlander.phevspy`
- Courriel de contact : **À FOURNIR par le propriétaire** (aucune adresse n'est inventée ici)

## Description courte

> Lecture seule de l'Outlander PHEV : découverte du protocole, en développement.

Compte : **78 caractères** (maximum Google Play : 80).

## Description complète

> Outlander PHEV Spy est un outil personnel, en développement, pour découvrir et documenter le protocole de diagnostic du Mitsubishi Outlander PHEV (génération 2023 et suivantes), via un adaptateur ELM327.
>
> Lecture seule, uniquement : l'application est conçue pour ne jamais écrire vers le véhicule — ni effacement de codes, ni commandes, ni contournement de passerelle sécurisée. Ne jamais l'utiliser en roulant.
>
> État actuel — squelette M0 (version 0.1.0-m0), soyons honnêtes :
> - l'application affiche un écran statique avec les compteurs du registre de signaux connus ;
> - aucune connexion au véhicule n'est encore implémentée ;
> - les permissions Bluetooth sont déclarées en préparation des étapes suivantes, mais ne sont ni demandées ni utilisées par cette version.
>
> Cette première mise en test fermé sert uniquement à valider la chaîne de publication, l'installation et le lancement. Les fonctions de lecture (état de charge, tensions, codes d'anomalie en lecture seule, etc.) arriveront plus tard, au rythme de la validation sur véhicule réel.
>
> Projet personnel, sans lien avec Mitsubishi Motors. « Outlander » et « PHEV » sont des marques de leurs détenteurs respectifs.

## Catégorie suggérée

**Auto et véhicules**

## Notes de version (test fermé)

Texte à coller dans la piste de test fermé (342 caractères, sauts de ligne inclus ; maximum Google Play : 500) :

```
Première version en test fermé (squelette M0, version 0.1.0-m0).
Écran statique seulement : l'app affiche les compteurs du registre de signaux connus.
Aucune connexion au véhicule, aucune lecture Bluetooth dans cette version.
Objectif du test : vérifier l'installation et le lancement. Lecture seule, toujours — ne jamais utiliser en roulant.
```

## Sécurité des données (ébauche M0)

Déclaration correspondant au squelette M0 actuel, à réviser à chaque version qui change ce portrait :

- **Données collectées : aucune.** Cette version n'a aucun compte, aucun analytique, aucun réseau.
- **Données partagées avec des tiers : aucune.**
- **Données transmises hors de l'appareil : aucune.** Aucune connexion au véhicule, aucun envoi.
- **Permissions déclarées mais non utilisées** : `android.permission.BLUETOOTH_SCAN` (avec `neverForLocation`) et `android.permission.BLUETOOTH_CONNECT` figurent au manifeste en préparation des transports Bluetooth à venir. La version M0 ne les demande pas à l'exécution et ne s'en sert pas.
- **Suppression des données** : sans objet, aucune donnée n'étant collectée.

> Attention : dès qu'une version lira le véhicule (VIN, journaux de session), cette section devra être réécrite **avant** téléversement. Les captures contenant un VIN réel ne quittent jamais l'appareil dans le design actuel, mais la déclaration Play devra le refléter explicitement.
