# Text2Video für Android 0.1a

Text einfügen → deutsche Frauenstimme → mit einem Bild als **MP4 (1280×720)** in der Galerie speichern.
Android 10 oder neuer. Keine Fremdbibliotheken, kein Internet nötig, keine Berechtigungen.

## APK installieren

Das APK wird bei jeder Änderung im Ordner `android/` automatisch von GitHub Actions gebaut.
Auf dem Handy: **github.com/IT-DUMMKOPFEN/TextPic2MP4 → Releases → `Text2Video.apk`** herunterladen,
antippen, Installation aus unbekannter Quelle einmalig erlauben.

Selbst bauen: Ordner `android/` in **Android Studio** öffnen → *Run*.

## Bedienung

1. **Text** einfügen – oder in einer anderen App Text markieren → *Teilen* → Text2Video
2. **Stimme** wählen und mit **▶ Probehören** testen. *Verführerisch* = etwas langsamer und tiefer
   (Tempo 0,90×, Tonhöhe 0,85×). Regler frei einstellbar.
3. **Bild** wählen (oder aus der Galerie per *Teilen* schicken). Rand: *Unscharf*, *Schwarz* oder *Füllen* –
   die Vorschau zeigt genau das spätere Videobild.
4. **Video erstellen** → landet in **Filme/Text2Video**, danach *Abspielen* oder *Teilen*.

## Stimmen

Die App nutzt die Sprachausgabe des Handys (meist „Sprachausgabe von Google“), offline.
Android verrät nicht, ob eine Stimme weiblich ist – deshalb einfach durchprobieren.
Mehr bzw. bessere Stimmen: Button **Weitere Stimmen installieren …** → Google-Sprachausgabe →
Deutsch herunterladen; dort lassen sich auch verschiedene Stimmen anhören.

## Technik

- `Speech.kt` – Text in Abschnitte teilen, mit `TextToSpeech.synthesizeToFile` als WAV erzeugen, zusammenfügen
- `FrameRenderer.kt` – Bild laden (EXIF-Drehung), 1280×720-Standbild bauen, nach YUV umrechnen
- `VideoEncoder.kt` – H.264 (2,5 Mbit/s, 25 fps) + AAC 128 kbit/s mit `MediaCodec`, Ausgabe mit `MediaMuxer`
- `MainActivity.kt` – Oberfläche
