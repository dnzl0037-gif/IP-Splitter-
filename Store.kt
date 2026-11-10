package com.ipsplit.tracker

import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList

data class Card(
    val connected: Boolean = false,
    val label: String = "-",
    val localIp: String = "-",
    val publicIp: String = "-",
    val isp: String = "-",
    val place: String = "-",
    val updated: String = "-",
    val note: String = "Waiting..."
)

object Store {
    @Volatile var cell = Card()
    @Volatile var wifi = Card()
    val history = ArrayList<String>()
    val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var loaded = false

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("ipsplit", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        synchronized(history) {
            if (loaded) return
            loaded = true
            val saved = prefs(ctx).getString("history", "") ?: ""
            history.addAll(saved.split("\n").filter { it.isNotBlank() })
        }
    }

    fun addHistory(ctx: Context, line: String) {
        synchronized(history) {
            history.add(0, line)
            while (history.size > 200) history.removeAt(history.size - 1)
            prefs(ctx).edit().putString("history", history.joinToString("\n")).apply()
        }
    }

    fun clearHistory(ctx: Context) {
        synchronized(history) {
            history.clear()
            prefs(ctx).edit().putString("history", "").apply()
        }
        changed()
    }

    fun changed() {
        listeners.forEach { it() }
    }
}
