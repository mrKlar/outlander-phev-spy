#!/usr/bin/env python3
"""Diff octet par octet des lectures 0x22 entre états consignés.

Usage : python3 scripts/diff-journaux.py JOURNAL.jsonl [JOURNAL2.jsonl ...]

Les journaux JSONL v1 (spec §9) sont lus dans l'ordre donné.
Chaque événement note « étape: ... » (phase epb-observation,
consignerEtapeEpb) ouvre un nouveau segment d'état. Pour chaque
(en-tête répondant, DID, offset dans la charge après 62 <DID>)
dont la valeur diffère entre segments, une ligne est imprimée
avec les valeurs par segment. Une ligne dont la valeur varie
déjà entre lectures d'un même segment est marquée « instable »
et triée après les lignes stables : un candidat d'état EPB doit
être stable à état constant.

Extraction des charges :
- lectures physiques (kind uds) : response.reassembled, posé
  par l'enrich de UdsClient, en-tête pris sur la première trame ;
- lectures fonctionnelles (reassembled null, ex. balayage) :
  chaque trame de response.frames est attribuée à son en-tête,
  la charge est ce qui suit 62 <DID> dans la trame mono-trame.
Les réponses négatives (7F) et les silences sont ignorés :
NO DATA ne prouve rien, ici comme dans l'app.

Hors ligne et lecture seule : ce script ne parle à aucun
véhicule. Les journaux réels contiennent le VIN — ne jamais
committer ni partager leur contenu brut.
"""

import json
import sys
from collections import OrderedDict


def octets_hex(texte):
    propre = "".join(texte.split())
    if len(propre) % 2 or not all(c in "0123456789abcdefABCDEF" for c in propre):
        return None
    return bytes.fromhex(propre)


def entete_et_reste(frame):
    """(en-tête normalisé, octets après l'en-tête) ou (None, None).

    Formes vues au journal : « 18 DA F1 DB 07 62 ... » (29 bits
    espacé), « 18DAF1DB ... », « 763 03 7F ... » (11 bits).
    """
    morceaux = frame.strip().split()
    if not morceaux:
        return None, None
    # 29 bits espacé : 4 premiers morceaux d'un octet chacun.
    if len(morceaux) >= 5 and all(len(m) == 2 for m in morceaux[:4]):
        brut = octets_hex("".join(morceaux))
        if brut and len(brut) > 4:
            return brut[:4].hex().upper(), brut[4:]
    brut = octets_hex(morceaux[0])
    if brut is None:
        return None, None
    reste = octets_hex("".join(morceaux[1:])) or b""
    if len(morceaux[0]) == 8:  # 18DAF1DB
        return morceaux[0].upper(), reste
    if len(morceaux[0]) == 3:  # 763
        return morceaux[0].upper(), reste
    return None, None


def charges_de(evenement):
    """Liste (en-tête, charge après 62 <DID>) d'une réponse 0x22 positive."""
    requete = evenement.get("request") or {}
    reponse = evenement.get("response") or {}
    if reponse.get("status") != "positive":
        return []
    did = (requete.get("did") or "").upper()
    octets_requete = (requete.get("bytes") or "").replace(" ", "").upper()
    if len(did) != 4 or not octets_requete.startswith("22"):
        return []
    prefixe = bytes.fromhex("62" + did)
    reassembled = reponse.get("reassembled")
    if reassembled:
        brut = octets_hex(reassembled)
        if brut and brut.startswith(prefixe):
            frames = reponse.get("frames") or []
            entete = entete_et_reste(frames[0])[0] if frames else "?"
            return [(entete or "?", brut[len(prefixe):])]
        return []
    resultats = []
    for frame in reponse.get("frames") or []:
        entete, reste = entete_et_reste(frame)
        if entete is None or reste is None:
            continue
        # Trame mono-trame ELM : [longueur] 62 <DID> charge...
        # ou PCI ISO-TP 0x0n suivi de la charge : on cherche le
        # préfixe 62 <DID>, ce qui couvre les deux présentations.
        pos = reste.find(prefixe)
        if pos >= 0:
            resultats.append((entete, reste[pos + len(prefixe):]))
    return resultats


def main(argv):
    if len(argv) < 2:
        print("usage: diff-journaux.py JOURNAL.jsonl [...]", file=sys.stderr)
        return 2
    segments = []  # libellés dans l'ordre d'apparition
    segment_courant = "(avant première étape)"
    compteurs = {}

    def nom_segment(note):
        base = note.split(";", 1)[0].strip()
        compteurs[base] = compteurs.get(base, 0) + 1
        if compteurs[base] > 1:
            return f"{base} #{compteurs[base]}"
        return base

    # (en-tête, DID, offset) -> {segment: set(valeurs)}
    valeurs = OrderedDict()
    for chemin in argv[1:]:
        with open(chemin, encoding="utf-8") as f:
            for ligne in f:
                ligne = ligne.strip()
                if not ligne:
                    continue
                e = json.loads(ligne)
                if e.get("kind") == "note" and (e.get("note") or "").startswith("étape:"):
                    segment_courant = nom_segment(e["note"])
                    if segment_courant not in segments:
                        segments.append(segment_courant)
                    continue
                if segment_courant not in segments:
                    segments.append(segment_courant)
                did = ((e.get("request") or {}).get("did") or "").upper()
                for entete, charge in charges_de(e):
                    for offset, valeur in enumerate(charge):
                        cle = (entete, did, offset)
                        valeurs.setdefault(cle, {}).setdefault(
                            segment_courant, set()).add(valeur)

    stables, instables = [], []
    for (entete, did, offset), par_segment in valeurs.items():
        distinctes = set().union(*par_segment.values()) if par_segment else set()
        if len(distinctes) < 2:
            continue
        instable = any(len(v) > 1 for v in par_segment.values())
        cellules = []
        for seg in segments:
            v = par_segment.get(seg)
            if not v:
                cellules.append("--")
            elif len(v) == 1:
                cellules.append(f"{next(iter(v)):02X}")
            else:
                cellules.append("/".join(f"{x:02X}" for x in sorted(v)))
        ligne = (entete, did, offset, cellules)
        (instables if instable else stables).append(ligne)

    print("Segments : " + " | ".join(
        f"[{i}] {s}" for i, s in enumerate(segments)))
    print("en-tête\tDID\toffset\t" + "\t".join(f"[{i}]" for i in range(len(segments))))
    for entete, did, offset, cellules in stables:
        print(f"{entete}\t{did}\t{offset}\t" + "\t".join(cellules))
    for entete, did, offset, cellules in instables:
        print(f"{entete}\t{did}\t{offset}\t" + "\t".join(cellules) + "\tinstable")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
