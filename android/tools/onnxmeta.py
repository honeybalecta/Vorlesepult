"""Metadaten (metadata_props) eines ONNX-Modells ohne Fremdbibliothek ersetzen.

ONNX-Modelle sind Protobuf-Nachrichten (ModelProto). Feld 14 ist `metadata_props`
(wiederholt, StringStringEntryProto mit key=1, value=2). Wir lesen die obersten Felder,
lassen Feld 14 weg und hängen unsere Einträge an – alles andere wird unverändert kopiert.
"""
import os


def _varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def _read_varint(buf, pos):
    shift = 0
    result = 0
    while True:
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, pos
        shift += 7


def _entry(key, value):
    k = key.encode("utf-8")
    v = str(value).encode("utf-8")
    body = b"\x0a" + _varint(len(k)) + k + b"\x12" + _varint(len(v)) + v
    return b"\x72" + _varint(len(body)) + body  # Feld 14, length-delimited


def read_metadata(path):
    """Liest die vorhandenen metadata_props (nur zur Kontrolle)."""
    buf = open(path, "rb").read()
    pos = 0
    out = {}
    while pos < len(buf):
        tag, pos = _read_varint(buf, pos)
        field, wire = tag >> 3, tag & 7
        if wire == 0:
            _, pos = _read_varint(buf, pos)
        elif wire == 2:
            ln, pos = _read_varint(buf, pos)
            if field == 14:
                e = buf[pos:pos + ln]
                p = 0
                k = v = ""
                while p < len(e):
                    t, p = _read_varint(e, p)
                    l2, p = _read_varint(e, p)
                    s = e[p:p + l2].decode("utf-8", "replace")
                    p += l2
                    if t >> 3 == 1:
                        k = s
                    elif t >> 3 == 2:
                        v = s
                out[k] = v
            pos += ln
        elif wire == 1:
            pos += 8
        elif wire == 5:
            pos += 4
        else:
            raise ValueError(f"unerwarteter Wire-Typ {wire}")
    return out


def set_metadata(path, meta):
    """Ersetzt alle metadata_props des Modells durch `meta` (in-place, über eine temporäre Datei)."""
    buf = open(path, "rb").read()
    tmp = path + ".tmp"
    with open(tmp, "wb") as out:
        pos = 0
        while pos < len(buf):
            start = pos
            tag, pos = _read_varint(buf, pos)
            field, wire = tag >> 3, tag & 7
            if wire == 0:
                _, pos = _read_varint(buf, pos)
            elif wire == 2:
                ln, pos = _read_varint(buf, pos)
                pos += ln
            elif wire == 1:
                pos += 8
            elif wire == 5:
                pos += 4
            else:
                raise ValueError(f"unerwarteter Wire-Typ {wire}")
            if field != 14:
                out.write(buf[start:pos])
        for k, v in meta.items():
            out.write(_entry(k, v))
    os.replace(tmp, path)


def piper_metadata(config):
    """Metadaten für ein Piper-Modell aus seiner .onnx.json – wie sherpa-onnx/scripts/piper/add_meta_data.py."""
    sr = int(config["audio"]["sample_rate"])
    if sr == 22500:
        sr = 22050
    voice = config.get("lang_code") or config["espeak"]["voice"]
    lang = config.get("language", {})
    name = lang.get("name_english") or lang.get("code", "") or "Unknown"
    return {
        "model_type": "vits",
        "comment": "piper",
        "language": name,
        "voice": voice,
        "version": 1,
        "has_espeak": 1,
        "has_g2pw": 0,
        "n_speakers": int(config["num_speakers"]),
        "sample_rate": sr,
    }
