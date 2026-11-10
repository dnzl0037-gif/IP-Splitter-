package com.ipsplit.tracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TrackerService : Service() {

    companion object {
        const val ACTION_REFRESH = "com.ipsplit.tracker.REFRESH"
        const val ACTION_STOP = "com.ipsplit.tracker.STOP"
        private const val CHANNEL = "ipsplit"
        private const val NOTIF_ID = 1
        private const val KEY_CELL = "cell"
        private const val KEY_WIFI = "wifi"
    }

    private lateinit var cm: ConnectivityManager
    private val nets = ConcurrentHashMap<String, Network>()
    private val lastIp = ConcurrentHashMap<String, String>()
    private val callbacks = mutableListOf<ConnectivityManager.NetworkCallback>()
    private val pool = Executors.newFixedThreadPool(2)
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        cm = getSystemService(ConnectivityManager::class.java)
        Store.load(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground("Starting...")

        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!started) {
            started = true
            register(KEY_CELL, NetworkCapabilities.TRANSPORT_CELLULAR)
            register(KEY_WIFI, NetworkCapabilities.TRANSPORT_WIFI)
            timer.scheduleWithFixedDelay({ refreshAll() }, 30, 30, TimeUnit.SECONDS)
        }
        if (intent?.action == ACTION_REFRESH) refreshAll()
        return START_STICKY
    }

    override fun onDestroy() {
        callbacks.forEach { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        timer.shutdownNow()
        pool.shutdownNow()
        super.onDestroy()
    }

    private fun register(key: String, transport: Int) {
        val request = NetworkRequest.Builder()
            .addTransportType(transport)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                nets[key] = network
                refresh(key)
            }

            override fun onLost(network: Network) {
                if (nets[key] == network) {
                    nets.remove(key)
                    publish(key, Card(note = "Not connected"))
                }
            }
        }
        callbacks.add(cb)
        try {
            cm.requestNetwork(request, cb)
        } catch (e: Exception) {
            publish(key, Card(note = "Cannot request network: ${e.message}"))
        }
    }

    private fun refreshAll() {
        nets.keys.forEach { refresh(it) }
    }

    private fun refresh(key: String) {
        val network = nets[key] ?: return
        pool.execute { publish(key, probe(network)) }
    }

    private fun probe(network: Network): Card {
        val lp = cm.getLinkProperties(network)
        val addrs = lp?.linkAddresses?.map { it.address } ?: emptyList()
        val local = addrs.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
            ?: addrs.firstOrNull { !it.isLoopbackAddress }?.hostAddress
            ?: "-"
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

        try {
            val json = JSONObject(httpGet(network, "https://ipinfo.io/json"))
            val ip = json.optString("ip", "-")
            val org = json.optString("org", "-")
            val place = listOf(json.optString("city"), json.optString("country"))
                .filter { it.isNotBlank() }.joinToString(", ").ifBlank { "-" }
            return Card(true, detect(org), local, ip, org, place, time, "OK")
        } catch (e: Exception) {
            try {
                val json = JSONObject(httpGet(network, "https://api.ipify.org?format=json"))
                return Card(true, "Unknown ISP", local, json.optString("ip", "-"), "-", "-", time,
                    "ISP lookup failed, IP only")
            } catch (e2: Exception) {
                return Card(true, "No internet", local, "-", "-", "-", time,
                    "Connected but no internet: ${e2.message}")
            }
        }
    }

    private fun httpGet(network: Network, url: String): String {
        val conn = network.openConnection(URL(url)) as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("Accept", "application/json")
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun detect(org: String): String {
        val l = org.lowercase(Locale.US)
        return when {
            "econet" in l -> "Econet"
            "zol" in l -> "ZOL"
            else -> "Other ISP"
        }
    }

    private fun publish(key: String, card: Card) {
        val old = if (key == KEY_CELL) Store.cell else Store.wifi
        val name = if (key == KEY_CELL) "Mobile data" else "Wi-Fi"
        val time = SimpleDateFormat("dd MMM HH:mm:ss", Locale.US).format(Date())

        if (card.publicIp != "-") {
            val prev = lastIp[key]
            if (prev == null) {
                Store.addHistory(this, "$time | $name (${card.label}) | first seen ${card.publicIp}")
            } else if (prev != card.publicIp) {
                Store.addHistory(this, "$time | $name (${card.label}) | $prev -> ${card.publicIp}")
            }
            lastIp[key] = card.publicIp
        }

        if (key == KEY_CELL) Store.cell = card else Store.wifi = card
        Store.changed()
        updateNotification()
    }

    private fun updateNotification() {
        val text = "Mobile: ${Store.cell.label} ${Store.cell.publicIp}  |  Wi-Fi: ${Store.wifi.label} ${Store.wifi.publicIp}"
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL)
            .setContentTitle("IP Split Tracker")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

    private fun startAsForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "IP tracking", NotificationManager.IMPORTANCE_LOW)
        )
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }
}
