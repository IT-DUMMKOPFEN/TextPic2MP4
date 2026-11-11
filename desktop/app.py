#!/usr/bin/env python3
"""Text2Video – GUI (tkinter). Text -> Sprache -> MP4 720p mit Bild."""
import os
import queue
import tempfile
import threading
import tkinter as tk
from tkinter import ttk, filedialog, messagebox

import core

try:
    from PIL import Image, ImageTk, ImageOps
except ImportError:
    Image = None

IMG_TYPES = [("Bilder", "*.jpg *.jpeg *.png *.webp *.bmp *.gif *.tif *.tiff"),
             ("Alle Dateien", "*.*")]


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title(f"{core.APP_NAME} {core.APP_VERSION}")
        self.geometry("900x640")
        self.minsize(780, 560)

        self.image_path = None
        self.thumb = None
        self.q = queue.Queue()
        self.busy = False
        self.tmpdir = tempfile.mkdtemp(prefix="text2video_")
        self.player = None

        self.voice = tk.StringVar(value=core.DEFAULT_VOICE)
        self.rate = tk.IntVar(value=core.PRESET_SEXY["rate"])
        self.pitch = tk.IntVar(value=core.PRESET_SEXY["pitch"])
        self.bg = tk.StringVar(value="blur")
        self.keep_mp3 = tk.BooleanVar(value=False)
        self.status = tk.StringVar(value="Bereit. Text einfügen, Bild wählen, Video erstellen.")

        self._build()
        self.after(100, self._poll)

    # ------------------------------------------------------------ Layout
    def _build(self):
        pad = {"padx": 8, "pady": 4}
        main = ttk.Frame(self, padding=10)
        main.pack(fill="both", expand=True)
        main.columnconfigure(0, weight=3)
        main.columnconfigure(1, weight=2)
        main.rowconfigure(1, weight=1)

        # --- Text
        top = ttk.Frame(main)
        top.grid(row=0, column=0, sticky="ew")
        ttk.Label(top, text="1. Text (Deutsch)", font=("", 12, "bold")).pack(side="left")
        ttk.Button(top, text="Einfügen", command=self._paste).pack(side="right")
        ttk.Button(top, text="Leeren", command=lambda: self.text.delete("1.0", "end")).pack(side="right", padx=4)
        ttk.Button(top, text="Datei laden …", command=self._load_text).pack(side="right")

        tf = ttk.Frame(main)
        tf.grid(row=1, column=0, sticky="nsew", pady=(4, 8))
        self.text = tk.Text(tf, wrap="word", undo=True, font=("", 12))
        sb = ttk.Scrollbar(tf, command=self.text.yview)
        self.text.configure(yscrollcommand=sb.set)
        self.text.pack(side="left", fill="both", expand=True)
        sb.pack(side="right", fill="y")
        self.text.bind("<<Modified>>", self._count)
        self.count_lbl = ttk.Label(main, text="0 Zeichen")
        self.count_lbl.grid(row=2, column=0, sticky="w")

        # --- Rechte Seite
        right = ttk.Frame(main)
        right.grid(row=0, column=1, rowspan=3, sticky="nsew", padx=(12, 0))
        right.columnconfigure(0, weight=1)

        vbox = ttk.LabelFrame(right, text="2. Stimme", padding=8)
        vbox.grid(row=0, column=0, sticky="ew")
        vbox.columnconfigure(1, weight=1)
        ttk.Combobox(vbox, textvariable=self.voice, values=list(core.VOICES),
                     state="readonly").grid(row=0, column=0, columnspan=3, sticky="ew", pady=(0, 6))
        self._slider(vbox, 1, "Tempo %", self.rate, -40, 40)
        self._slider(vbox, 2, "Tonhöhe Hz", self.pitch, -30, 30)
        pf = ttk.Frame(vbox)
        pf.grid(row=3, column=0, columnspan=3, sticky="ew", pady=(6, 0))
        ttk.Button(pf, text="Verführerisch", command=lambda: self._preset(core.PRESET_SEXY)).pack(side="left")
        ttk.Button(pf, text="Normal", command=lambda: self._preset(core.PRESET_NORMAL)).pack(side="left", padx=4)
        ttk.Button(pf, text="▶ Probehören", command=self._preview).pack(side="right")

        ibox = ttk.LabelFrame(right, text="3. Bild", padding=8)
        ibox.grid(row=1, column=0, sticky="ew", pady=8)
        ttk.Button(ibox, text="Bild wählen …", command=self._pick_image).pack(fill="x")
        self.img_lbl = ttk.Label(ibox, text="(kein Bild)", anchor="center")
        self.img_lbl.pack(fill="x", pady=6)
        bf = ttk.Frame(ibox)
        bf.pack(fill="x")
        ttk.Label(bf, text="Rand:").pack(side="left")
        for txt, val in (("Unscharf", "blur"), ("Schwarz", "black"), ("Füllen", "fill")):
            ttk.Radiobutton(bf, text=txt, value=val, variable=self.bg).pack(side="left", padx=2)

        obox = ttk.LabelFrame(right, text="4. Video (MP4, 1280×720)", padding=8)
        obox.grid(row=2, column=0, sticky="ew")
        ttk.Checkbutton(obox, text="MP3 zusätzlich speichern", variable=self.keep_mp3).pack(anchor="w")
        self.go_btn = ttk.Button(obox, text="🎬  Video erstellen", command=self._create)
        self.go_btn.pack(fill="x", pady=(6, 4))
        self.pbar = ttk.Progressbar(obox, mode="determinate", maximum=100)
        self.pbar.pack(fill="x")

        ttk.Label(self, textvariable=self.status, relief="sunken", anchor="w",
                  padding=(8, 3)).pack(fill="x", side="bottom")

    def _slider(self, parent, row, label, var, lo, hi):
        ttk.Label(parent, text=label, width=11).grid(row=row, column=0, sticky="w")
        ttk.Scale(parent, from_=lo, to=hi, variable=var, orient="horizontal",
                  command=lambda v: var.set(int(float(v)))).grid(row=row, column=1, sticky="ew")
        ttk.Label(parent, textvariable=var, width=4).grid(row=row, column=2)

    # ------------------------------------------------------------ Aktionen
    def _count(self, _=None):
        n = len(self.text.get("1.0", "end-1c"))
        self.count_lbl.config(text=f"{n} Zeichen")
        self.text.edit_modified(False)

    def _paste(self):
        try:
            self.text.insert("insert", self.clipboard_get())
        except tk.TclError:
            pass

    def _load_text(self):
        p = filedialog.askopenfilename(filetypes=[("Text", "*.txt *.md"), ("Alle", "*.*")])
        if p:
            with open(p, encoding="utf-8", errors="replace") as f:
                self.text.delete("1.0", "end")
                self.text.insert("1.0", f.read())

    def _preset(self, p):
        self.rate.set(p["rate"])
        self.pitch.set(p["pitch"])

    def _pick_image(self):
        p = filedialog.askopenfilename(filetypes=IMG_TYPES)
        if not p:
            return
        self.image_path = p
        name = os.path.basename(p)
        if Image:
            try:
                im = ImageOps.exif_transpose(Image.open(p))
                im.thumbnail((320, 180))
                self.thumb = ImageTk.PhotoImage(im)
                self.img_lbl.config(image=self.thumb, text="")
            except Exception as e:
                self.img_lbl.config(image="", text=f"{name}\n(Vorschau nicht möglich: {e})")
        else:
            self.img_lbl.config(text=name)
        self.status.set(f"Bild: {name}")

    def _get_text(self):
        return self.text.get("1.0", "end-1c").strip()

    def _preview(self):
        text = self._get_text()
        if not text:
            messagebox.showinfo(core.APP_NAME, "Bitte zuerst Text eingeben.")
            return
        sample = text[:300]
        if self.player and self.player.poll() is None:
            self.player.terminate()
        out = os.path.join(self.tmpdir, "probe.mp3")
        self._run(self._job_preview, sample, out)

    def _create(self):
        text = self._get_text()
        if not text:
            messagebox.showwarning(core.APP_NAME, "Bitte Text eingeben.")
            return
        if not self.image_path:
            messagebox.showwarning(core.APP_NAME, "Bitte ein Bild wählen.")
            return
        base = os.path.splitext(os.path.basename(self.image_path))[0]
        out = filedialog.asksaveasfilename(defaultextension=".mp4",
                                           initialfile=f"{base}_sprache.mp4",
                                           filetypes=[("MP4-Video", "*.mp4")])
        if not out:
            return
        self._run(self._job_video, text, out)

    # ------------------------------------------------------------ Threads
    def _run(self, fn, *args):
        if self.busy:
            return
        self.busy = True
        self.go_btn.state(["disabled"])
        self.pbar["value"] = 0
        settings = (self.voice.get(), self.rate.get(), self.pitch.get(),
                    self.bg.get(), self.keep_mp3.get(), self.image_path)
        threading.Thread(target=self._wrap, args=(fn, settings) + args, daemon=True).start()

    def _wrap(self, fn, settings, *args):
        try:
            fn(settings, *args)
        except Exception as e:
            self.q.put(("error", str(e)))
        finally:
            self.q.put(("idle", None))

    def _job_preview(self, s, text, out):
        voice, rate, pitch, *_ = s
        self.q.put(("status", "Erzeuge Hörprobe …"))
        core.text_to_speech(text, out, voice, rate, pitch)
        self.q.put(("play", out))
        self.q.put(("status", "Hörprobe läuft."))

    def _job_video(self, s, text, out):
        voice, rate, pitch, bg, keep, img = s
        mp3 = os.path.splitext(out)[0] + ".mp3" if keep else os.path.join(self.tmpdir, "sprache.mp3")
        self.q.put(("status", "Schritt 1/2: Erzeuge Sprache …"))
        self.q.put(("progress", 5))
        core.text_to_speech(text, mp3, voice, rate, pitch)
        self.q.put(("status", "Schritt 2/2: Erzeuge Video (720p) …"))
        core.make_video(img, mp3, out, bg,
                        progress=lambda f: self.q.put(("progress", 10 + f * 90)))
        self.q.put(("done", out))

    def _poll(self):
        try:
            while True:
                kind, val = self.q.get_nowait()
                if kind == "status":
                    self.status.set(val)
                elif kind == "progress":
                    self.pbar["value"] = val
                elif kind == "play":
                    self.player = core.play_audio(val)
                elif kind == "error":
                    self.status.set("Fehler.")
                    messagebox.showerror(core.APP_NAME, val)
                elif kind == "done":
                    self.pbar["value"] = 100
                    self.status.set(f"Fertig: {val}")
                    messagebox.showinfo(core.APP_NAME, f"Video gespeichert:\n{val}")
                elif kind == "idle":
                    self.busy = False
                    self.go_btn.state(["!disabled"])
        except queue.Empty:
            pass
        self.after(100, self._poll)


if __name__ == "__main__":
    App().mainloop()
