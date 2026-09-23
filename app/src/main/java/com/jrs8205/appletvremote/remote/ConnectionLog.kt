package com.jrs8205.appletvremote.remote

import android.util.Log
import com.jrs8205.appletvremote.BuildConfig
import com.jrs8205.appletvremote.protocol.log.ProtocolLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Keeps the last few hundred protocol lines in memory for the debug screen; logcat only in debug builds. */
class ConnectionLog : ProtocolLog {

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines
    private val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    override fun log(message: () -> String) {
        val text = message()
        if (BuildConfig.DEBUG) Log.d(TAG, text)
        val line = "${format.format(Date())} $text"
        _lines.value = (_lines.value + line).takeLast(MAX_LINES)
    }

    fun clear() {
        _lines.value = emptyList()
    }

    private companion object {
        const val TAG = "Companion"
        const val MAX_LINES = 300
    }
}
