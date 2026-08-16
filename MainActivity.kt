package com.example.pisadores

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var startUri: Uri? = null
    private var middleUri: Uri? = null
    private var endUri: Uri? = null
    private val songs = mutableListOf<Uri>()

    private val startPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) {
        startUri = it
        updateStatus("Pisador de inicio seleccionado.")
    }
    private val middlePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) {
        middleUri = it
        updateStatus("Pisador de mitad seleccionado.")
    }
    private val endPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) {
        endUri = it
        updateStatus("Pisador final seleccionado.")
    }
    private val songPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        songs.clear()
        songs.addAll(it)
        updateStatus("${songs.size} canción(es) seleccionada(s).")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.pickStart).setOnClickListener { startPicker.launch(arrayOf("audio/*")) }
        findViewById<Button>(R.id.pickMiddle).setOnClickListener { middlePicker.launch(arrayOf("audio/*")) }
        findViewById<Button>(R.id.pickEnd).setOnClickListener { endPicker.launch(arrayOf("audio/*")) }
        findViewById<Button>(R.id.pickSongs).setOnClickListener { songPicker.launch(arrayOf("audio/*")) }
        findViewById<Button>(R.id.process).setOnClickListener { processAll() }
    }

    private fun copyToCache(uri: Uri, name: String): File {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val out = File(cacheDir, "${System.currentTimeMillis()}_$safe")
        contentResolver.openInputStream(uri)!!.use { input ->
            FileOutputStream(out).use { output -> input.copyTo(output) }
        }
        return out
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return "cancion.mp3"
    }

    private fun durationSeconds(file: File): Double {
        val info = FFprobeKit.getMediaInformation(file.absolutePath).mediaInformation
        return info.duration?.toDoubleOrNull() ?: 0.0
    }

    private fun processAll() {
        val a = startUri
        val b = middleUri
        val c = endUri
        if (a == null || b == null || c == null || songs.isEmpty()) {
            Toast.makeText(this, "Selecciona los 3 pisadores y al menos una canción.", Toast.LENGTH_LONG).show()
            return
        }

        val progress = findViewById<ProgressBar>(R.id.progress)
        progress.visibility = View.VISIBLE
        findViewById<Button>(R.id.process).isEnabled = false

        Thread {
            try {
                val start = copyToCache(a, "pisador_inicio")
                val middle = copyToCache(b, "pisador_mitad")
                val end = copyToCache(c, "pisador_final")

                var done = 0
                for (songUri in songs) {
                    val song = copyToCache(songUri, displayName(songUri))
                    val duration = durationSeconds(song)
                    if (duration <= 0) continue

                    val middleSec = duration / 2.0
                    val endSec = (duration - 45.0).coerceAtLeast(0.0)

                    val startMs = 10_000L
                    val middleMs = (middleSec * 1000).toLong()
                    val endMs = (endSec * 1000).toLong()

                    val baseName = displayName(songUri).substringBeforeLast(".")
                    val out = File(getExternalFilesDir(null), "${baseName}_pisadores.mp3")

                    val filter = "[1:a]adelay=${startMs}:all=1[a1];" +
                            "[2:a]adelay=${middleMs}:all=1[a2];" +
                            "[3:a]adelay=${endMs}:all=1[a3];" +
                            "[0:a][a1][a2][a3]amix=inputs=4:duration=first:dropout_transition=0:normalize=0[aout]"

                    val cmd = "-y -i "${song.absolutePath}" " +
                            "-i "${start.absolutePath}" -i "${middle.absolutePath}" -i "${end.absolutePath}" " +
                            "-filter_complex "$filter" -map "[aout]" -map_metadata 0 " +
                            "-c:a libmp3lame -b:a 320k "${out.absolutePath}""

                    val session = FFmpegKit.execute(cmd)
                    if (ReturnCode.isSuccess(session.returnCode)) done++

                    runOnUiThread {
                        updateStatus("Procesadas: $done/${songs.size}")
                    }
                }

                runOnUiThread {
                    progress.visibility = View.GONE
                    findViewById<Button>(R.id.process).isEnabled = true
                    Toast.makeText(this, "Listo. Las copias están en la carpeta de archivos de la aplicación.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    findViewById<Button>(R.id.process).isEnabled = true
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun updateStatus(text: String) {
        findViewById<TextView>(R.id.status).text = text
    }
}
