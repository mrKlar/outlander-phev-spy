package ca.mrklar.outlander.phevspy

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PolitiqueActivationTest {

    @Test
    fun `connecte sans scan en cours active les actions de session`() {
        assertTrue(actionsSessionActives(connecte = true, scanEnCours = false))
    }

    @Test
    fun `connecte avec scan en cours desactive les actions de session`() {
        assertFalse(actionsSessionActives(connecte = true, scanEnCours = true))
    }

    @Test
    fun `non connecte sans scan en cours desactive les actions de session`() {
        assertFalse(actionsSessionActives(connecte = false, scanEnCours = false))
    }

    @Test
    fun `non connecte avec scan en cours desactive les actions de session`() {
        assertFalse(actionsSessionActives(connecte = false, scanEnCours = true))
    }
}
