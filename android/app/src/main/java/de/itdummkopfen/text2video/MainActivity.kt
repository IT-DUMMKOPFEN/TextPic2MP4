package de.itdummkopfen.text2video

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var speech: Speech
    private lateinit var text: EditText
    private lateinit var countLbl: TextView
    private lateinit var voiceSpinner: Spinner
    private lateinit var voiceHint: TextView
    private lateinit var rateBar: SeekBar
    private lateinit var pitchBar: SeekBar
    private lateinit var rateLbl: TextView
    private lateinit var pitchLbl: TextView
    private lateinit var preview: ImageView
    private lateinit var createBtn: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var resultRow: LinearLayout

    private var voices: List<Voice> = emptyList()
    private var source: Bitmap? = null
    private var frame: Bitmap? = null
    private var mode = FrameRenderer.Mode.BLUR
    private var lastVideo: Uri? = null

    @Volatile private var busy = false
    @Volatile private var cancel = false

    // Voreinstellungen (SeekBar 0..100 entspricht 0.5..1.5)
    private val sexyRate = 40     // 0.90×
    private val sexyPitch = 35    // 0.85×

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        say("Sprachausgabe wird geladen …")
        speech = Speech(this) { ok -> runOnUiThread { onSpeechReady(ok) } }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        cancel = true
        speech.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun heading(t: String) = TextView(this).apply {
        text = t
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(18), 0, dp(6))
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(32))
        }

        // 1. Text
        col.addView(heading("1. Text (Deutsch)"))
        text = EditText(this).apply {
            hint = "Text hier einfügen …"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 6
            maxLines = 14
            gravity = Gravity.TOP or Gravity.START
            isVerticalScrollBarEnabled = true
        }
        col.addView(text)
        countLbl = TextView(this).apply { text = "0 Zeichen"; alpha = 0.7f }
        col.addView(countLbl)
        text.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { countLbl.text = "${s?.length ?: 0} Zeichen" }
        })
        col.addView(row(
            button("Einfügen") { paste() },
            button("Leeren") { text.setText("") },
        ))

        // 2. Stimme
        col.addView(heading("2. Stimme"))
        voiceSpinner = Spinner(this)
        col.addView(voiceSpinner)
        voiceHint = TextView(this).apply {
            alpha = 0.7f
            text = "Tipp: mit ▶ Probehören die passende Frauenstimme aussuchen."
        }
        col.addView(voiceHint)

        rateLbl = TextView(this)
        rateBar = SeekBar(this).apply { max = 100; progress = sexyRate }
        pitchLbl = TextView(this)
        pitchBar = SeekBar(this).apply { max = 100; progress = sexyPitch }
        val sl = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) = updateLabels()
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        }
        rateBar.setOnSeekBarChangeListener(sl)
        pitchBar.setOnSeekBarChangeListener(sl)
        col.addView(rateLbl); col.addView(rateBar)
        col.addView(pitchLbl); col.addView(pitchBar)
        updateLabels()

        col.addView(row(
            button("Verführerisch") { rateBar.progress = sexyRate; pitchBar.progress = sexyPitch },
            button("Normal") { rateBar.progress = 50; pitchBar.progress = 50 },
        ))
        col.addView(row(
            button("▶ Probehören") { previewSpeech() },
            button("■ Stopp") { speech.stop() },
        ))
        col.addView(button("Weitere Stimmen installieren …") { openTtsSettings() })

        // 3. Bild
        col.addView(heading("3. Bild"))
        col.addView(button("Bild wählen …") { pickImage() })
        preview = ImageView(this).apply {
            setBackgroundColor(Color.rgb(40, 40, 40))
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }
        col.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(190)).apply {
            topMargin = dp(8); bottomMargin = dp(4)
        })
        val group = RadioGroup(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Unscharf" to FrameRenderer.Mode.BLUR, "Schwarz" to FrameRenderer.Mode.BLACK,
            "Füllen" to FrameRenderer.Mode.FILL).forEach { (label, m) ->
            val rb = RadioButton(this).apply {
                text = label
                id = View.generateViewId()
                isChecked = m == mode
                setOnClickListener { mode = m; rerender() }
            }
            group.addView(rb)
        }
        col.addView(group)

        // 4. Video
        col.addView(heading("4. Video (MP4, 1280×720)"))
        createBtn = button("🎬  Video erstellen") { onCreateClicked() }
        col.addView(createBtn)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        col.addView(progress)
        status = TextView(this).apply { setPadding(0, dp(6), 0, dp(6)) }
        col.addView(status)
        resultRow = row(
            button("Abspielen") { openVideo() },
            button("Teilen") { shareVideo() },
        ).apply { visibility = View.GONE }
        col.addView(resultRow)
        col.addView(TextView(this).apply {
            text = "Text2Video 0.1a · IT-Dummkopfen · Videos landen in Filme/Text2Video"
            alpha = 0.5f; textSize = 12f; setPadding(0, dp(16), 0, 0)
        })

        return ScrollView(this).apply {
            fitsSystemWindows = true
            isFillViewport = true
            addView(col)
        }
    }

    private fun rateValue() = 0.5f + rateBar.progress / 100f
    private fun pitchValue() = 0.5f + pitchBar.progress / 100f

    private fun updateLabels() {
        rateLbl.text = String.format(Locale.GERMANY, "Tempo: %.2f×", rateValue())
        pitchLbl.text = String.format(Locale.GERMANY, "Tonhöhe: %.2f×", pitchValue())
    }

    private fun say(s: String) { status.text = s }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------ Stimmen
    private fun onSpeechReady(ok: Boolean) {
        if (!ok) {
            say("Sprachausgabe nicht verfügbar. Bitte „Sprachausgabe von Google“ installieren.")
            return
        }
        voices = speech.germanVoices
        if (voices.isEmpty()) {
            voiceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                listOf("Standard (Deutsch)"))
            voiceHint.text = "Keine deutsche Stimme gefunden – bitte über „Weitere Stimmen installieren“ " +
                "Deutsch herunterladen."
        } else {
            val names = voices.mapIndexed { i, v ->
                "Stimme ${i + 1} · ${v.locale.country} · ${v.name}" +
                    if (v.isNetworkConnectionRequired) " (online)" else ""
            }
            voiceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
            val def = speech.defaultVoice
            val idx = voices.indexOfFirst { it.name == def?.name }
            if (idx >= 0) voiceSpinner.setSelection(idx)
        }
        voiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {}
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        say("Bereit. Text einfügen, Bild wählen, Video erstellen.")
    }

    private fun selectedVoice(): Voice? = voices.getOrNull(voiceSpinner.selectedItemPosition)

    private fun previewSpeech() {
        val t = text.text.toString().trim()
        val sample = if (t.isEmpty()) "Hallo. Schön, dass du da bist. So klinge ich." else t.take(300)
        speech.configure(selectedVoice(), rateValue(), pitchValue())
        speech.speak(sample)
    }

    private fun openTtsSettings() {
        val intents = listOf(
            Intent("com.android.settings.TTS_SETTINGS"),
            Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA),
        )
        for (i in intents) {
            try { startActivity(i); return } catch (_: Exception) {}
        }
        toast("Bitte in den Android-Einstellungen unter „Sprachausgabe“ Deutsch installieren.")
    }

    private fun paste() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val s = clip.getItemAt(0).coerceToText(this)
            text.text.insert(text.selectionStart.coerceAtLeast(0), s)
        } else toast("Zwischenablage ist leer.")
    }

    // ---------------------------------------------------------------- Bild
    private fun pickImage() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(Intent.createChooser(i, "Bild wählen"), REQ_IMAGE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMAGE && resultCode == RESULT_OK) data?.data?.let { loadImage(it) }
    }

    private fun loadImage(uri: Uri) {
        say("Bild wird geladen …")
        Thread {
            try {
                val bmp = FrameRenderer.load(contentResolver, uri)
                val f = FrameRenderer.render(bmp, mode)
                runOnUiThread {
                    source = bmp; frame = f
                    preview.setImageBitmap(f)
                    say("Bild geladen (${bmp.width}×${bmp.height}).")
                }
            } catch (e: Exception) {
                runOnUiThread { say("Bild konnte nicht geladen werden: ${e.message}") }
            }
        }.start()
    }

    private fun rerender() {
        val src = source ?: return
        Thread {
            val f = FrameRenderer.render(src, mode)
            runOnUiThread { frame = f; preview.setImageBitmap(f) }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun handleIntent(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val type = i.type ?: return
        if (type.startsWith("text/")) {
            i.getStringExtra(Intent.EXTRA_TEXT)?.let { text.setText(it) }
        } else if (type.startsWith("image/")) {
            i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { loadImage(it) }
        }
    }

    // --------------------------------------------------------------- Video
    private fun onCreateClicked() {
        if (busy) { cancel = true; say("Wird abgebrochen …"); return }
        val t = text.text.toString().trim()
        val f = frame
        when {
            t.isEmpty() -> { toast("Bitte Text eingeben."); return }
            f == null -> { toast("Bitte ein Bild wählen."); return }
        }
        speech.stop()
        busy = true; cancel = false
        createBtn.text = "Abbrechen"
        resultRow.visibility = View.GONE
        progress.progress = 0
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val voice = selectedVoice()
        val rate = rateValue()
        val pitch = pitchValue()
        val image = f!!

        Thread {
            val work = File(cacheDir, "work").apply { mkdirs() }
            val tmp = File(work, "video.mp4")
            try {
                ui { say("Schritt 1/3: Sprache wird erzeugt …") }
                speech.configure(voice, rate, pitch)
                val pcm = speech.synthesize(t, work, { cancel }) { done, total ->
                    ui {
                        progress.progress = done * 30 / total
                        say("Schritt 1/3: Sprache wird erzeugt … ($done/$total)")
                    }
                }
                val secs = pcm.durationUs / 1_000_000
                ui { say("Schritt 2/3: Video wird erzeugt (${secs / 60}:${"%02d".format(secs % 60)} min) …") }
                val yuv = FrameRenderer.toYuv(image)
                VideoEncoder.encode(yuv, pcm, tmp, 25, { cancel }) { p ->
                    ui { progress.progress = 30 + (p * 65).toInt() }
                }
                ui { say("Schritt 3/3: Wird in der Galerie gespeichert …") }
                val name = "Text2Video_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.GERMANY).format(Date()) + ".mp4"
                val uri = saveToGallery(tmp, name)
                ui {
                    lastVideo = uri
                    progress.progress = 100
                    say("Fertig: Filme/Text2Video/$name")
                    resultRow.visibility = View.VISIBLE
                }
            } catch (e: InterruptedException) {
                ui { say("Abgebrochen."); progress.progress = 0 }
            } catch (e: Exception) {
                ui {
                    say("Fehler: ${e.message}")
                    AlertDialog.Builder(this).setTitle("Fehler")
                        .setMessage(e.message ?: e.toString()).setPositiveButton("OK", null).show()
                }
            } finally {
                tmp.delete()
                ui {
                    busy = false
                    createBtn.text = "🎬  Video erstellen"
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }.start()
    }

    private fun ui(block: () -> Unit) = runOnUiThread { if (!isDestroyed) block() }

    private fun saveToGallery(file: File, name: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Text2Video")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Konnte Video nicht in der Galerie anlegen")
        contentResolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        } ?: throw IOException("Konnte Video nicht speichern")
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        return uri
    }

    private fun openVideo() {
        val uri = lastVideo ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) { toast("Kein Videoplayer gefunden.") }
    }

    private fun shareVideo() {
        val uri = lastVideo ?: return
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, "Video teilen"))
    }

    companion object {
        private const val REQ_IMAGE = 42
    }
}
