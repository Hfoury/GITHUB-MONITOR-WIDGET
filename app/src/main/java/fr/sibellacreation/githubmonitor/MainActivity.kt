package fr.sibellacreation.githubmonitor

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences(MonitorService.PREFS, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 42, 32, 32)
        }
        layout.addView(TextView(this).apply {
            text = "Suivi GitHub"
            textSize = 24f
            setPadding(0, 0, 0, 12)
        })
        layout.addView(TextView(this).apply {
            text = "Ajoute le grand widget à ton écran d'accueil. Il suit les traitements de tes trois dépôts."
            textSize = 15f
            setPadding(0, 0, 0, 18)
        })

        layout.addView(TextView(this).apply { text = "ALERTES DE FIN DE TRAITEMENT"; textSize = 14f; setPadding(0, 8, 0, 6) })
        val alertGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val alertOptions = listOf(
            "none" to "Aucune notification",
            "each" to "Une notification à chaque projet terminé",
            "all" to "Une seule notification quand les 3 projets sont terminés"
        )
        alertOptions.forEach { (value, label) ->
            alertGroup.addView(RadioButton(this).apply { text = label; tag = value; isChecked = prefs.getString("alert_mode", "all") == value })
        }
        alertGroup.setOnCheckedChangeListener { group, checkedId ->
            val selected = group.findViewById<RadioButton>(checkedId)
            prefs.edit().putString("alert_mode", selected?.tag as? String ?: "all").apply()
        }
        layout.addView(alertGroup)

        layout.addView(TextView(this).apply { text = "TYPE D'ALERTE"; textSize = 14f; setPadding(0, 18, 0, 6) })
        val soundGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val soundOptions = listOf(
            "sound" to "Son + vibration",
            "vibrate" to "Vibration seule (sans son)",
            "silent" to "Silencieuse (sans son ni vibration)"
        )
        soundOptions.forEach { (value, label) ->
            soundGroup.addView(RadioButton(this).apply { text = label; tag = value; isChecked = prefs.getString("alert_sound", "vibrate") == value })
        }
        soundGroup.setOnCheckedChangeListener { group, checkedId ->
            val selected = group.findViewById<RadioButton>(checkedId)
            prefs.edit().putString("alert_sound", selected?.tag as? String ?: "vibrate").apply()
        }
        layout.addView(soundGroup)

        layout.addView(TextView(this).apply {
            text = "La vibration seule fonctionne avec le téléphone en mode discret si Android autorise les vibrations de cette application. Les réglages Ne pas déranger peuvent toutefois les bloquer."
            textSize = 12f
            setPadding(0, 16, 0, 18)
        })
        layout.addView(Button(this).apply {
            text = "Démarrer la surveillance"
            setOnClickListener { requestAndStart() }
        })
        layout.addView(Button(this).apply {
            text = "Actualiser le widget"
            setOnClickListener { MonitorService.start(this@MainActivity) }
        })
        setContentView(layout)
    }

    private fun requestAndStart() {
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
}
