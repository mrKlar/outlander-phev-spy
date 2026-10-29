package ca.mrklar.outlander.protocol.elm

import ca.mrklar.outlander.protocol.journal.InMemoryJournal
import ca.mrklar.outlander.protocol.transport.TcpElmTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Intégration réelle contre ELM327-emulator (Ircama) en TCP local.
 *
 * Gardée par [assumeTrue] : sans hôte/port fournis, le test est sauté
 * (jamais rouge en CI sans émulateur). Fournir, au choix :
 * - propriétés système `elm.emu.host` / `elm.emu.port` de la JVM de test ;
 * - variables d'environnement `ELM_EMU_HOST` / `ELM_EMU_PORT` (une `-D`
 *   sur la ligne Gradle ne descend pas dans la JVM de test ; le
 *   harnais `scripts/run-emulator-it.sh` exporte donc les deux formes).
 *
 * Verdict observé le 2026-10-29 (ELM327-emulator 4.0.0, scénario par
 * défaut, sonde socket manuelle puis ce test) : init complète acceptée
 * (`ATI` → « ELM327 v1.5 », `STI` → « STN1100 v1.2.3 », `ATRV` → 13.1V,
 * `ATCEA` → OK) et `0100` → « SEARCHING... / 7E8 06 41 00 38 3B 80 13 »,
 * donc [ElmStatus.POSITIVE]. L'assertion accepte aussi
 * [ElmStatus.NO_DATA] (autre dictionnaire possible), mais le statut
 * réellement observé est POSITIVE.
 */
class EmulatorIntegrationTest {

    @Test
    fun initCompleteEt0100SurEmulateurReel() {
        val host = System.getProperty("elm.emu.host") ?: System.getenv("ELM_EMU_HOST")
        val portTexte = System.getProperty("elm.emu.port") ?: System.getenv("ELM_EMU_PORT")
        assumeTrue(host != null && portTexte != null) {
            "émulateur non configuré (elm.emu.host/port ou ELM_EMU_HOST/PORT)"
        }
        val port = portTexte!!.toInt()

        runBlocking {
            val transport = TcpElmTransport.connect(host!!, port, connectTimeoutMs = 5000)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val journal = InMemoryJournal(sessionId = "emulator-it")
            val session = ElmSession(
                scope = scope,
                transport = transport,
                journal = journal,
                transportKind = "tcp-emu",
            )
            try {
                val capacites = session.initialize()
                println("capacités observées: $capacites")
                assertTrue(capacites.ati.isNotBlank(), "ATI vide: $capacites")

                val reponse = session.transact("0100", timeoutMs = 5000, phase = "emulator-it", kind = "obd")
                println("0100 observé: statut=${reponse.status} lignes=${reponse.lines}")
                assertTrue(
                    reponse.status == ElmStatus.POSITIVE || reponse.status == ElmStatus.NO_DATA,
                    "0100 doit être classé sans échec de lien, reçu ${reponse.status} (${reponse.rawText})",
                )

                // Le journal porte l'init complète (16 transactions) + le 0100 ci-dessus.
                assertTrue(journal.events.size >= 17, "journal=${journal.events.size} événements")
            } finally {
                session.close()
                scope.cancel()
            }
        }
    }
}
