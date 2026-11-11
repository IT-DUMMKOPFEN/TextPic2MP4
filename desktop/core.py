#!/usr/bin/env python3
"""
Text2Video – Kernlogik
Text -> deutsche Sprachausgabe (edge-tts) -> MP4 720p mit Standbild (ffmpeg)

Auch ohne GUI nutzbar:
    python3 core.py --text-file text.txt --image bild.jpg --out video.mp4
"""
import asyncio
import os
import re
import shutil
import subprocess
import sys
import tempfile

APP_NAME = "Text2Video"
APP_VERSION = "0.1a"

# Deutsche Frauenstimmen (Microsoft Neural, über edge-tts)
VOICES = {
    "Seraphina (warm, weich) – DE": "de-DE-SeraphinaMultilingualNeural",
    "Katja (klar, sanft) – DE": "de-DE-KatjaNeural",
    "Amala (jung, freundlich) – DE": "de-DE-AmalaNeural",
    "Ingrid – Österreich": "de-AT-IngridNeural",
    "Leni – Schweiz": "de-CH-LeniNeural",
}
DEFAULT_VOICE = "Seraphina (warm, weich) – DE"

# Voreinstellung "verführerisch": etwas langsamer, etwas tiefer
PRESET_SEXY = {"rate": -12, "pitch": -6, "volume": 0}
PRESET_NORMAL = {"rate": 0, "pitch": 0, "volume": 0}

WIDTH, HEIGHT, FPS = 1280, 720, 25


# ----------------------------------------------------------------- ffmpeg
def find_ffmpeg():
    """System-ffmpeg bevorzugen, sonst das mitgelieferte aus imageio-ffmpeg."""
    exe = shutil.which("ffmpeg")
    if exe:
        return exe
    for p in ("/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/usr/bin/ffmpeg"):
        if os.path.exists(p):
            return p
    try:
        import imageio_ffmpeg
        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception:
        return None


def media_duration(ffmpeg, path):
    """Dauer in Sekunden aus der ffmpeg-Ausgabe lesen (kein ffprobe nötig)."""
    r = subprocess.run([ffmpeg, "-hide_banner", "-i", path],
                       capture_output=True, text=True)
    m = re.search(r"Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)", r.stderr)
    if not m:
        return None
    h, mi, s = m.groups()
    return int(h) * 3600 + int(mi) * 60 + float(s)


# -------------------------------------------------------------------- TTS
def _fmt(value, unit):
    return f"{'+' if value >= 0 else ''}{int(value)}{unit}"


async def _tts_async(text, voice_id, out_path, rate, pitch, volume):
    import edge_tts
    com = edge_tts.Communicate(
        text, voice_id,
        rate=_fmt(rate, "%"), pitch=_fmt(pitch, "Hz"), volume=_fmt(volume, "%"),
    )
    await com.save(out_path)


def text_to_speech(text, out_path, voice=DEFAULT_VOICE, rate=0, pitch=0, volume=0):
    """Erzeugt eine MP3-Datei. voice = Anzeigename aus VOICES oder Voice-ID."""
    text = (text or "").strip()
    if not text:
        raise ValueError("Kein Text vorhanden.")
    try:
        import edge_tts  # noqa: F401
    except ImportError:
        raise RuntimeError("Paket 'edge-tts' fehlt. Bitte install.sh ausführen.")
    voice_id = VOICES.get(voice, voice)
    try:
        asyncio.run(_tts_async(text, voice_id, out_path, rate, pitch, volume))
    except Exception as e:
        raise RuntimeError(
            f"Sprachausgabe fehlgeschlagen ({e}).\n"
            "Hinweis: Für die Stimmen wird eine Internetverbindung benötigt."
        )
    if not os.path.exists(out_path) or os.path.getsize(out_path) == 0:
        raise RuntimeError("Sprachausgabe hat keine Audiodaten geliefert.")
    return out_path


# ------------------------------------------------------------------ Video
def _prepare_image(image_path, tmpdir):
    """Bild drehen (EXIF), Transparenz entfernen, als PNG speichern."""
    try:
        from PIL import Image, ImageOps
        img = Image.open(image_path)
        img = ImageOps.exif_transpose(img)
        if img.mode in ("RGBA", "LA", "P"):
            img = img.convert("RGBA")
            bg = Image.new("RGB", img.size, (0, 0, 0))
            bg.paste(img, mask=img.split()[-1])
            img = bg
        else:
            img = img.convert("RGB")
        out = os.path.join(tmpdir, "bild.png")
        img.save(out)
        return out
    except ImportError:
        return image_path


def make_video(image_path, audio_path, out_path, background="blur",
               progress=None, ffmpeg=None):
    """
    Standbild + Audio -> MP4 (H.264/AAC, 1280x720, 25 fps).
    background: "blur" = unscharfer Bildhintergrund, "black" = schwarze Ränder,
                "fill" = Bild füllt 720p komplett (wird beschnitten)
    progress:   Funktion(float 0..1) oder None
    """
    ffmpeg = ffmpeg or find_ffmpeg()
    if not ffmpeg:
        raise RuntimeError("ffmpeg nicht gefunden. Bitte install.sh ausführen.")
    duration = media_duration(ffmpeg, audio_path)
    if not duration:
        raise RuntimeError("Konnte die Audiolänge nicht bestimmen.")

    W, H = WIDTH, HEIGHT
    if background == "black":
        vf = (f"[0:v]scale={W}:{H}:force_original_aspect_ratio=decrease,"
              f"pad={W}:{H}:(ow-iw)/2:(oh-ih)/2:black,setsar=1,format=yuv420p[v]")
    elif background == "fill":
        vf = (f"[0:v]scale={W}:{H}:force_original_aspect_ratio=increase,"
              f"crop={W}:{H},setsar=1,format=yuv420p[v]")
    else:  # blur
        vf = (f"[0:v]split=2[a][b];"
              f"[a]scale={W}:{H}:force_original_aspect_ratio=increase,"
              f"crop={W}:{H},boxblur=25:3,eq=brightness=-0.08[bg];"
              f"[b]scale={W}:{H}:force_original_aspect_ratio=decrease[fg];"
              f"[bg][fg]overlay=(W-w)/2:(H-h)/2,setsar=1,format=yuv420p[v]")

    with tempfile.TemporaryDirectory() as tmp:
        img = _prepare_image(image_path, tmp)
        cmd = [
            ffmpeg, "-y", "-hide_banner", "-loglevel", "error",
            "-loop", "1", "-framerate", str(FPS), "-i", img,
            "-i", audio_path,
            "-filter_complex", vf,
            "-map", "[v]", "-map", "1:a",
            "-c:v", "libx264", "-preset", "veryfast", "-tune", "stillimage",
            "-crf", "20", "-r", str(FPS),
            "-c:a", "aac", "-b:a", "192k", "-ar", "48000",
            "-t", f"{duration:.3f}", "-shortest",
            "-movflags", "+faststart",
            "-progress", "pipe:1", "-nostats",
            out_path,
        ]
        p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                             text=True)
        for line in p.stdout:
            if progress and line.startswith("out_time_us="):
                try:
                    us = int(line.split("=")[1])
                    progress(min(1.0, us / 1e6 / duration))
                except ValueError:
                    pass
        err = p.stderr.read()
        p.wait()
        if p.returncode != 0:
            raise RuntimeError(f"ffmpeg-Fehler:\n{err.strip()[-800:]}")
    if progress:
        progress(1.0)
    return out_path


def play_audio(path):
    """Audio abspielen (macOS: afplay, Linux: ffplay/mpv/xdg-open)."""
    if sys.platform == "darwin":
        return subprocess.Popen(["afplay", path])
    for player in (["ffplay", "-nodisp", "-autoexit", "-loglevel", "quiet"],
                   ["mpv", "--no-video", "--really-quiet"],
                   ["cvlc", "--play-and-exit"]):
        if shutil.which(player[0]):
            return subprocess.Popen(player + [path])
    return subprocess.Popen(["xdg-open", path])


# -------------------------------------------------------------------- CLI
def _cli():
    import argparse
    ap = argparse.ArgumentParser(description=f"{APP_NAME} {APP_VERSION} (Kommandozeile)")
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--text", help="Text direkt")
    src.add_argument("--text-file", help="Textdatei (UTF-8)")
    ap.add_argument("--image", required=True, help="Bilddatei")
    ap.add_argument("--out", required=True, help="Ziel-MP4")
    ap.add_argument("--voice", default=DEFAULT_VOICE,
                    help="Stimme (Name oder Voice-ID). Liste: --list-voices")
    ap.add_argument("--rate", type=int, default=PRESET_SEXY["rate"], help="Tempo in %%")
    ap.add_argument("--pitch", type=int, default=PRESET_SEXY["pitch"], help="Tonhöhe in Hz")
    ap.add_argument("--background", choices=["blur", "black", "fill"], default="blur")
    ap.add_argument("--keep-audio", action="store_true", help="MP3 zusätzlich speichern")
    ap.add_argument("--list-voices", action="store_true")
    if "--list-voices" in sys.argv:
        for k, v in VOICES.items():
            print(f"{k:35s} {v}")
        return
    a = ap.parse_args()
    text = a.text if a.text else open(a.text_file, encoding="utf-8").read()
    mp3 = os.path.splitext(a.out)[0] + ".mp3"
    print("Erzeuge Sprache …")
    text_to_speech(text, mp3, a.voice, a.rate, a.pitch)
    print("Erzeuge Video …")
    make_video(a.image, mp3, a.out, a.background,
               progress=lambda f: print(f"\r  {f*100:5.1f} %", end="", flush=True))
    print(f"\nFertig: {a.out}")
    if not a.keep_audio:
        os.remove(mp3)


if __name__ == "__main__":
    _cli()
