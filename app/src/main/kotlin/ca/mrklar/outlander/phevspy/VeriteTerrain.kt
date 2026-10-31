package ca.mrklar.outlander.phevspy

/**
 * Vérité terrain consignée au journal (Kotlin pur, testable).
 *
 * Le cluster de l'Outlander PHEV 2024 n'affiche pas de SOC en % :
 * le propriétaire compte les barres de jauge allumées, sur 16
 * (confirmation 2026-10-31). Le journal garde ce compte brut
 * (`N/16`) — chaque barre vaut ~6,25 % à titre indicatif, seuils
 * inconnus ; aucune conversion en % n'est faite ici.
 */
internal fun partiesVeriteTerrain(barresCluster: String, autonomieKm: String): List<String> {
    val barres = barresCluster.trim()
    val autonomie = autonomieKm.trim()
    return buildList {
        if (barres.isNotEmpty()) add("SOC (barres cluster): $barres/16")
        if (autonomie.isNotEmpty()) add("autonomie: $autonomie km")
    }
}
