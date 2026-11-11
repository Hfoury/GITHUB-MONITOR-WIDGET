package fr.sibellacreation.githubmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(NOTIFICATION_ID, foregroundNotification("Démarrage de la surveillance"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (monitorJob?.isActive != true) {
            monitorJob = scope.launch {
                var keepChecking = true
                while (isActive && keepChecking) {
                    val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
                    val previousStates = repositories.associate { it.second to prefs.getString("state_" + it.second, null) }
                    val results = repositories.map { (repo, key, label) ->
                        try {
                            val state = readLatest(repo)
                            save(key, state.first)
                            Triple(label, key, state.first to state.second)
                        } catch (e: Exception) {
                            save(key, "Erreur réseau")
                            Triple(label, key, "Erreur réseau" to true)
                        }
                    }

                    val newlyCompleted = results.filter { result ->
                        val previous = previousStates[result.second]
                        previous != null && isActiveState(previous) && !result.third.second
                    }
                    sendCompletionAlerts(newlyCompleted.map { it.first })

                    val active = results.any { it.third.second }
                    val allCompletedBefore = prefs.getBoolean("all_completed", false)
                    val initialized = prefs.getBoolean("initialized", false)
                    val allCompletedNow = !active
                    if (initialized && !allCompletedBefore && allCompletedNow) {
                        sendGlobalCompletionAlert()
                    }
                    prefs.edit()
                        .putBoolean("all_completed", allCompletedNow)
                        .putBoolean("initialized", true)
                        .apply()

                    val summary = results.joinToString { "${it.first}: ${it.third.first}" }
                    updateStatus(summary, active)
                    if (active) {
                        delay(120_000)
                    } else {
                        saveMonitor("Terminé — actualisation manuelle nécessaire")
                        MonitorWidget.updateAll(this@MonitorService)
                        keepChecking = false
                    }
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun readLatest(repo: String): Pair<String, Boolean> = withContext(Dispatchers.IO) {
        val url = URL("https://api.github.com/repos/Hfoury/$repo/actions/runs?per_page=20")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "Suivi-GitHub-Android")
        }
        try {
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val runs = JSONObject(body).optJSONArray("workflow_runs")
            if (runs == null || runs.length() == 0) return@withContext "Aucune exécution" to false
            var latest = runs.getJSONObject(0)
            for (i in 0 until runs.length()) {
                val run = runs.getJSONObject(i)
                if (run.optString("status") != "completed") { latest = run; break }
            }
            val status = latest.optString("status")
            val conclusion = latest.optString("conclusion", "")
            when (status) {
                "in_progress" -> "EN COURS" to true
                "queued", "pending", "requested", "waiting" -> "EN ATTENTE" to true
                "completed" -> ("TERMINÉ • " + (if (conclusion.isBlank() || conclusion == "null") "INCONNU" else conclusion.uppercase(Locale.ROOT))) to false
                else -> "ÉTAT : $status" to true
            }
        } finally { connection.disconnect() }
    }

    private fun isActiveState(value: String?): Boolean =
        value == "EN COURS" || value == "EN ATTENTE" || value == "Erreur réseau" || value?.startsWith("ÉTAT :") == true

    private fun sendCompletionAlerts(completed: List<String>) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getString("alert_mode", "all") != "each" || completed.isEmpty()) return
        completed.forEachIndexed { index, name ->
            showAlert(200 + index, "Projet terminé", "$name a terminé son traitement.")
        }
    }

    private fun sendGlobalCompletionAlert() {
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getString("alert_mode", "all") == "all") {
            showAlert(300, "Tous les projets sont terminés", "Les trois dépôts GitHub ont terminé leurs traitements.")
        }
    }

    private fun showAlert(id: Int, title: String, message: String) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val mode = prefs.getString("alert_sound", "vibrate") ?: "vibrate"
        val channel = when (mode) {
            "sound" -> SOUND_CHANNEL
            "silent" -> SILENT_CHANNEL
            else -> VIBRATE_CHANNEL
        }
        val notification = NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setPriority(if (mode == "silent") NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT)
            .build()
        getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    private fun save(key: String, value: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(key, value).apply()
    }
    private fun saveMonitor(value: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("monitor", value).putString("updated", timestamp()).apply()
    }
    private fun updateStatus(summary: String, active: Boolean) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("monitor", if (active) "Surveillance active • contrôle dans 2 min" else "Tous les workflows sont terminés")
            .putString("updated", "Mis à jour à " + timestamp()).apply()
        MonitorWidget.updateAll(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, foregroundNotification(if (active) "Surveillance active" else "Traitements terminés"))
    }
    private fun timestamp() = SimpleDateFormat("HH:mm:ss", Locale.FRANCE).format(Date())

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(FOREGROUND_CHANNEL, "Suivi en arrière-plan", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(SOUND_CHANNEL, "Fin de projet — son et vibration", NotificationManager.IMPORTANCE_DEFAULT))
            manager.createNotificationChannel(NotificationChannel(VIBRATE_CHANNEL, "Fin de projet — vibration seule", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 150, 250)
            })
            manager.createNotificationChannel(NotificationChannel(SILENT_CHANNEL, "Fin de projet — silencieuse", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
            })
        }
    }

    private fun foregroundNotification(message: String): Notification =
        NotificationCompat.Builder(this, FOREGROUND_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Suivi GitHub")
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        const val PREFS = "github_monitor"
        private const val FOREGROUND_CHANNEL = "github_monitor_foreground"
        private const val SOUND_CHANNEL = "github_monitor_alert_sound"
        private const val VIBRATE_CHANNEL = "github_monitor_alert_vibrate"
        private const val SILENT_CHANNEL = "github_monitor_alert_silent"
        private const val NOTIFICATION_ID = 41
        private val repositories = listOf(
            Triple("BOULANGERIES-FRANCE", "bakeries", "Boulangeries"),
            Triple("STATIONS-TOTAL-FRANCE", "stations", "Stations Total"),
            Triple("iriscope", "iriscope", "Iriscope")
        )
        fun start(context: Context) {
            val intent = Intent(context, MonitorService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}
