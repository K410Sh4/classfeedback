package com.esphub.lab

import android.app.Application
import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ESPhubApplication : Application() {
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
            "esphub_crash_v1"

        private const val KEY_LAST_CRASH =
            "last_crash"

        fun consumeLastCrash(
            context: Context
        ): String? {
            val prefs =
                context.getSharedPreferences(
                    PREFS,
                    Context.MODE_PRIVATE
                )

            val crash =
                prefs.getString(
                    KEY_LAST_CRASH,
                    null
                )

            if (crash != null) {
                prefs.edit()
                    .remove(KEY_LAST_CRASH)
                    .commit()
            }

            return crash
        }
    }
}
