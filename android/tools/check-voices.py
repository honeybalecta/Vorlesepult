#!/usr/bin/env python3
"""
Prüft vor dem APK-Bau jede eingebaute Stimme mit der Linux-Fassung von sherpa-onnx:
ein kurzer Satz wird erzeugt; scheitert eine Stimme (z. B. fehlende Metadaten), scheitert der Build.

    python3 android/tools/check-voices.py --assets android/app/src/main/assets --sherpa <entpackter Ordner>
"""
import argparse, json, os, subprocess, sys, tempfile, wave

SENTENCE = "Guten Tag, das ist ein kurzer Test der eingebauten Stimme."


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--assets", required=True)
    ap.add_argument("--sherpa", required=True, help="Ordner mit bin/ und lib/ der sherpa-onnx-Linux-Fassung")
    a = ap.parse_args()
    exe = os.path.join(a.sherpa, "bin", "sherpa-onnx-offline-tts")
    env = dict(os.environ, LD_LIBRARY_PATH=os.path.join(a.sherpa, "lib"))
    voices = json.load(open(os.path.join(a.assets, "www", "voices.json"), encoding="utf-8"))
    espeak = os.path.join(a.assets, "espeak-ng-data")
    failed = []
    with tempfile.TemporaryDirectory() as tmp:
        for v in voices:
            out = os.path.join(tmp, v["id"] + ".wav")
            cmd = [exe, f"--vits-model={os.path.join(a.assets, v['dir'], 'model.onnx')}",
                   f"--vits-tokens={os.path.join(a.assets, v['dir'], 'tokens.txt')}",
                   f"--sid={v.get('defaultSid', 0)}", "--num-threads=2", f"--output-filename={out}", SENTENCE]
            if v.get("frontend", "piper") == "piper":
                cmd.insert(3, f"--vits-data-dir={espeak}")
            r = subprocess.run(cmd, env=env, capture_output=True, text=True, timeout=600)
            secs = 0.0
            if r.returncode == 0 and os.path.exists(out):
                try:
                    with wave.open(out) as w:
                        secs = w.getnframes() / w.getframerate()
                except Exception:
                    secs = 0.0
            if r.returncode != 0 or secs < 0.5:
                tail = "\n".join((r.stdout + r.stderr).strip().splitlines()[-8:])
                failed.append(v["id"])
                print(f"FEHLER {v['id']}: Rückgabe {r.returncode}, {secs:.2f} s Audio\n{tail}", flush=True)
            else:
                print(f"ok     {v['id']}: {secs:.2f} s Audio", flush=True)
    if failed:
        sys.exit("Stimmen fehlerhaft: " + ", ".join(failed))
    print(f"Alle {len(voices)} Stimmen sprechen.")


if __name__ == "__main__":
    main()
