#!/usr/bin/env python3
"""
Holt die eingebauten Stimmen und JS-Bibliotheken für die Vorlesepult-App und legt sie in den App-Assets ab.

    python3 android/tools/fetch-voices.py --set komplett --out android/app/src/main/assets --web web --cache .cache

Ergebnis:
  assets/voices/<id>/model.onnx, tokens.txt [, model.onnx.json]
  assets/espeak-ng-data/               (Aussprachedaten für die Piper-Stimmen, de + en)
  assets/www/index.html            (Kopie aus web/)
  assets/www/voices.json           (Katalog für Oberfläche und Engine)
  assets/www/vendor/*.js           (pdf.js, mammoth, JSZip, jsPDF)
  assets/www/fonts/*.woff          (Inter, Lora – Kopie aus web/fonts/)
"""
import argparse, io, json, os, shutil, sys, tarfile, urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import onnxmeta  # noqa: E402  (Metadaten für rohe Piper-Modelle, ohne Fremdbibliothek)

SHERPA = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"
HF = "https://huggingface.co/rhasspy/piper-voices/resolve/main/"

EMOTIONS = {"neutral": "Neutral", "amused": "Amüsiert", "angry": "Wütend", "disgusted": "Angewidert",
            "drunk": "Betrunken", "sleepy": "Schläfrig", "surprised": "Überrascht", "whisper": "Flüstern"}

# Reihenfolge = Reihenfolge in der Stimmenliste der App
VOICES = [
    dict(id="thorsten", name="Thorsten", desc="männlich, klar und natürlich", group="Deutsch",
         pkg="vits-piper-de_DE-thorsten-medium", sets=("komplett", "kompakt"),
         license="CC0", source="https://github.com/thorstenMueller/Thorsten-Voice"),
    dict(id="thorsten_emotional", name="Thorsten Stimmungen", desc="männlich, acht Stimmungen", group="Deutsch",
         pkg="vits-piper-de_DE-thorsten_emotional-medium", sets=("komplett",), speakerNames=EMOTIONS, defaultSpeaker="neutral",
         license="CC0", source="https://github.com/thorstenMueller/Thorsten-Voice"),
    dict(id="kerstin", name="Kerstin", desc="weiblich, deutlich", group="Deutsch",
         pkg="vits-piper-de_DE-kerstin-low", sets=("komplett", "kompakt"),
         license="CC0", source="https://github.com/rhasspy/dataset-voice-kerstin"),
    dict(id="karlsson", name="Karlsson", desc="männlich, ruhig", group="Deutsch",
         pkg="vits-piper-de_DE-karlsson-low", sets=("komplett", "kompakt"),
         license="M-AILABS", source="https://www.caito.de/2019/01/03/the-m-ailabs-speech-dataset/"),
    dict(id="eva", name="Eva", desc="weiblich, leicht und schnell", group="Deutsch",
         pkg="vits-piper-de_DE-eva_k-x_low", sets=("komplett", "kompakt"),
         license="M-AILABS", source="https://www.caito.de/2019/01/03/the-m-ailabs-speech-dataset/"),
    dict(id="ramona", name="Ramona", desc="weiblich, Hörbuch", group="Deutsch",
         pkg="vits-piper-de_DE-ramona-low", sets=("komplett",),
         license="M-AILABS", source="https://www.caito.de/2019/01/03/the-m-ailabs-speech-dataset/"),
    dict(id="pavoque", name="Pavoque", desc="männlich, Hörbuch", group="Deutsch",
         pkg="vits-piper-de_DE-pavoque-low", sets=("komplett",),
         license="CC BY-NC-SA 4.0", source="https://github.com/marytts/pavoque-data"),
    dict(id="mls", name="MLS-Sprecher:innen", desc="236 Stimmen aus Hörbüchern", group="Deutsch",
         hf="de/de_DE/mls/medium/de_DE-mls-medium", sets=("komplett",), optional=True,
         license="CC BY 4.0", source="https://huggingface.co/rhasspy/piper-voices/tree/main/de/de_DE/mls"),
    dict(id="css10", name="CSS10 (Coqui)", desc="weiblich, Hörbuch", group="Deutsch",
         pkg="vits-coqui-de-css10", sets=("komplett",), frontend="chars", lang="de", sampleRate=22050,
         license="CSS10-Datensatz", source="https://github.com/Kyubyong/css10"),
]

LIBS = {
    "pdf.min.js": "https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.min.js",
    "pdf.worker.min.js": "https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.worker.min.js",
    "mammoth.browser.min.js": "https://cdnjs.cloudflare.com/ajax/libs/mammoth/1.6.0/mammoth.browser.min.js",
    "jszip.min.js": "https://cdnjs.cloudflare.com/ajax/libs/jszip/3.10.1/jszip.min.js",
    "jspdf.umd.min.js": "https://cdnjs.cloudflare.com/ajax/libs/jspdf/2.5.1/jspdf.umd.min.js",
}


def log(*a):
    print(*a, flush=True)


def download(url, dest, retries=3):
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return dest
    tmp = dest + ".part"
    last = None
    for i in range(retries):
        try:
            log(f"  lade {url}")
            req = urllib.request.Request(url, headers={"User-Agent": "vorlesepult-build"})
            with urllib.request.urlopen(req, timeout=120) as r, open(tmp, "wb") as f:
                shutil.copyfileobj(r, f, 1024 * 1024)
            os.replace(tmp, dest)
            return dest
        except Exception as e:  # noqa
            last = e
            log(f"  Versuch {i + 1} fehlgeschlagen: {e}")
    raise RuntimeError(f"Download fehlgeschlagen: {url} ({last})")


ESPEAK_KEEP_DICTS = {"de_dict", "en_dict"}


def extract_espeak(tar_path, outdir):
    """Holt die Aussprachedaten (espeak-ng-data) aus einem Piper-Paket – nur deutsche und englische Wörterbücher."""
    n = 0
    with tarfile.open(tar_path, "r:bz2") as t:
        for m in t.getmembers():
            if not m.isfile() or "/espeak-ng-data/" not in m.name:
                continue
            rel = m.name.split("/espeak-ng-data/", 1)[1]
            base = os.path.basename(rel)
            if base.endswith("_dict") and base not in ESPEAK_KEEP_DICTS:
                continue
            dest = os.path.join(outdir, rel)
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with t.extractfile(m) as src, open(dest, "wb") as dst:
                shutil.copyfileobj(src, dst, 1024 * 1024)
            n += 1
    if not os.path.exists(os.path.join(outdir, "phontab")):
        raise RuntimeError(f"espeak-ng-data fehlt in {tar_path}")
    return n


def extract_pkg(tar_path, outdir):
    """Holt model.onnx, tokens.txt und die Modellbeschreibung aus einem sherpa-onnx-Paket."""
    os.makedirs(outdir, exist_ok=True)
    found = {}
    with tarfile.open(tar_path, "r:bz2") as t:
        for m in t.getmembers():
            if not m.isfile():
                continue
            base = os.path.basename(m.name)
            if "espeak-ng-data" in m.name:
                continue
            key = None
            if base.endswith(".onnx"):
                key = "model.onnx"
            elif base == "tokens.txt":
                key = "tokens.txt"
            elif base.endswith(".onnx.json"):
                key = "model.onnx.json"
            elif base == "MODEL_CARD":
                key = "MODEL_CARD"
            elif base == "config.json":
                key = "config.json"
            if key and key not in found:
                dest = os.path.join(outdir, key)
                with t.extractfile(m) as src, open(dest, "wb") as dst:
                    shutil.copyfileobj(src, dst, 1024 * 1024)
                found[key] = dest
    if "model.onnx" not in found or "tokens.txt" not in found:
        raise RuntimeError(f"Paket unvollständig: {tar_path}")
    return found


def meta_from_json(path):
    try:
        j = json.load(open(path, encoding="utf-8"))
    except Exception:
        return {}
    out = {}
    if "audio" in j and "sample_rate" in j["audio"]:
        out["sampleRate"] = int(j["audio"]["sample_rate"])
    if "num_speakers" in j:
        out["numSpeakers"] = int(j["num_speakers"])
    if "espeak" in j and "voice" in j["espeak"]:
        out["lang"] = j["espeak"]["voice"]
    if j.get("speaker_id_map"):
        out["speakerIdMap"] = j["speaker_id_map"]
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--set", default="komplett", choices=["komplett", "kompakt"])
    ap.add_argument("--out", required=True, help="assets-Verzeichnis der App")
    ap.add_argument("--web", default="web", help="Ordner mit index.html der Oberfläche")
    ap.add_argument("--cache", default=".cache", help="Ablage für Downloads")
    ap.add_argument("--no-libs", action="store_true", help="JS-Bibliotheken nicht laden")
    ap.add_argument("--only", default="", help="nur diese Stimmen-IDs (Komma), zum Testen")
    a = ap.parse_args()

    os.makedirs(a.cache, exist_ok=True)
    voices_dir = os.path.join(a.out, "voices")
    www = os.path.join(a.out, "www")
    vendor = os.path.join(www, "vendor")
    shutil.rmtree(voices_dir, ignore_errors=True)
    os.makedirs(voices_dir, exist_ok=True)
    os.makedirs(vendor, exist_ok=True)

    only = set(x for x in a.only.split(",") if x)
    espeak_dir = os.path.join(a.out, "espeak-ng-data")
    shutil.rmtree(espeak_dir, ignore_errors=True)
    catalog = []
    total = 0
    for v in VOICES:
        if a.set not in v["sets"] or (only and v["id"] not in only):
            continue
        log(f"== {v['id']} ({v['name']})")
        outdir = os.path.join(voices_dir, v["id"])
        try:
            if "pkg" in v:
                tar_path = download(SHERPA + v["pkg"] + ".tar.bz2", os.path.join(a.cache, v["pkg"] + ".tar.bz2"))
                files = extract_pkg(tar_path, outdir)
                if v.get("frontend", "piper") == "piper" and not os.path.exists(os.path.join(espeak_dir, "phontab")):
                    log(f"  Aussprachedaten (espeak-ng-data): {extract_espeak(tar_path, espeak_dir)} Dateien")
            else:
                os.makedirs(outdir, exist_ok=True)
                base = v["hf"]
                name = base.rsplit("/", 1)[1]
                files = {
                    "model.onnx": shutil.copy(download(HF + base + ".onnx", os.path.join(a.cache, name + ".onnx")), os.path.join(outdir, "model.onnx")),
                    "model.onnx.json": shutil.copy(download(HF + base + ".onnx.json", os.path.join(a.cache, name + ".onnx.json")), os.path.join(outdir, "model.onnx.json")),
                }
                # tokens.txt aus der Modellbeschreibung ableiten (phoneme_id_map), wie sherpa-onnx/scripts/piper
                j = json.load(open(files["model.onnx.json"], encoding="utf-8"))
                with open(os.path.join(outdir, "tokens.txt"), "w", encoding="utf-8") as f:
                    for ph, ids in j["phoneme_id_map"].items():
                        if ph == "\n":
                            continue
                        f.write(f"{ph} {ids[0] if isinstance(ids, list) else ids}\n")
                files["tokens.txt"] = os.path.join(outdir, "tokens.txt")
                # Rohe Piper-Modelle von Hugging Face tragen keine sherpa-Metadaten – ohne sie bricht die Engine ab
                onnxmeta.set_metadata(files["model.onnx"], onnxmeta.piper_metadata(j))
                log(f"  Metadaten gesetzt: {onnxmeta.read_metadata(files['model.onnx'])}")
        except Exception as e:
            if v.get("optional"):
                log(f"  übersprungen: {e}")
                shutil.rmtree(outdir, ignore_errors=True)
                continue
            raise

        present = onnxmeta.read_metadata(files["model.onnx"])
        if "sample_rate" not in present or "comment" not in present:
            if "model.onnx.json" in files and v.get("frontend", "piper") == "piper":
                j = json.load(open(files["model.onnx.json"], encoding="utf-8"))
                onnxmeta.set_metadata(files["model.onnx"], onnxmeta.piper_metadata(j))
                log(f"  Metadaten ergänzt: {onnxmeta.read_metadata(files['model.onnx'])}")
            else:
                raise RuntimeError(f"Modell {v['id']} ohne sherpa-Metadaten (sample_rate/comment)")
        meta = meta_from_json(files["model.onnx.json"]) if "model.onnx.json" in files else {}
        entry = dict(
            id=v["id"], name=v["name"], desc=v["desc"], group=v["group"],
            frontend=v.get("frontend", "piper"),
            lang=v.get("lang") or meta.get("lang") or "de",
            numSpeakers=meta.get("numSpeakers", 1),
            sampleRate=v.get("sampleRate") or meta.get("sampleRate") or 22050,
            dir="voices/" + v["id"],
            license=v.get("license", ""), source=v.get("source", ""),
        )
        if v.get("speakerNames") and meta.get("speakerIdMap"):
            names = v["speakerNames"]
            entry["speakers"] = [dict(sid=sid, name=names.get(key, key)) for key, sid in sorted(meta["speakerIdMap"].items(), key=lambda kv: kv[1])]
            entry["defaultSid"] = meta["speakerIdMap"].get(v.get("defaultSpeaker", ""), 0)
        size = os.path.getsize(files["model.onnx"])
        entry["bytes"] = size
        total += size
        # Modellkarte und Coqui-Konfiguration werden nicht gebraucht
        for extra in ("MODEL_CARD", "config.json"):
            p = os.path.join(outdir, extra)
            if os.path.exists(p):
                os.remove(p)
        catalog.append(entry)
        log(f"  ok: {size / 1e6:.1f} MB, {entry['numSpeakers']} Sprecher, {entry['sampleRate']} Hz, {entry['lang']}")

    if not catalog:
        sys.exit("Keine Stimme eingebaut – Abbruch.")
    if any(v["frontend"] == "piper" for v in catalog) and not os.path.exists(os.path.join(espeak_dir, "phontab")):
        sys.exit("Aussprachedaten (espeak-ng-data) fehlen – Abbruch.")
    with open(os.path.join(www, "voices.json"), "w", encoding="utf-8") as f:
        json.dump(catalog, f, ensure_ascii=False, indent=1)

    if not a.no_libs:
        for name, url in LIBS.items():
            shutil.copy(download(url, os.path.join(a.cache, name)), os.path.join(vendor, name))

    src = os.path.join(a.web, "index.html")
    shutil.copy(src, os.path.join(www, "index.html"))
    fonts = os.path.join(a.web, "fonts")
    if os.path.isdir(fonts):
        shutil.copytree(fonts, os.path.join(www, "fonts"), dirs_exist_ok=True)
    log(f"Fertig: {len(catalog)} Stimmen, {total / 1e6:.0f} MB Modelle, Satz „{a.set}“")


if __name__ == "__main__":
    main()
