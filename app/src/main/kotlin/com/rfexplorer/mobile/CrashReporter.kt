package com.rfexplorer.mobile

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Captures uncaught exceptions to a file so the next launch can show the stack trace
 * on screen. Lets us diagnose crashes without adb/logcat access.
 */
object CrashReporter {
    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val appCtx = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                File(appCtx.filesDir, FILE).writeText(
                    "RF Explorer crash\nThread: ${thread.name}\n\n$sw",
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Returns the last crash trace (if any) and clears it. */
    fun consume(context: Context): String? {
        val f = File(context.applicationContext.filesDir, FILE)
        if (!f.exists()) return null
        val text = runCatching { f.readText() }.getOrNull()
        runCatching { f.delete() }
        return text
    }
}
