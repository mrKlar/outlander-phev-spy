package ca.mrklar.outlander.phevspy

/**
 * Politique d'activation des actions de session (scan, balayage DID,
 * snapshot EPB) : actives dès qu'un adaptateur est connecté et
 * qu'aucune opération n'est déjà en cours.
 */
internal fun actionsSessionActives(connecte: Boolean, scanEnCours: Boolean): Boolean =
    connecte && !scanEnCours
