package fr.sibellacreation.githubmonitor
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
class MonitorWidget:AppWidgetProvider(){
 override fun onUpdate(context:Context,manager:AppWidgetManager,ids:IntArray){ids.forEach{update(context,manager,it)}}
 override fun onReceive(context:Context,intent:Intent){super.onReceive(context,intent);if(intent.action==ACTION_REFRESH)MonitorService.start(context);if(intent.action==AppWidgetManager.ACTION_APPWIDGET_UPDATE){val m=AppWidgetManager.getInstance(context);m.getAppWidgetIds(ComponentName(context,MonitorWidget::class.java)).forEach{update(context,m,it)}}}
 companion object{
  const val ACTION_REFRESH="fr.sibellacreation.githubmonitor.REFRESH"
  fun updateAll(context:Context){val m=AppWidgetManager.getInstance(context);m.getAppWidgetIds(ComponentName(context,MonitorWidget::class.java)).forEach{update(context,m,it)}}
  private fun update(context:Context,m:AppWidgetManager,id:Int){
   val p=context.getSharedPreferences(MonitorService.PREFS,Context.MODE_PRIVATE);val v=RemoteViews(context.packageName,R.layout.widget)
   setRepo(context,v,p,"bakeries","Boulangeries-France",R.id.bakeries,R.id.bakeries_progress,R.id.bakeries_card,101,"BOULANGERIES-FRANCE")
   setRepo(context,v,p,"stations","Stations-Total-France",R.id.stations,R.id.stations_progress,R.id.stations_card,102,"STATIONS-TOTAL-FRANCE")
   setRepo(context,v,p,"iriscope","Iriscope",R.id.iriscope,R.id.iriscope_progress,R.id.iriscope_card,103,"iriscope")
   v.setTextViewText(R.id.updated,p.getString("updated","En attente d'actualisation"));v.setTextViewText(R.id.monitor_state,p.getString("monitor","Surveillance inactive"))
   val i=Intent(context,MonitorWidget::class.java).setAction(ACTION_REFRESH);val pi=PendingIntent.getBroadcast(context,1001,i,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE);v.setOnClickPendingIntent(R.id.refresh,pi);m.updateAppWidget(id,v)
  }
  private fun setRepo(context:Context,v:RemoteViews,p:android.content.SharedPreferences,key:String,label:String,textId:Int,barId:Int,cardId:Int,code:Int,repo:String){
   val status=p.getString(key,"—")?:"—";val progress=p.getInt("progress_$key",if(status.startsWith("TERMINÉ"))100 else 0).coerceIn(0,100)
   v.setTextViewText(textId,"$label • $status • $progress%");v.setProgressBar(barId,100,progress,false)
   val url=p.getString("target_url_$key","")?.takeIf{it.startsWith("https://github.com/")}?:"https://github.com/Hfoury/$repo/actions"
   val open=Intent(Intent.ACTION_VIEW,Uri.parse(url));val click=PendingIntent.getActivity(context,code,open,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE);v.setOnClickPendingIntent(cardId,click)
  }
 }
}