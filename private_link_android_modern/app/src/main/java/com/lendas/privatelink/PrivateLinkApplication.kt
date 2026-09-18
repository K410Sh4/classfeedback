package com.lendas.privatelink

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PrivateLinkApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val previous =
            Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val writer = StringWriter()
                throwable.printStackTrace(
                    PrintWriter(writer)
                )

                val stamp =
                    SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss",
                        Locale.US
                    ).format(Date())

                getSharedPreferences(
                    PREFS,
                    Context.MODE_PRIVATE
                )
                    .edit()
                    .putString(
                        KEY_LAST_CRASH,
                        "[$stamp] " +
                            throwable.javaClass.name +
                            ": " +
                            (throwable.message ?: "") +
                            "\n" +
                            writer.toString()
                    )
                    .commit()
            }

            previous?.uncaughtException(
                thread,
                throwable
            )
        }
    }

    companion object {
        private const val PREFS =
            "privatelink_crash_reporter"

        private const val KEY_LAST_CRASH =
            "last_crash"

        private const val KEY_LAST_EXIT_TS =
            "last_exit_ts"


        fun consumePreviousExit(
            context: Context
        ): String? {
            if (android.os.Build.VERSION.SDK_INT < 30) {
                return null
            }

            val manager =
                context.getSystemService(
                    Context.ACTIVITY_SERVICE
                ) as? ActivityManager
                    ?: return null

            val prefs =
                context.getSharedPreferences(
                    PREFS,
                    Context.MODE_PRIVATE
                )

            val lastReported =
                prefs.getLong(
                    KEY_LAST_EXIT_TS,
                    0L
                )

            val exit =
                runCatching {
                    manager.getHistoricalProcessExitReasons(
                        null,
                        0,
                        5
                    )
                }.getOrNull()
                    ?.firstOrNull {
                        it.timestamp > lastReported
                    }
                    ?: return null

            prefs.edit()
                .putLong(
                    KEY_LAST_EXIT_TS,
                    exit.timestamp
                )
                .commit()

            val reason =
                when (exit.reason) {
                    android.app.ApplicationExitInfo.REASON_CRASH ->
                        "CRASH_JAVA"
                    android.app.ApplicationExitInfo.REASON_CRASH_NATIVE ->
                        "CRASH_NATIVE"
                    android.app.ApplicationExitInfo.REASON_ANR ->
                        "ANR"
                    android.app.ApplicationExitInfo.REASON_LOW_MEMORY ->
                        "LOW_MEMORY"
                    android.app.ApplicationExitInfo.REASON_USER_REQUESTED ->
                        "USER_REQUESTED"
                    else ->
                        "REASON_" + exit.reason
                }

            return (
                "Última saída do processo: " +
                    reason +
                    " • status=" +
                    exit.status +
                    " • importance=" +
                    exit.importance
            )
        }

        fun consumeLastCrash(
            context: Context
        ): String? {
            val prefs =
                context.getSharedPreferences(
                    PREFS,
                    Context.MODE_PRIVATE
                )

            val value =
                prefs.getString(
                    KEY_LAST_CRASH,
                    null
                )

            if (value != null) {
                prefs.edit()
                    .remove(KEY_LAST_CRASH)
                    .apply()
            }

            return value
        }
    }
}
