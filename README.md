# Vorlesepult

Android-App, die Texte, PDFs und Word-Dateien vorliest – mit natürlichen Stimmen, die komplett in der App stecken. Kein Internet, kein Konto, keine Abhängigkeit vom Browser.

- **Öffnen:** PDF, Word (.docx), Text, Markdown, HTML – über den Button, per „Teilen“ aus anderen Apps oder „Öffnen mit“ aus dem Dateimanager.
- **Vorlesen:** Satz für Satz, der aktuelle Satz ist markiert. Tipp in den Text, und es geht ab dort weiter. Während des Vorlesens lässt sich weiter schreiben.
- **Bearbeiten:** Überschriften, fett, kursiv, Listen, Zitate.
- **Exportieren:** Word, PDF, Markdown, HTML, Text – oder als Text teilen.

## Stimmen

Alle Stimmen sind Piper-Stimmen (VITS), ausgeführt mit [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (mit eingebautem espeak-ng für die Aussprache). Welche Stimmen eingebaut werden, steht in `android/tools/fetch-voices.py`.

| APK | Stimmen |
|---|---|
| **komplett** | Deutsch: Thorsten, Thorsten Stimmungen (8 Varianten), Kerstin, Karlsson, Eva, Ramona, Pavoque, MLS (236 Sprecher:innen), CSS10 · Englisch: Lessac, Alan, LibriTTS (904 Sprecher:innen) |
| **kompakt** | Thorsten, Kerstin, Karlsson, Eva |

## Installation

1. Unter **Releases** die gewünschte APK auf das Handy laden (`Vorlesepult-komplett-….apk` oder `Vorlesepult-kompakt-….apk`).
2. Die Datei öffnen und die Installation aus dieser Quelle erlauben.
3. Android 10 oder neuer, 64-Bit (ARM64).

Die komplette Fassung ist rund 800 MB groß, die kompakte rund 240 MB.

## Bauen

Der Workflow `.github/workflows/build-android.yml` baut beide Fassungen bei jedem Push auf `main` (oder von Hand über „Actions“) und hängt sie an ein Release `v<Version>` (Version aus `android/version.txt`).

- Die Sprach-Engine kommt als AAR aus dem GitHub-Release von sherpa-onnx.
- Die Stimmen lädt `fetch-voices.py` aus den sherpa-onnx-Releases (MLS von Hugging Face) und legt sie unter `android/app/src/main/assets/voices/` ab.
- Signiert wird mit dem Schlüssel aus den Repository-Secrets `ANDROID_KEYSTORE_B64` (Keystore als Base64), `ANDROID_KEYSTORE_PASSWORD` und optional `ANDROID_KEY_ALIAS` (Standard: `vorlesepult`). Ohne diese Secrets entsteht ein Testbuild mit Debug-Signatur. Updates lassen sich nur über eine bestehende Installation legen, wenn derselbe Schlüssel benutzt wird – sonst einmal deinstallieren.

## Aufbau

- `web/index.html` – die komplette Oberfläche (Editor, Import, Export, Vorlese-Steuerung).
- `android/app/src/main/java/de/honeybalecta/vorlesepult/`
  - `MainActivity.kt` – WebView, Dateidialoge, „Öffnen mit“/„Teilen“, Tastatur.
  - `Bridge.kt` – Schnittstelle zur Oberfläche (`VP.*` ↔ `VPApp.*`).
  - `Engine.kt` – Stimme laden, Audio erzeugen, Zwischenspeicher, Aussprachedaten bereitstellen.
  - `Player.kt` – Wiedergabe über AudioTrack, Hörfokus.
  - `LocalServer.kt` – liefert die Oberfläche und Importdateien an den WebView.
  - `Store.kt` – Dokument und Einstellungen im App-Speicher.
