# Text2Video 0.1a

Text einfügen → deutsche Frauenstimme → zusammen mit einem Bild als **MP4-Video in 720p** speichern.
Für **macOS (Apple Silicon)** und **Linux**.

## Installation

1. Ordner entpacken, Terminal darin öffnen
2. `bash install.sh`
3. Starten: `./start.sh` (macOS auch per Doppelklick auf `start.command` – beim ersten Mal Rechtsklick → *Öffnen*)

Voraussetzung: Python 3.9+ mit tkinter
- macOS: `brew install python python-tk` (oder Installer von python.org)
- Debian/Ubuntu: `sudo apt install python3 python3-venv python3-tk`

ffmpeg muss nicht installiert sein – es wird über `imageio-ffmpeg` mitgeliefert. Ist ein System-ffmpeg vorhanden, wird das benutzt.

## Bedienung

1. **Text** einfügen (Button *Einfügen* oder Cmd/Strg+V) oder Textdatei laden
2. **Stimme** wählen – Standard ist *Seraphina* mit Voreinstellung **„Verführerisch“** (etwas langsamer, etwas tiefer). Tempo und Tonhöhe per Regler anpassen, mit **▶ Probehören** testen (erste 300 Zeichen)
3. **Bild** wählen und Rand-Modus festlegen:
   - *Unscharf* – Bild mittig, Ränder mit unscharfer Kopie gefüllt (gut für Hochformat)
   - *Schwarz* – schwarze Balken
   - *Füllen* – Bild füllt 16:9 komplett (wird beschnitten)
4. **Video erstellen** → Speicherort wählen. Optional MP3 zusätzlich speichern.

Ausgabe: H.264 / AAC 192 kbit/s, 1280×720, 25 fps. Videolänge = Länge der Sprache.

## Stimmen

| Name | Voice-ID |
|---|---|
| Seraphina (warm, weich) | de-DE-SeraphinaMultilingualNeural |
| Katja (klar, sanft) | de-DE-KatjaNeural |
| Amala (jung, freundlich) | de-DE-AmalaNeural |
| Ingrid (Österreich) | de-AT-IngridNeural |
| Leni (Schweiz) | de-CH-LeniNeural |

Die Stimmen kommen von Microsofts Neural-Sprachdienst über das Paket `edge-tts` – **dafür ist eine Internetverbindung nötig** (kostenlos, kein Konto).

## Kommandozeile

```bash
.venv/bin/python core.py --text-file text.txt --image bild.jpg --out video.mp4
.venv/bin/python core.py --text "Hallo, schön dass du da bist." --image bild.jpg --out hallo.mp4 --rate -15 --pitch -8 --background black
.venv/bin/python core.py --list-voices
```

## Dateien

- `app.py` – Oberfläche (tkinter)
- `core.py` – Sprache + Video, auch als Kommandozeilen-Tool
- `install.sh`, `start.sh`, `start.command` – Installation / Start
