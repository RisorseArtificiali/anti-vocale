package com.antivocale.app.spike

import android.content.Context
import android.os.Environment
import android.os.FileObserver
import android.util.Log
import java.io.File
import java.io.FileInputStream

/**
 * TASK-271 follow-up spike (2026-10-13): the device trial proved WhatsApp
 * never indexes voice notes into MediaStore, so the ContentObserver premise
 * is dead. This spike probes the three open questions from the research
 * report (claudedocs/research_task271-alternative-detection-routes):
 *
 * PROBE 1 (enumeration): can the app process LIST the Voice Notes tree at
 * all (java.io.File)? The run-as approximation said no; the real process
 * may differ (its own FUSE mount).
 *
 * PROBE 2 (events): does FileObserver deliver create/write events for the
 * tree? The CCS 2026 "File Notification Attacks" research (inoti.fyi)
 * demonstrated FileObserver bypasses FUSE per-app isolation specifically
 * against WhatsApp. FileObserver is NOT recursive, and the dated
 * counter-named subfolders cannot be enumerated if probe 1 fails, so the
 * spike also blindly watches a counter RANGE (exists() by name needs no
 * listing) plus any directory name that surfaces in a root event.
 *
 * PROBE 3 (read access): when an event names a fresh .opus, can the app
 * actually OPEN and read it without a SAF grant? (run-as said no; if
 * confirmed, the production read path must be the SAF tree grant.)
 *
 * Debug-only, logged under the VNFS tag, armed from the connected
 * notification-listener spike (already bound by the system). No
 * production wiring, no strings, no manifest changes.
 */
object VoiceNoteFsSpike {

    private const val TAG = "VNFS"

    /** WhatsApp's counter-named subfolders live in a small recent range. */
    private const val COUNTER_RANGE = 40

    private val observers = mutableListOf<FileObserver>()

    fun arm() {
        if (observers.isNotEmpty()) return
        val root = File(
            Environment.getExternalStorageDirectory(),
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes")

        // PROBE 1: enumeration through the app's own FUSE mount.
        val children = root.listFiles()?.map { it.name }
        Log.i(TAG, "probe1 root.exists=${root.exists()} canRead=${root.canRead()} " +
            "list=${children ?: listOf("<null: no enumeration>")}")

        watch(root, label = "root")

        // PROBE 2 fallback: the counter folders. If enumeration failed we
        // still cover today's folder by probing exists() over a range.
        val counters = children?.filter { it.toIntOrNull() != null }
            ?: (0..COUNTER_RANGE).mapNotNull { n ->
                val name = "${202620 + n}"
                if (File(root, name).exists()) name else null
            }
        Log.i(TAG, "watching counters=$counters")
        counters.forEach { watch(File(root, it), label = "counter/$it") }
    }

    private fun watch(dir: File, label: String) {
        val observer = object : FileObserver(
            // The String constructor: FileObserver(File, Int) is API 29+,
            // minSdk is 26 (lint-fatal, CI 2026-10-14).
            dir.absolutePath,
            FileObserver.CREATE or FileObserver.CLOSE_WRITE or
                FileObserver.MOVED_TO or FileObserver.DELETE,
        ) {
            override fun onEvent(event: Int, path: String?) {
                Log.i(TAG, "probe2 event=0x${Integer.toHexString(event)} " +
                    "dir=$label path=$path")
                if (path == null) return
                // A new dated folder surfacing at the root: watch it too.
                if (path.toIntOrNull() != null && (event and (FileObserver.CREATE or
                            FileObserver.MOVED_TO)) != 0)
                {
                    watch(File(dir, path), label = "late/$path")
                }
                // PROBE 3: readability of a fresh voice note.
                if (path.endsWith(".opus", ignoreCase = true)) {
                    val file = File(dir, path)
                    val verdict = try {
                        FileInputStream(file).use { input ->
                            val head = ByteArray(8)
                            val n = input.read(head)
                            "READABLE(first=$n bytes: ${head.joinToString(",") { it.toString() }})"
                        }
                    } catch (e: Exception) {
                        "UNREADABLE(${e.javaClass.simpleName}: ${e.message})"
                    }
                    Log.i(TAG, "probe3 $file -> $verdict")
                }
            }
        }
        observer.startWatching()
        synchronized(observers) { observers.add(observer) }
        Log.i(TAG, "watching $label")
    }

    /**
     * PROBE 4 (read via on-demand indexing): we can LIST but not OPEN
     * (probe 3, EACCES). If the app can have MediaProvider INDEX the file
     * on demand, the resulting row (attributed to WhatsApp, but visible
     * with READ_MEDIA_AUDIO) should be openable through its content URI.
     * Runs on the newest existing PTT at arm time, so it needs no fresh
     * voice note.
     */
    fun probeScanRead(context: Context) {
        val root = File(
            Environment.getExternalStorageDirectory(),
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes")
        // Level-2 enumeration is FUSE-blocked (listFiles returns empty
        // inside the counters), so discovery relies on observer events;
        // for the arm-time run we use today's trial files by name.
        val newest = File(root, "202642/PTT-20261013-WA0009.opus").takeIf { it.exists() }
        if (newest == null) {
            Log.i(TAG, "probe4 no PTT found to scan")
            return
        }
        Log.i(TAG, "probe4 scanning $newest (lastModified=${newest.lastModified()})")
        android.media.MediaScannerConnection.scanFile(
            context, arrayOf(newest.absolutePath), arrayOf("audio/opus")
        ) { _, uri ->
            Log.i(TAG, "probe4 scanCompleted uri=$uri")
            if (uri == null) return@scanFile
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val head = ByteArray(8)
                    val n = input.read(head)
                    Log.i(TAG, "probe4 READ-VIA-ROW first=$n bytes: " +
                        head.joinToString(",") { it.toString() })
                } ?: Log.i(TAG, "probe4 openInputStream returned null")
            } catch (e: Exception) {
                Log.i(TAG, "probe4 READ-VIA-ROW FAILED " +
                    "(${e.javaClass.simpleName}: ${e.message})")
            }
        }
    }

    fun disarm() {
        synchronized(observers) {
            observers.forEach { it.stopWatching() }
            observers.clear()
        }
    }
}
