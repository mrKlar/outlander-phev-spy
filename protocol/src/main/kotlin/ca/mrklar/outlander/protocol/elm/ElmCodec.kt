package ca.mrklar.outlander.protocol.elm

/** Sort de la réponse ELM à une commande (spec §4.3).
 *  [TIMEOUT] n'est jamais produit par [ElmCodec.classify] : il est réservé
 *  à la session, seule à savoir qu'aucun bloc n'est arrivé à temps. */
enum class ElmStatus { POSITIVE, NRC, NO_DATA, TIMEOUT, ELM_ERROR, BUFFER_FULL }

/** Réponse ELM complète (délimitée par le prompt `>`) et classifiée. */
data class ElmResponse(
    val command: String,
    val rawText: String,              // texte verbatim avant tout parsing
    val lines: List<String>,
    val status: ElmStatus,
    val elmTokens: List<String>,      // SEARCHING..., NO DATA, CAN ERROR, BUFFER FULL, ?, OK...
    val durationMs: Long,
)

/** Découpe un flux d'octets ELM327 en blocs terminés par le prompt `>`
 *  et classifie chaque bloc en [ElmResponse].
 *
 *  Le découpage tolère des chunks arbitraires (aucun alignement supposé) :
 *  coupe en pleine ligne, `>` seul, plusieurs blocs dans un même chunk.
 *  Décodage ISO-8859-1 pour ne perdre aucun octet. */
class ElmCodec {

    private val accumule = StringBuilder()

    /** Absorbe un chunk et retourne les blocs terminés par `>` devenus
     *  complets (texte brut verbatim, sans le `>` final). Le reliquat
     *  non terminé reste accumulé pour les appels suivants. */
    fun feed(chunk: ByteArray): List<String> {
        accumule.append(String(chunk, Charsets.ISO_8859_1))
        val blocs = mutableListOf<String>()
        while (true) {
            val prompt = accumule.indexOf('>')
            if (prompt < 0) break
            blocs += accumule.substring(0, prompt)
            accumule.delete(0, prompt + 1)
        }
        return blocs
    }

    /** Texte brut accumulé pas encore terminé par `>`. */
    fun pending(): String = accumule.toString()

    /** Vide l'accumulateur (abandonne tout partiel en cours). */
    fun reset() {
        accumule.setLength(0)
    }

    /** Classifie le [rawText] d'un bloc complet reçu pour [command].
     *
     *  Lignes = découpage sur `\r`/`\n`, vides jetées, chacune trimmée.
     *  Si la première ligne égale [command] (écho, trim + casse ignorée),
     *  elle est retirée des lignes (mais reste dans le brut). Les lignes
     *  reconnues comme tokens ELM sortent des lignes et sont consignées
     *  dans [ElmResponse.elmTokens] telles que reçues (trimmées).
     *
     *  Statut, par priorité : `BUFFER FULL` présent → [ElmStatus.BUFFER_FULL] ;
     *  sinon lignes de données non vides → [ElmStatus.POSITIVE] (sauf
     *  [ElmStatus.NRC] si l'unique ligne, espaces retirés, commence par `7F`) ;
     *  sinon `NO DATA` présent → [ElmStatus.NO_DATA] ; sinon `OK` présent →
     *  [ElmStatus.POSITIVE] ; sinon [ElmStatus.ELM_ERROR]. */
    fun classify(command: String, rawText: String, durationMs: Long): ElmResponse {
        var toutes = rawText.split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (toutes.isNotEmpty() && toutes.first().equals(command.trim(), ignoreCase = true)) {
            toutes = toutes.drop(1)
        }

        val tokens = mutableListOf<String>()
        val lines = mutableListOf<String>()
        for (ligne in toutes) {
            if (estTokenElm(ligne)) tokens += ligne else lines += ligne
        }

        val status = when {
            tokens.any { it.equals("BUFFER FULL", ignoreCase = true) } -> ElmStatus.BUFFER_FULL
            lines.isNotEmpty() ->
                if (lines.size == 1 && lines.single()
                        .filterNot { it.isWhitespace() }
                        .startsWith("7F", ignoreCase = true)
                ) {
                    ElmStatus.NRC
                } else {
                    ElmStatus.POSITIVE
                }
            tokens.any { it.equals("NO DATA", ignoreCase = true) } -> ElmStatus.NO_DATA
            tokens.any { it.equals("OK", ignoreCase = true) } -> ElmStatus.POSITIVE
            else -> ElmStatus.ELM_ERROR
        }

        return ElmResponse(
            command = command,
            rawText = rawText,
            lines = lines,
            status = status,
            elmTokens = tokens,
            durationMs = durationMs,
        )
    }

    private fun estTokenElm(ligne: String): Boolean =
        TOKENS.any { it.equals(ligne, ignoreCase = true) } ||
            ligne.startsWith("BUS INIT:", ignoreCase = true)

    private companion object {
        /** Tokens reconnus mot pour mot (après trim, casse ignorée). */
        val TOKENS: Set<String> = setOf(
            "SEARCHING...",
            "NO DATA",
            "CAN ERROR",
            "BUFFER FULL",
            "?",
            "OK",
            "STOPPED",
            "UNABLE TO CONNECT",
            "FB ERROR",
            "DATA ERROR",
            "<DATA ERROR",
        )
    }
}
