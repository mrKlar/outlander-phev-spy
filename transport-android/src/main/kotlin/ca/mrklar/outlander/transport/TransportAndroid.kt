package ca.mrklar.outlander.transport

/**
 * Ancrage du module `:transport-android` (M0).
 *
 * Les vrais transports Android — Bluetooth Low Energy et Bluetooth
 * Classic (RFCOMM/SPP) vers l'adaptateur ELM327 — arrivent en M2.
 * En M0, ce module ne contient aucun code d'émission : il prouve
 * seulement la liaison `:app` → `:transport-android` → `:protocol`.
 */
object TransportAndroid {
    const val ETAT: String = "M2 à venir : BLE + Bluetooth Classic"
}
