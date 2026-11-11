package fr.sibellacreation.githubmonitor

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews

class MonitorWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, manager, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) MonitorService.start(context)
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, MonitorWidget::class.java))
                .forEach { update(context, manager, it) }
        }
    }

    companion object {
        const val ACTION_REFRESH = "fr.sibellacreation.githubmonitor.REFRESH"
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, MonitorWidget::class.java))
                .forEach { update(context, manager, it) }
        }
        private fun update(context: Context, manager: AppWidgetManager, id: Int) {
            val prefs = context.getSharedPreferences(MonitorService.PREFS, Context.MODE_PRIVATE)
            val views = RemoteViews(context.packageName, R.layout.widget)
            views.setTextViewText(R.id.bakeries, "Boulangeries-France : " + prefs.getString("bakeries", "—"))
            views.setTextViewText(R.id.stations, "Stations-Total-France : " + prefs.getString("stations", "—"))
            views.setTextViewText(R.id.iriscope, "Iriscope : " + prefs.getString("iriscope", "—"))
            views.setTextViewText(R.id.updated, prefs.getString("updated", "En attente d'actualisation"))
            views.setTextViewText(R.id.monitor_state, prefs.getString("monitor", "Surveillance inactive"))
            val intent = Intent(context, MonitorWidget::class.java).setAction(ACTION_REFRESH)
            val pi = PendingIntent.getBroadcast(context, 1001, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.refresh, pi)
            manager.updateAppWidget(id, views)
        }
    }
}
