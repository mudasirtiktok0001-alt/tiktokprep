@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.mudasir.tiktokprep

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private class Job(val account: File, val video: File, val sound: File, val out: File)

    // Phone ke storage mein: TikTok/<account_name>/ , TikTok/sounds/ , TikTok/ready/
    private val root = File(Environment.getExternalStorageDirectory(), "TikTok")
    private val videoExt = setOf("mp4", "mov", "mkv", "m4v", "3gp")
    private val soundExt = setOf("mp3", "m4a", "aac", "wav", "ogg", "mp4")
    private val reserved = setOf("sounds", "ready")
    private val prefs by lazy { getSharedPreferences("counts", Context.MODE_PRIVATE) }

    private val jobs = mutableListOf<Job>()
    private var total = 0
    private var done = 0
    private var failed = 0
    private var busy = false

    private lateinit var list: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var runBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
        }
        col.addView(TextView(this).apply {
            text = "Mudasir Trusted Seller"
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#B8860B"))
        })
        col.addView(TextView(this).apply {
            text = "TikTok Video Prep"
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        })
        col.addView(Button(this).apply {
            text = "Permission do"
            setOnClickListener { askAccess() }
        })
        runBtn = Button(this).apply {
            text = "Sounds lagao"
            setOnClickListener { startProcessing() }
        }
        col.addView(runBtn)
        statusView = TextView(this).apply { setPadding(0, 20, 0, 20) }
        col.addView(statusView)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)

        setContentView(ScrollView(this).apply { addView(col) })
        Toast.makeText(this, "Mudasir Trusted Seller", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        if (hasAccess()) {
            File(root, "sounds").mkdirs()
            if (!busy) statusView.text = "Folder: ${root.absolutePath}"
            render()
        } else {
            statusView.text = "Pehle 'Permission do' dabayein aur Allow karein."
        }
    }

    // ---------- Permission ----------

    private fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun askAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } else {
            requestPermissions(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ), 1
            )
        }
    }

    // ---------- Folders ----------

    private fun accounts(): List<File> =
        root.listFiles()
            ?.filter { it.isDirectory && it.name.lowercase() !in reserved }
            ?.sortedBy { it.name }
            ?: emptyList()

    private fun videosOf(acc: File): List<File> =
        acc.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in videoExt }
            ?.sortedBy { it.name }
            ?: emptyList()

    private fun render() {
        list.removeAllViews()
        val accs = accounts()
        if (accs.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "TikTok folder mein har account ka folder banayein (jaise ayeza_khan) aur us mein videos rakhein."
            })
            return
        }
        accs.forEach { acc ->
            val count = prefs.getInt(acc.name, 3)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 12, 0, 12)
            }
            row.addView(TextView(this).apply {
                text = "${acc.name}\nbaqi videos: ${videosOf(acc).size}"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(this).apply {
                text = "-"
                setOnClickListener {
                    prefs.edit().putInt(acc.name, maxOf(0, count - 1)).apply()
                    render()
                }
            })
            row.addView(TextView(this).apply {
                text = " $count "
                textSize = 18f
            })
            row.addView(Button(this).apply {
                text = "+"
                setOnClickListener {
                    prefs.edit().putInt(acc.name, count + 1).apply()
                    render()
                }
            })
            list.addView(row)
        }
    }

    // ---------- Processing ----------

    private fun startProcessing() {
        if (busy) return
        if (!hasAccess()) {
            statusView.text = "Pehle permission dein."
            return
        }
        val sounds = File(root, "sounds").listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in soundExt }
            ?.shuffled()
            ?: emptyList()
        if (sounds.isEmpty()) {
            statusView.text = "TikTok/sounds folder mein sounds (mp3/m4a/mp4) rakhein."
            return
        }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        jobs.clear()
        var si = 0
        for (acc in accounts()) {
            val n = prefs.getInt(acc.name, 3)
            videosOf(acc).take(n).forEachIndexed { i, v ->
                val out = File(root, "ready/${acc.name}/${acc.name}_${stamp}_v${i + 1}.mp4")
                jobs.add(Job(acc, v, sounds[si % sounds.size], out))
                si++
            }
        }
        if (jobs.isEmpty()) {
            statusView.text = "Kisi account mein video nahi mili."
            return
        }
        total = jobs.size
        done = 0
        failed = 0
        busy = true
        runBtn.isEnabled = false
        next()
    }

    private fun durationMs(f: File): Long = try {
        val r = MediaMetadataRetriever()
        r.setDataSource(f.absolutePath)
        val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        r.release()
        d
    } catch (e: Exception) {
        0L
    }

    private fun finishJob(job: Job, ok: Boolean) {
        if (ok) {
            done++
            // Asli video "done" folder mein chali jati hai taake dobara use na ho
            val doneDir = File(job.account, "done")
            doneDir.mkdirs()
            job.video.renameTo(File(doneDir, job.video.name))
        } else {
            failed++
        }
        next()
    }

    private fun next() {
        if (jobs.isEmpty()) {
            busy = false
            runBtn.isEnabled = true
            statusView.text = "Mukammal: $done tayyar, $failed fail.\nVideos 'TikTok/ready' folder mein hain."
            render()
            return
        }
        val job = jobs.removeAt(0)
        statusView.text = "Ban rahi hai ${done + failed + 1}/$total : ${job.account.name}"

        try {
            job.out.parentFile?.mkdirs()
            val vDur = durationMs(job.video)
            val sDur = durationMs(job.sound)
            // Sound video se lambi ho to video ki length par kaat di jati hai
            val end = if (vDur > 0 && sDur > 0) minOf(vDur, sDur) else 0L

            val soundItem = MediaItem.Builder()
                .setUri(Uri.fromFile(job.sound))
                .apply {
                    if (end > 0) {
                        setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder().setEndPositionMs(end).build()
                        )
                    }
                }
                .build()

            val videoEdited = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(job.video)))
                .setRemoveAudio(true)
                .build()
            val audioEdited = EditedMediaItem.Builder(soundItem)
                .setRemoveVideo(true)
                .build()

            val composition = Composition.Builder(
                listOf(
                    EditedMediaItemSequence(listOf(videoEdited)),
                    EditedMediaItemSequence(listOf(audioEdited))
                )
            ).build()

            Transformer.Builder(this)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        finishJob(job, true)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        finishJob(job, false)
                    }
                })
                .build()
                .start(composition, job.out.absolutePath)
        } catch (e: Exception) {
            finishJob(job, false)
        }
    }
}
