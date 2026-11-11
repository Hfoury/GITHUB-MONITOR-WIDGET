package fr.sibellacreation.githubmonitor

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews

class MonitorWidget:AppWidgetProvider(){
 override fun onUpdate(context:Context,manager:AppWidgetManager,ids:IntArray){ids.forEach{update(context,manager,it)}}
 override fun onAppWidgetOptionsChanged(context:Context,manager:AppWidgetManager,id:Int,newOptions:Bundle){super.onAppWidgetOptionsChanged(context,manager,id,newOptions);update(context,manager,id)}
 override fun onReceive(context:Context,intent:Intent){super.onReceive(context,intent);if(intent.action==ACTION_REFRESH)MonitorService.start(context);if(intent.action==AppWidgetManager.ACTION_APPWIDGET_UPDATE){val m=AppWidgetManager.getInstance(context);m.getAppWidgetIds(ComponentName(context,MonitorWidget::class.java)).forEach{update(context,m,it)}}}
 companion object{
  const val ACTION_REFRESH="fr.sibellacreation.githubmonitor.REFRESH"
  fun updateAll(context:Context){val m=AppWidgetManager.getInstance(context);m.getAppWidgetIds(ComponentName(context,MonitorWidget::class.java)).forEach{update(context,m,it)}}
  private fun update(context:Context,m:AppWidgetManager,id:Int){
   val p=context.getSharedPreferences(MonitorService.PREFS,Context.MODE_PRIVATE)
   val options=m.getAppWidgetOptions(id)
   val width=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,280)
   val height=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,240)
   val compact=width<270||height<230
   val expanded=width>=330&&height>=300
   val v=RemoteViews(context.packageName,R.layout.widget)
   v.setTextViewTextSize(R.id.widget_title,TypedValue.COMPLEX_UNIT_SP,if(compact)14f else if(expanded)19f else 16f)
   v.setTextViewTextSize(R.id.updated,TypedValue.COMPLEX_UNIT_SP,if(compact)9f else 10f)
   v.setTextViewTextSize(R.id.monitor_state,TypedValue.COMPLEX_UNIT_SP,if(compact)9f else if(expanded)12f else 10f)
   v.setTextViewTextSize(R.id.refresh,TypedValue.COMPLEX_UNIT_SP,if(compact)10f else 12f)
   val pad=if(compact)6 else if(expanded)16 else 10
   v.setViewPadding(R.id.widget_root,pad,pad,pad,pad)
   val defaults=listOf("BOULANGERIES-FRANCE","STATIONS-TOTAL-FRANCE","iriscope")
   val selected=(p.getStringSet("selected_repos",null)?.toList()?:defaults).sorted()
   val slots=listOf(
    intArrayOf(R.id.repo1_card,R.id.repo1_text,R.id.repo1_progress),
    intArrayOf(R.id.repo2_card,R.id.repo2_text,R.id.repo2_progress),
    intArrayOf(R.id.repo3_card,R.id.repo3_text,R.id.repo3_progress),
    intArrayOf(R.id.repo4_card,R.id.repo4_text,R.id.repo4_progress),
    intArrayOf(R.id.repo5_card,R.id.repo5_text,R.id.repo5_progress)
   )
   slots.forEachIndexed{index,slot->
    if(index<selected.size){
     v.setViewVisibility(slot[0],View.VISIBLE)
     val repo=selected[index]
     val key=repoKey(repo)
     setRepo(context,v,p,key,repo,slot[1],slot[2],slot[0],101+index)
    }else v.setViewVisibility(slot[0],View.GONE)
   }
   v.setTextViewText(R.id.updated,p.getString("updated","En attente d'actualisation"))
   v.setTextViewText(R.id.monitor_state,p.getString("monitor","Surveillance inactive"))
   val refresh=Intent(context,MonitorWidget::class.java).setAction(ACTION_REFRESH)
   val pi=PendingIntent.getBroadcast(context,1001,refresh,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
   v.setOnClickPendingIntent(R.id.refresh,pi)
   m.updateAppWidget(id,v)
  }
  private fun setRepo(context:Context,v:RemoteViews,p:android.content.SharedPreferences,key:String,repo:String,textId:Int,barId:Int,cardId:Int,code:Int){
   val label=p.getString("label_$key",repo)?:repo
   val status=p.getString(key,"—")?:"—"
   val progress=p.getInt("progress_$key",if(status.startsWith("TERMINÉ"))100 else 0).coerceIn(0,100)
   v.setTextViewText(textId,"$label  •  $status  •  $progress%")
   v.setProgressBar(barId,100,progress,false)
   val url=p.getString("target_url_$key","")?.takeIf{it.startsWith("https://github.com/")}?:"https://github.com/Hfoury/$repo/actions"
   val open=Intent(Intent.ACTION_VIEW,Uri.parse(url))
   val click=PendingIntent.getActivity(context,code,open,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
   v.setOnClickPendingIntent(cardId,click)
  }
  private fun repoKey(repo:String)=when(repo){"BOULANGERIES-FRANCE"->"bakeries";"STATIONS-TOTAL-FRANCE"->"stations";"iriscope"->"iriscope";else->"repo_"+repo.lowercase().replace(Regex("[^a-z0-9]+"),"_").trim('_')}
 }
}
