package fr.sibellacreation.githubmonitor

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.Locale

private data class RepoState(
    val label: String,
    val key: String,
    val status: String,
    val active: Boolean,
    val progress: Int,
    val runUrl: String,
    val targetUrl: String,
    val runId: Long = 0L,
    val conclusion: String = "",
    val createdAt: String = ""
)

class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(NOTIFICATION_ID, foregroundNotification("Démarrage de la surveillance"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (monitorJob?.isActive != true) monitorJob = scope.launch {
            var keep = true
            while (isActive && keep) {
                val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
                val selected = prefs.getStringSet("selected_repos", null)
                val configs = (selected ?: DEFAULT_REPOS).sorted().map { repo ->
                    RepoConfig(repo, repoKey(repo), repoLabel(repo))
                }
                if (configs.isEmpty()) {
                    saveMonitor("Aucun dépôt sélectionné")
                    MonitorWidget.updateAll(this@MonitorService)
                    keep = false
                    continue
                }

                val token = TokenVault.read(this@MonitorService)
                val previous = configs.associate { it.key to prefs.getString(it.key, null) }
                val results = configs.map { cfg ->
                    try {
                        readLatest(cfg.repo, cfg.key, cfg.label, token).also {
                            saveRepoState(it)
                            // Une réponse valide met fin à l'épisode d'indisponibilité réseau.
                            prefs.edit().remove("network_incident_${it.key}").apply()
                        }
                    } catch (e: Exception) {
                        val reason = (e.message ?: e.javaClass.simpleName).take(180)
                        val state = RepoState(
                            cfg.label, cfg.key, "Erreur réseau", true,
                            prefs.getInt("progress_${cfg.key}", 0),
                            prefs.getString("run_url_${cfg.key}", "") ?: "",
                            prefs.getString("target_url_${cfg.key}", "") ?: "",
                            prefs.getLong("run_id_${cfg.key}", 0L),
                            "", prefs.getString("created_at_${cfg.key}", "") ?: ""
                        )
                        saveRepoState(state)
                        sendIncidentOnce(
                            "network_incident_${cfg.key}", "network",
                            "Serveur inaccessible — ${cfg.label}",
                            "Impossible de joindre GitHub pour ${cfg.label}. Détail : $reason. La surveillance continue et réessaiera dans 2 minutes.",
                            cfg.key
                        )
                        state
                    }
                }

                // Les incidents sont indépendants des alertes de fin normale.
                results.forEach { state ->
                    if (state.runId > 0L && isProblemConclusion(state.conclusion)) {
                        val marker = "${state.runId}:${state.conclusion}"
                        sendIncidentOnce(
                            "terminal_incident_${state.key}", marker,
                            "Traitement interrompu — ${state.label}",
                            "Le workflow de ${state.label} s'est terminé avec le statut ${state.conclusion.uppercase(Locale.ROOT)}. Consulte les journaux : ${state.runUrl}",
                            state.key
                        )
                    }
                    if (state.active && state.runId > 0L && isOverdue(state.createdAt)) {
                        val marker = "timeout:${state.runId}"
                        sendIncidentOnce(
                            "timeout_incident_${state.key}", marker,
                            "Traitement anormalement long — ${state.label}",
                            "Le workflow de ${state.label} est toujours actif après 60 minutes. Vérifie sa progression : ${state.runUrl}",
                            state.key
                        )
                    }
                }

                val done = results.filter { state ->
                    previous[state.key]?.let { isActiveState(it) && !state.active } == true &&
                        state.conclusion.equals("success", ignoreCase = true)
                }
                sendCompletionAlerts(done.map { it.label })

                val active = results.any { it.active }
                val allSucceeded = results.isNotEmpty() && results.all {
                    it.conclusion.equals("success", ignoreCase = true)
                }
                val allBefore = prefs.getBoolean("all_completed", false)
                if (prefs.getBoolean("initialized", false) && !allBefore && !active && allSucceeded) {
                    sendGlobalCompletionAlert(configs.size)
                }
                prefs.edit()
                    .putBoolean("all_completed", !active)
                    .putBoolean("initialized", true)
                    .apply()

                updateStatus(
                    results.joinToString { "${it.label}: ${it.status} (${it.progress}%)" },
                    active
                )
                if (active) {
                    delay(120_000)
                } else {
                    saveMonitor(
                        if (allSucceeded) "Tous les traitements sont terminés avec succès"
                        else "Traitements terminés — au moins un incident à vérifier"
                    )
                    MonitorWidget.updateAll(this@MonitorService)
                    keep = false
                }
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private suspend fun readLatest(
        repo: String, key: String, label: String, token: String?
    ): RepoState = withContext(Dispatchers.IO) {
        val runs = getJson(
            "https://api.github.com/repos/Hfoury/$repo/actions/runs?per_page=20", token
        ).optJSONArray("workflow_runs")
        if (runs == null || runs.length() == 0) {
            val url = "https://github.com/Hfoury/$repo/actions"
            return@withContext RepoState(label, key, "Aucune exécution", false, 0, url, url)
        }

        // Priorité au traitement encore actif pour ne pas arrêter la surveillance trop tôt.
        var run = runs.getJSONObject(0)
        for (i in 0 until runs.length()) {
            val candidate = runs.getJSONObject(i)
            if (candidate.optString("status") != "completed") {
                run = candidate
                break
            }
        }
        val id = run.optLong("id")
        val runUrl = run.optString("html_url", "https://github.com/Hfoury/$repo/actions")
        val raw = run.optString("status")
        val conclusion = run.optString("conclusion", "").takeUnless { it == "null" } ?: ""
        val createdAt = run.optString("created_at", "")
        val active = raw != "completed"
        val progress = if (raw == "completed") 100
            else if (raw in listOf("queued", "pending", "requested", "waiting")) 0
            else readProgress(repo, id, token)
        val status = when (raw) {
            "in_progress" -> "EN COURS"
            "queued", "pending", "requested", "waiting" -> "EN ATTENTE"
            "completed" -> "TERMINÉ • " +
                (if (conclusion.isBlank()) "INCONNU" else conclusion.uppercase(Locale.ROOT))
            else -> "ÉTAT : $raw"
        }
        var target = runUrl
        if (raw == "completed" && id > 0L) {
            val artifact = readArtifactId(repo, id, token)
            if (artifact > 0L) target =
                "https://github.com/Hfoury/$repo/actions/runs/$id/artifacts/$artifact"
        }
        RepoState(label, key, status, active, progress.coerceIn(0, 100),
            runUrl, target, id, conclusion, createdAt)
    }

    private suspend fun readProgress(repo: String, id: Long, token: String?): Int =
        withContext(Dispatchers.IO) {
            try {
                val jobs = getJson(
                    "https://api.github.com/repos/Hfoury/$repo/actions/runs/$id/jobs?per_page=100",
                    token
                ).optJSONArray("jobs") ?: return@withContext 0
                var total = 0
                var done = 0
                var jobTotal = 0
                var jobDone = 0
                for (i in 0 until jobs.length()) {
                    val job = jobs.getJSONObject(i)
                    jobTotal++
                    if (job.optString("status") == "completed") jobDone++
                    val steps = job.optJSONArray("steps") ?: continue
                    for (j in 0 until steps.length()) {
                        total++
                        val step = steps.getJSONObject(j)
                        if (step.optString("status") == "completed" ||
                            (step.optString("conclusion").isNotBlank() &&
                                step.optString("conclusion") != "null")) done++
                    }
                }
                if (total > 0) done * 100 / total
                else if (jobTotal > 0) jobDone * 100 / jobTotal
                else 0
            } catch (e: Exception) {
                0
            }
        }

    private suspend fun readArtifactId(repo: String, id: Long, token: String?): Long =
        withContext(Dispatchers.IO) {
            try {
                val artifacts = getJson(
                    "https://api.github.com/repos/Hfoury/$repo/actions/runs/$id/artifacts?per_page=100",
                    token
                ).optJSONArray("artifacts") ?: return@withContext 0L
                for (i in 0 until artifacts.length()) {
                    val item = artifacts.getJSONObject(i)
                    if (!item.optBoolean("expired", false)) return@withContext item.optLong("id")
                }
                0L
            } catch (e: Exception) {
                0L
            }
        }

    private fun getJson(address: String, token: String?): JSONObject {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "Suivi-GitHub-Android")
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("GitHub HTTP $code")
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun saveRepoState(state: RepoState) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(state.key, state.status)
            .putInt("progress_${state.key}", state.progress)
            .putString("label_${state.key}", state.label)
            .putString("run_url_${state.key}", state.runUrl)
            .putString("target_url_${state.key}", state.targetUrl)
            .putLong("run_id_${state.key}", state.runId)
            .putString("conclusion_${state.key}", state.conclusion)
            .putString("created_at_${state.key}", state.createdAt)
            .apply()
    }

    private fun isActiveState(value: String?) =
        value == "EN COURS" || value == "EN ATTENTE" ||
            value == "Erreur réseau" || value?.startsWith("ÉTAT :") == true

    private fun isProblemConclusion(value: String): Boolean =
        value.lowercase(Locale.ROOT) in setOf(
            "failure", "cancelled", "timed_out", "startup_failure", "action_required", "stale"
        )

    private fun isOverdue(createdAt: String): Boolean {
        if (createdAt.isBlank()) return false
        return try {
            Duration.between(Instant.parse(createdAt), Instant.now()).toMinutes() >= MAX_RUN_MINUTES
        } catch (e: Exception) {
            false
        }
    }

    private fun sendIncidentOnce(
        preferenceKey: String, marker: String, title: String, message: String, repoKey: String
    ) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean("incident_alerts", true)) return
        if (prefs.getString(preferenceKey, null) == marker) return
        prefs.edit().putString(preferenceKey, marker).apply()
        showAlert(notificationId(repoKey), title, message)
    }

    private fun notificationId(key: String) = 5000 + (key.hashCode() and 0x7fffffff) % 10000

    private fun sendCompletionAlerts(names: List<String>) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getString("alert_mode", "all") != "each") return
        names.forEachIndexed { index, name ->
            showAlert(200 + index, "Projet terminé", "$name a terminé son traitement avec succès.")
        }
    }

    private fun sendGlobalCompletionAlert(count: Int) {
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getString("alert_mode", "all") == "all") {
            showAlert(
                300, "Tous les projets sont terminés",
                "Les $count dépôt(s) sélectionné(s) ont terminé leurs traitements avec succès."
            )
        }
    }

    private fun showAlert(id: Int, title: String, message: String) {
        val mode = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString("alert_sound", "vibrate") ?: "vibrate"
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
            .setPriority(if (mode == "silent") NotificationCompat.PRIORITY_LOW
                else NotificationCompat.PRIORITY_DEFAULT)
            .build()
        getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    private fun saveMonitor(value: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("monitor", value).putString("updated", timestamp()).apply()
    }

    private fun updateStatus(summary: String, active: Boolean) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("monitor", if (active) "Surveillance active • contrôle dans 2 min"
                else "Tous les workflows sont terminés")
            .putString("updated", "Mis à jour à " + timestamp())
            .apply()
        MonitorWidget.updateAll(this)
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            foregroundNotification(if (active) "Surveillance active" else "Traitements terminés")
        )
    }

    private fun timestamp() = SimpleDateFormat("HH:mm:ss", Locale.FRANCE).format(Date())

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(FOREGROUND_CHANNEL, "Suivi en arrière-plan",
                    NotificationManager.IMPORTANCE_LOW)
            )
            manager.createNotificationChannel(
                NotificationChannel(SOUND_CHANNEL, "Alertes — son et vibration",
                    NotificationManager.IMPORTANCE_DEFAULT)
            )
            manager.createNotificationChannel(
                NotificationChannel(VIBRATE_CHANNEL, "Alertes — vibration seule",
                    NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 250, 150, 250)
                }
            )
            manager.createNotificationChannel(
                NotificationChannel(SILENT_CHANNEL, "Alertes — silencieuses",
                    NotificationManager.IMPORTANCE_LOW).apply {
                    setSound(null, null)
                    enableVibration(false)
                }
            )
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
    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private data class RepoConfig(val repo: String, val key: String, val label: String)

    private fun repoKey(repo: String) = when (repo) {
        "BOULANGERIES-FRANCE" -> "bakeries"
        "STATIONS-TOTAL-FRANCE" -> "stations"
        "iriscope" -> "iriscope"
        else -> "repo_" + repo.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "_").trim('_')
    }

    private fun repoLabel(repo: String) = when (repo) {
        "BOULANGERIES-FRANCE" -> "Boulangeries"
        "STATIONS-TOTAL-FRANCE" -> "Stations Total"
        "iriscope" -> "Iriscope"
        else -> repo
    }

    companion object {
        const val PREFS = "github_monitor"
        private const val FOREGROUND_CHANNEL = "github_monitor_foreground"
        private const val SOUND_CHANNEL = "github_monitor_alert_sound"
        private const val VIBRATE_CHANNEL = "github_monitor_alert_vibrate"
        private const val SILENT_CHANNEL = "github_monitor_alert_silent"
        private const val NOTIFICATION_ID = 41
        private const val MAX_RUN_MINUTES = 60L
        private val DEFAULT_REPOS = setOf(
            "BOULANGERIES-FRANCE", "STATIONS-TOTAL-FRANCE", "iriscope"
        )

        fun start(context: Context) {
            val intent = Intent(context, MonitorService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }
}
