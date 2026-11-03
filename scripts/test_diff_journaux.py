#!/usr/bin/env python3
"""Tests de scripts/diff-journaux.py — journaux synthétiques, aucun VIN.

Le format des événements suit le schéma JSONL v1 réel (Journal.kt,
renderJournalLine) : request {tx_id, bytes, service, did},
response {raw_text, frames, reassembled, status, nrc, elm_tokens}.
"""

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("diff-journaux.py")


def evenement(seq, phase="epb-observation", kind="uds", request=None,
              response=None, note=None):
    e = {
        "schema": 1,
        "session_id": "test",
        "seq": seq,
        "ts_wall": "2026-11-01T00:00:00-04:00",
        "ts_mono_ms": seq,
        "phase": phase,
        "kind": kind,
        "adapter": {"ati": None, "stn": None, "transport": "classic"},
        "elm": {"protocol": "7", "sh": "DADBF1", "cra": None, "fcsh": None,
                "fcsd": None, "caf": "1", "st_ms": "400"},
        "vehicle": {"state": None, "atrv_v": None},
        "request": request,
        "response": response,
        "duration_ms": 1,
        "retry_of": None,
        "tx_chunks": [],
        "rx_chunks": [],
    }
    if note is not None:
        e["note"] = note
    return e


def lecture22(seq, did, charge_hex, entete="18 DA F1 DB"):
    """Réponse positive physique : frames + reassembled posés par l'enrich."""
    reassembled = "62" + did + charge_hex
    octets = [reassembled[i:i + 2] for i in range(0, len(reassembled), 2)]
    frame = f"{entete} {len(octets):02X} " + " ".join(octets)
    return evenement(
        seq,
        request={"tx_id": None, "bytes": "22" + did, "service": "22", "did": did},
        response={"raw_text": frame, "frames": [frame],
                  "reassembled": reassembled, "status": "positive",
                  "nrc": None, "elm_tokens": []},
    )


def etape(seq, nom):
    return evenement(seq, kind="note", note=f"étape: {nom}; observation: ")


def journal(evenements):
    f = tempfile.NamedTemporaryFile(
        "w", suffix=".jsonl", delete=False, encoding="utf-8")
    for e in evenements:
        f.write(json.dumps(e, ensure_ascii=False) + "\n")
    f.close()
    return f.name


def lancer(*chemins):
    return subprocess.run(
        [sys.executable, str(SCRIPT), *chemins],
        capture_output=True, text=True, check=True).stdout


class DiffJournauxTest(unittest.TestCase):

    def test_octet_qui_change_entre_etats_stable_en_tete(self):
        chemin = journal([
            etape(0, "S0 serre"),
            lecture22(1, "0100", "A100"),
            lecture22(2, "0100", "A100"),
            etape(3, "S1 desserre"),
            lecture22(4, "0100", "A200"),
        ])
        sortie = lancer(chemin)
        self.assertIn("18DAF1DB", sortie)
        self.assertIn("0100", sortie)
        self.assertIn("A1", sortie)
        self.assertIn("A2", sortie)

    def test_octet_instable_dans_un_etat_marque_instable(self):
        chemin = journal([
            etape(0, "S0 serre"),
            lecture22(1, "0100", "A100"),
            lecture22(2, "0100", "A300"),  # bouge déjà dans S0 : bruit
            etape(3, "S1 desserre"),
            lecture22(4, "0100", "A200"),
        ])
        sortie = lancer(chemin)
        self.assertIn("instable", sortie)

    def test_octet_constant_absent_de_la_sortie(self):
        chemin = journal([
            etape(0, "S0 serre"),
            lecture22(1, "0100", "A1BB"),
            etape(2, "S1 desserre"),
            lecture22(3, "0100", "A1BB"),
        ])
        sortie = lancer(chemin)
        self.assertNotIn("BB", sortie)

    def test_reponses_fonctionnelles_multi_ecu_attribuees_par_entete(self):
        # Balayage fonctionnel réel : reassembled null, plusieurs
        # répondants dans frames (séance du 2026-10-31, seq 22).
        ev = evenement(
            1, kind="obd", phase="balayage",
            request={"tx_id": None, "bytes": "220100", "service": "22",
                     "did": "0100"},
            response={"raw_text": "", "frames": [
                "18 DA F1 DB 05 62 01 00 AA 11",
                "18 DA F1 DF 04 62 01 00 04",
                "18 DA F1 DC 03 7F 22 31",
            ], "reassembled": None, "status": "positive",
                "nrc": None, "elm_tokens": []},
        )
        ev2 = evenement(
            3, kind="obd", phase="balayage",
            request={"tx_id": None, "bytes": "220100", "service": "22",
                     "did": "0100"},
            response={"raw_text": "", "frames": [
                "18 DA F1 DB 05 62 01 00 AA 11",
                "18 DA F1 DF 04 62 01 00 05",
            ], "reassembled": None, "status": "positive",
                "nrc": None, "elm_tokens": []},
        )
        chemin = journal([etape(0, "S0 serre"), ev,
                          etape(2, "S1 desserre"), ev2])
        sortie = lancer(chemin)
        # Seul DF change (04 -> 05) ; DB constant, DC en NRC ignoré.
        self.assertIn("18DAF1DF", sortie)
        self.assertNotIn("18DAF1DB\t0100", sortie.replace("  ", "\t"))


if __name__ == "__main__":
    unittest.main()
