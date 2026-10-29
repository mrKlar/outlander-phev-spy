package ca.mrklar.outlander.protocol.journal

/** Masquage d'un VIN dans du texte (ligne JSONL ou capture), en
 *  conservant longueur et structure (spec §9.3).
 *
 *  Formes remplacées : hex espacé et hex compact de l'ASCII du VIN
 *  (casse quelconque, chaque octet devient `3F` — ou `3f` si la forme
 *  trouvée est minuscule) et VIN en ASCII direct (insensible à la
 *  casse, devient 17 `?`). */
object VinAnonymizer {

    /** Anonymise une ligne JSONL : c'est du texte, même traitement. */
    fun anonymizeLine(line: String, vin: String): String = anonymizeText(line, vin)

    /** Remplace toutes les formes du [vin] (17 caractères, sinon
     *  [IllegalArgumentException]) dans [text], longueurs inchangées. */
    fun anonymizeText(text: String, vin: String): String {
        require(vin.length == 17) { "VIN de 17 caractères attendu, reçu ${vin.length}" }

        val octetsHex = vin.uppercase().map { "%02X".format(it.code) }

        // Hex d'abord : ne pas risquer de casser une forme hex en
        // remplaçant une coïncidence ASCII à l'intérieur.
        var resultat = Regex(Regex.escape(octetsHex.joinToString(" ")), RegexOption.IGNORE_CASE)
            .replace(text) { m ->
                List(17) { if (estFormeMinuscule(m.value)) "3f" else "3F" }.joinToString(" ")
            }
        resultat = Regex(Regex.escape(octetsHex.joinToString("")), RegexOption.IGNORE_CASE)
            .replace(resultat) { m ->
                if (estFormeMinuscule(m.value)) "3f".repeat(17) else "3F".repeat(17)
            }

        return resultat.replace(vin, "?".repeat(17), ignoreCase = true)
    }

    /** Forme minuscule : au moins une lettre hex minuscule, aucune majuscule. */
    private fun estFormeMinuscule(s: String): Boolean =
        s.any { it in 'a'..'f' } && s.none { it in 'A'..'F' }
}
