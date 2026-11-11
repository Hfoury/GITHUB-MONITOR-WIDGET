package fr.sibellacreation.githubmonitor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

private data class RepoInfo(val name: String, val isPrivate: Boolean, val archived: Boolean)

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences(MonitorService.PREFS, MODE_PRIVATE) }
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var repoContainer: LinearLayout
    private lateinit var repoStatus: TextView
    private lateinit var selectedSummary: TextView
    private lateinit var tokenInput: EditText
    private lateinit var reloadButton: Button
    private val defaultRepos = setOf("BOULANGERIES-FRANCE", "STATIONS-TOTAL-FRANCE", "iriscope")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!prefs.contains("selected_repos")) prefs.edit().putStringSet("selected_repos", defaultRepos).apply()

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 34, 32, 32)
        }
        fun heading(value: String) = TextView(this).apply {
            text = value
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 20, 0, 8)
        }
        fun note(value: String) = TextView(this).apply {
            text = value
            textSize = 13f
            setPadding(0, 4, 0, 8)
        }
        page.addView(TextView(this).apply {
            text = "Suivi GitHub"
            textSize = 25f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        })
        page.addView(note("Configure les alertes et choisis les dépôts GitHub à afficher dans ton widget."))

        page.addView(heading("ALERTES DE FIN DE TRAITEMENT"))
        val alertGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        listOf(
            "none" to "Aucune notification",
            "each" to "Une notification à chaque projet terminé",
            "all" to "Une notification lorsque tous les projets suivis sont terminés"
        ).forEach { (value, label) ->
            alertGroup.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                tag = value
            })
        }
        alertGroup.check(alertGroup.getChildAt(
            listOf("none", "each", "all").indexOf(prefs.getString("alert_mode", "all")).coerceAtLeast(0)
        ).id)
        alertGroup.setOnCheckedChangeListener { group, checkedId ->
            val selected = group.findViewById<RadioButton>(checkedId)
            if (selected != null) prefs.edit().putString("alert_mode", selected.tag as String).apply()
        }
        page.addView(alertGroup)

        page.addView(heading("TYPE D'ALERTE"))
        val soundGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        listOf(
            "sound" to "Son + vibration",
            "vibrate" to "Vibration seule (sans son)",
            "silent" to "Silencieuse (sans son ni vibration)"
        ).forEach { (value, label) ->
            soundGroup.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                tag = value
            })
        }
        soundGroup.check(soundGroup.getChildAt(
            listOf("sound", "vibrate", "silent").indexOf(prefs.getString("alert_sound", "vibrate")).coerceAtLeast(0)
        ).id)
        soundGroup.setOnCheckedChangeListener { group, checkedId ->
            val selected = group.findViewById<RadioButton>(checkedId)
            if (selected != null) prefs.edit().putString("alert_sound", selected.tag as String).apply()
        }
        page.addView(soundGroup)
        page.addView(note("Les notifications restent soumises aux autorisations Android et aux réglages Ne pas déranger."))

        page.addView(heading("ACCÈS À TES DÉPÔTS GITHUB"))
        page.addView(note("Sans jeton, seuls les dépôts publics sont visibles. Pour afficher aussi les dépôts privés, ajoute un jeton personnel en lecture seule. Il sera chiffré sur ce téléphone et ne sera jamais envoyé ailleurs que directement à l'API GitHub."))
        tokenInput = EditText(this).apply {
            hint = "Jeton GitHub (ghp_… ou github_pat_…)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            singleLine = true
        }
        page.addView(tokenInput)
        page.addView(Button(this).apply {
            text = "Créer un jeton GitHub"
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/settings/personal-access-tokens/new"))) }
        })
        page.addView(note("Pour un jeton à granularité fine : propriétaire Hfoury, accès à tous les dépôts, autorisation Metadata en lecture seule et Actions en lecture seule."))
        page.addView(Button(this).apply {
            text = "Enregistrer le jeton et actualiser la liste"
            setOnClickListener {
                val value = tokenInput.text.toString().trim()
                if (value.isNotEmpty()) {
                    if (!value.startsWith("github_pat_") && !value.startsWith("ghp_")) {
                        repoStatus.text = "Format du jeton non reconnu. Vérifie le jeton GitHub."
                    } else {
                        try {
                            TokenVault.save(this@MainActivity, value)
                            tokenInput.text.clear()
                            loadRepositories()
                        } catch (e: Exception) {
                            repoStatus.text = "Impossible de protéger le jeton sur cet appareil : \${e.message}"
                        }
                    }
                } else {
                    loadRepositories()
                }
            }
        })
        page.addView(Button(this).apply {
            text = "Supprimer le jeton enregistré"
            setOnClickListener {
                TokenVault.clear(this@MainActivity)
                loadRepositories()
            }
        })

        page.addView(heading("TOUS LES REPOSITORIES"))
        repoStatus = note("Chargement des dépôts GitHub…")
        page.addView(repoStatus)
        selectedSummary = note("")
        page.addView(selectedSummary)
        repoContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(repoContainer)
        reloadButton = Button(this).apply {
            text = "Actualiser la liste des repositories"
            setOnClickListener { loadRepositories() }
        }
        page.addView(reloadButton)

        page.addView(heading("SURVEILLANCE"))
        page.addView(note("Les dépôts cochés sont suivis par le widget. La vérification passe à 2 minutes lorsqu'au moins un traitement est en cours."))
        page.addView(Button(this).apply {
            text = "Démarrer la surveillance"
            setOnClickListener { requestAndStart() }
        })
        page.addView(Button(this).apply {
            text = "Actualiser le widget"
            setOnClickListener { MonitorService.start(this@MainActivity) }
        })

        val scroll = ScrollView(this).apply { addView(page) }
        setContentView(scroll)
        loadRepositories()
    }

    private fun loadRepositories() {
        repoStatus.text = "Chargement des repositories GitHub…"
        reloadButton.isEnabled = false
        uiScope.launch {
            try {
                val token = TokenVault.read(this@MainActivity)
                val repos = withContext(Dispatchers.IO) { fetchRepositories(token) }
                renderRepositories(repos, token != null)
            } catch (e: Exception) {
                repoStatus.text = "Impossible de récupérer la liste : \${e.message ?: "erreur réseau"}"
            } finally {
                reloadButton.isEnabled = true
            }
        }
    }

    private fun fetchRepositories(token: String?): List<RepoInfo> {
        val result = mutableListOf<RepoInfo>()
        var page = 1
        while (page <= 20) {
            val address = if (token.isNullOrBlank()) {
                "https://api.github.com/users/Hfoury/repos?per_page=100&page=$page&sort=updated"
            } else {
                "https://api.github.com/user/repos?per_page=100&page=$page&sort=updated&affiliation=owner,collaborator,organization_member"
            }
            val connection = (URL(address).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "Suivi-GitHub-Android")
                if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
            }
            val response = try {
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) throw IllegalStateException("GitHub HTTP $code : $body")
                JSONArray(body)
            } finally {
                connection.disconnect()
            }
            if (response.length() == 0) break
            for (i in 0 until response.length()) {
                val item = response.getJSONObject(i)
                result.add(RepoInfo(item.optString("name"), item.optBoolean("private"), item.optBoolean("archived")))
            }
            if (response.length() < 100) break
            page++
        }
        return result.distinctBy { it.name }.sortedBy { it.name.lowercase() }
    }

    private fun renderRepositories(repos: List<RepoInfo>, authenticated: Boolean) {
        repoContainer.removeAllViews()
        if (repos.isEmpty()) {
            repoStatus.text = "Aucun repository retourné par GitHub."
            return
        }
        repoStatus.text = "\${repos.size} repository(s) trouvé(s) • \${repos.count { it.isPrivate }} privé(s) • \${repos.count { !it.isPrivate }} public(s)"
        val selected = prefs.getStringSet("selected_repos", defaultRepos)?.toMutableSet() ?: defaultRepos.toMutableSet()
        repos.forEach { repo ->
            val check = CheckBox(this).apply {
                text = repo.name + (if (repo.isPrivate) "  •  PRIVÉ" else "  •  PUBLIC") + (if (repo.archived) "  •  ARCHIVÉ" else "")
                isChecked = selected.contains(repo.name)
                setOnCheckedChangeListener { _, checked ->
                    val updated = (prefs.getStringSet("selected_repos", defaultRepos) ?: defaultRepos).toMutableSet()
                    if (checked) updated.add(repo.name) else updated.remove(repo.name)
                    prefs.edit().putStringSet("selected_repos", updated).apply()
                    selectedSummary.text = "\${updated.size} dépôt(s) sélectionné(s) pour le widget."
                }
            }
            repoContainer.addView(check)
        }
        selectedSummary.text = "\${selected.size} dépôt(s) sélectionné(s) pour le widget."
        if (!authenticated) {
            repoContainer.addView(TextView(this).apply {
                text = "Les dépôts privés ne peuvent pas apparaître sans jeton personnel. Ajoute un jeton pour les inclure."
                textSize = 12f
                setPadding(0, 10, 0, 10)
            })
        }
    }

    private fun requestAndStart() {
        val selected = prefs.getStringSet("selected_repos", emptySet()) ?: emptySet()
        if (selected.isEmpty()) {
            repoStatus.text = "Coche au moins un repository avant de démarrer la surveillance."
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 22)
        } else {
            MonitorService.start(this)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 22) MonitorService.start(this)
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }
}
