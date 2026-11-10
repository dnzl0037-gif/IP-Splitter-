package com.ipsplit.tracker

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private class CardViews(val title: TextView, val label: TextView, val details: TextView)

    private lateinit var cellCard: CardViews
    private lateinit var wifiCard: CardViews
    private lateinit var historyView: TextView

    private val listener: () -> Unit = { runOnUiThread { render() } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.load(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(32), dp(16), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "IP Split Tracker"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Econet (mobile data) and ZOL (Wi-Fi) tracked at the same time"
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, dp(12))
        })

        cellCard = makeCard(root, "MOBILE DATA", "#E8F5E9")
        wifiCard = makeCard(root, "WI-FI", "#E3F2FD")

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(button("Refresh") { sendToService(TrackerService.ACTION_REFRESH) })
        buttons.addView(button("Stop") { sendToService(TrackerService.ACTION_STOP) })
        buttons.addView(button("Clear log") { Store.clearHistory(this) })
        root.addView(buttons)

        root.addView(TextView(this).apply {
            text = "IP change history"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(4))
        })
        historyView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        root.addView(historyView)

        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        sendToService(null)
    }

    override fun onStart() {
        super.onStart()
        Store.listeners.add(listener)
        render()
    }

    override fun onStop() {
        Store.listeners.remove(listener)
        super.onStop()
    }

    private fun makeCard(parent: LinearLayout, title: String, bg: String): CardViews {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bg))
                cornerRadius = dp(14).toFloat()
            }
        }
        val t = TextView(this).apply { text = title; textSize = 12f; setTextColor(Color.DKGRAY) }
        val l = TextView(this).apply { textSize = 26f; setTypeface(null, Typeface.BOLD) }
        val d = TextView(this).apply { textSize = 14f; setTextIsSelectable(true) }
        box.addView(t); box.addView(l); box.addView(d)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }
        parent.addView(box, lp)
        return CardViews(t, l, d)
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun sendToService(action: String?) {
        val i = Intent(this, TrackerService::class.java)
        if (action != null) i.action = action
        startForegroundService(i)
    }

    private fun fill(v: CardViews, c: Card) {
        v.label.text = c.label
        v.details.text = "Public IP:  ${c.publicIp}\n" +
            "Local IP:   ${c.localIp}\n" +
            "ISP:        ${c.isp}\n" +
            "Location:   ${c.place}\n" +
            "Updated:    ${c.updated}\n" +
            "Status:     ${c.note}"
    }

    private fun render() {
        fill(cellCard, Store.cell)
        fill(wifiCard, Store.wifi)
        val lines = synchronized(Store.history) { Store.history.toList() }
        historyView.text = if (lines.isEmpty()) "No changes recorded yet" else lines.joinToString("\n")
        historyView.visibility = View.VISIBLE
    }
}
