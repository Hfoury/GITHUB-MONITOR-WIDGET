package fr.sibellacreation.githubmonitor

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        layout.addView(TextView(this).apply {
            text = "Suivi GitHub\n\nAjoute le widget à ton écran d'accueil, puis utilise Actualiser pour lancer la surveillance."
            textSize = 18f
        })
        layout.addView(Button(this).apply {
            text = "Démarrer la surveillance"
            setOnClickListener { requestAndStart() }
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
        if (requestCode == 22 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) MonitorService.start(this)
    }
}
