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
import java.util.Date
import java.util.Locale

private data class RepoState(val label:String,val key:String,val status:String,val active:Boolean,val progress:Int,val runUrl:String,val targetUrl:String)

class MonitorService : Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var monitorJob:Job?=null
    override fun onCreate(){super.onCreate();createChannels();startForeground(NOTIFICATION_ID,foregroundNotification("Démarrage de la surveillance"))}
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(monitorJob?.isActive!=true) monitorJob=scope.launch {
            var keep=true
            while(isActive&&keep){
                val prefs=getSharedPreferences(PREFS,MODE_PRIVATE)
                val selected=prefs.getStringSet("selected_repos",null)
                val configs=(selected ?: DEFAULT_REPOS).sorted().map{repo->RepoConfig(repo,repoKey(repo),repoLabel(repo))}
                if(configs.isEmpty()){
                    saveMonitor("Aucun dépôt sélectionné")
                    MonitorWidget.updateAll(this@MonitorService)
                    keep=false
                    continue
                }
                val token=TokenVault.read(this@MonitorService)
                val previous=configs.associate{it.key to prefs.getString(it.key,null)}
                val results=configs.map{cfg->
                    try{readLatest(cfg.repo,cfg.key,cfg.label,token).also{saveRepoState(it)}}
                    catch(e:Exception){RepoState(cfg.label,cfg.key,"Erreur réseau",true,0,prefs.getString("run_url_\${cfg.key}","")?:"",prefs.getString("target_url_\${cfg.key}","")?:"").also{saveRepoState(it)}}
                }
                val done=results.filter{r->previous[r.key]?.let{isActiveState(it)&&!r.active}?:false}
                sendCompletionAlerts(done.map{it.label})
                val active=results.any{it.active}
                val allBefore=prefs.getBoolean("all_completed",false)
                if(prefs.getBoolean("initialized",false)&&!allBefore&&!active)sendGlobalCompletionAlert(configs.size)
                prefs.edit().putBoolean("all_completed",!active).putBoolean("initialized",true).apply()
                updateStatus(results.joinToString{ "\${it.label}: \${it.status} (\${it.progress}%)" },active)
                if(active)delay(120_000) else {saveMonitor("Tous les dépôts sélectionnés sont terminés");MonitorWidget.updateAll(this@MonitorService);keep=false}
            }
            stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
        }
        return START_NOT_STICKY
    }

    private suspend fun readLatest(repo:String,key:String,label:String,token:String?):RepoState=withContext(Dispatchers.IO){
        val runs=getJson("https://api.github.com/repos/Hfoury/$repo/actions/runs?per_page=20",token).optJSONArray("workflow_runs")
        if(runs==null||runs.length()==0){val u="https://github.com/Hfoury/$repo/actions";return@withContext RepoState(label,key,"Aucune exécution",false,0,u,u)}
        var run=runs.getJSONObject(0)
        for(i in 0 until runs.length()){val candidate=runs.getJSONObject(i);if(candidate.optString("status")!="completed"){run=candidate;break}}
        val id=run.optLong("id");val runUrl=run.optString("html_url","https://github.com/Hfoury/$repo/actions")
        val raw=run.optString("status");val conclusion=run.optString("conclusion","")
        val active=raw!="completed"
        val progress=if(raw=="completed")100 else if(raw in listOf("queued","pending","requested","waiting"))0 else readProgress(repo,id,token)
        val status=when(raw){"in_progress"->"EN COURS";"queued","pending","requested","waiting"->"EN ATTENTE";"completed"->"TERMINÉ • "+(if(conclusion.isBlank()||conclusion=="null")"INCONNU" else conclusion.uppercase(Locale.ROOT));else->"ÉTAT : $raw"}
        var target=runUrl
        if(raw=="completed"&&id>0){val artifact=readArtifactId(repo,id,token);if(artifact>0)target="https://github.com/Hfoury/$repo/actions/runs/$id/artifacts/$artifact"}
        RepoState(label,key,status,active,progress.coerceIn(0,100),runUrl,target)
    }

    private suspend fun readProgress(repo:String,id:Long,token:String?):Int=withContext(Dispatchers.IO){
        try{
            val jobs=getJson("https://api.github.com/repos/Hfoury/$repo/actions/runs/$id/jobs?per_page=100",token).optJSONArray("jobs")?:return@withContext 0
            var total=0;var done=0;var jobTotal=0;var jobDone=0
            for(i in 0 until jobs.length()){
                val job=jobs.getJSONObject(i);jobTotal++;if(job.optString("status")=="completed")jobDone++
                val steps=job.optJSONArray("steps")?:continue
                for(j in 0 until steps.length()){total++;val s=steps.getJSONObject(j);if(s.optString("status")=="completed"||(s.optString("conclusion").isNotBlank()&&s.optString("conclusion")!="null"))done++}
            }
            if(total>0)done*100/total else if(jobTotal>0)jobDone*100/jobTotal else 0
        }catch(e:Exception){0}
    }

    private suspend fun readArtifactId(repo:String,id:Long,token:String?):Long=withContext(Dispatchers.IO){
        try{val a=getJson("https://api.github.com/repos/Hfoury/$repo/actions/runs/$id/artifacts?per_page=100",token).optJSONArray("artifacts")?:return@withContext 0L
            for(i in 0 until a.length()){val item=a.getJSONObject(i);if(!item.optBoolean("expired",false))return@withContext item.optLong("id")};0L
        }catch(e:Exception){0L}
    }
    private fun getJson(address:String,token:String?):JSONObject{
        val c=(URL(address).openConnection() as HttpURLConnection).apply{
            connectTimeout=15000;readTimeout=15000
            setRequestProperty("Accept","application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version","2022-11-28")
            setRequestProperty("User-Agent","Suivi-GitHub-Android")
            if(!token.isNullOrBlank())setRequestProperty("Authorization","Bearer $token")
        }
        return try{
            val code=c.responseCode
            val stream=if(code in 200..299)c.inputStream else c.errorStream
            val body=stream?.bufferedReader()?.use{it.readText()}?:""
            if(code !in 200..299)throw IllegalStateException("GitHub HTTP $code")
            JSONObject(body)
        }finally{c.disconnect()}
    }
    private fun saveRepoState(s:RepoState){getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString(s.key,s.status).putInt("progress_\${s.key}",s.progress).putString("label_\${s.key}",s.label).putString("run_url_\${s.key}",s.runUrl).putString("target_url_\${s.key}",s.targetUrl).apply()}
    private fun isActiveState(v:String?)=v=="EN COURS"||v=="EN ATTENTE"||v=="Erreur réseau"||v?.startsWith("ÉTAT :")==true
    private fun sendCompletionAlerts(names:List<String>){val p=getSharedPreferences(PREFS,MODE_PRIVATE);if(p.getString("alert_mode","all")!="each")return;names.forEachIndexed{i,n->showAlert(200+i,"Projet terminé","$n a terminé son traitement.")}}
    private fun sendGlobalCompletionAlert(count:Int){if(getSharedPreferences(PREFS,MODE_PRIVATE).getString("alert_mode","all")=="all")showAlert(300,"Tous les projets sont terminés","Les $count dépôt(s) sélectionné(s) ont terminé leurs traitements.")}
    private fun showAlert(id:Int,title:String,message:String){
        val mode=getSharedPreferences(PREFS,MODE_PRIVATE).getString("alert_sound","vibrate")?:"vibrate"
        val channel=when(mode){"sound"->SOUND_CHANNEL;"silent"->SILENT_CHANNEL;else->VIBRATE_CHANNEL}
        val n=NotificationCompat.Builder(this,channel).setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle(title).setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message)).setAutoCancel(true).setPriority(if(mode=="silent")NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT).build()
        getSystemService(NotificationManager::class.java).notify(id,n)
    }
    private fun saveMonitor(v:String){getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString("monitor",v).putString("updated",timestamp()).apply()}
    private fun updateStatus(summary:String,active:Boolean){
        getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString("monitor",if(active)"Surveillance active • contrôle dans 2 min" else "Tous les workflows sont terminés").putString("updated","Mis à jour à "+timestamp()).apply()
        MonitorWidget.updateAll(this);getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,foregroundNotification(if(active)"Surveillance active" else "Traitements terminés"))
    }
    private fun timestamp()=SimpleDateFormat("HH:mm:ss",Locale.FRANCE).format(Date())
    private fun createChannels(){if(Build.VERSION.SDK_INT>=26){val m=getSystemService(NotificationManager::class.java)
        m.createNotificationChannel(NotificationChannel(FOREGROUND_CHANNEL,"Suivi en arrière-plan",NotificationManager.IMPORTANCE_LOW))
        m.createNotificationChannel(NotificationChannel(SOUND_CHANNEL,"Fin de projet — son et vibration",NotificationManager.IMPORTANCE_DEFAULT))
        m.createNotificationChannel(NotificationChannel(VIBRATE_CHANNEL,"Fin de projet — vibration seule",NotificationManager.IMPORTANCE_DEFAULT).apply{setSound(null,null);enableVibration(true);vibrationPattern=longArrayOf(0,250,150,250)})
        m.createNotificationChannel(NotificationChannel(SILENT_CHANNEL,"Fin de projet — silencieuse",NotificationManager.IMPORTANCE_LOW).apply{setSound(null,null);enableVibration(false)})
    }}
    private fun foregroundNotification(message:String):Notification=NotificationCompat.Builder(this,FOREGROUND_CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("Suivi GitHub").setContentText(message).setOngoing(true).setOnlyAlertOnce(true).build()
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onDestroy(){scope.cancel();super.onDestroy()}
    private data class RepoConfig(val repo:String,val key:String,val label:String)
    private fun repoKey(repo:String)=when(repo){"BOULANGERIES-FRANCE"->"bakeries";"STATIONS-TOTAL-FRANCE"->"stations";"iriscope"->"iriscope";else->"repo_"+repo.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"),"_").trim('_')}
    private fun repoLabel(repo:String)=when(repo){"BOULANGERIES-FRANCE"->"Boulangeries";"STATIONS-TOTAL-FRANCE"->"Stations Total";"iriscope"->"Iriscope";else->repo}
    companion object{
        const val PREFS="github_monitor"
        private const val FOREGROUND_CHANNEL="github_monitor_foreground"
        private const val SOUND_CHANNEL="github_monitor_alert_sound"
        private const val VIBRATE_CHANNEL="github_monitor_alert_vibrate"
        private const val SILENT_CHANNEL="github_monitor_alert_silent"
        private const val NOTIFICATION_ID=41
        private val DEFAULT_REPOS=setOf("BOULANGERIES-FRANCE","STATIONS-TOTAL-FRANCE","iriscope")
        fun start(context:Context){val i=Intent(context,MonitorService::class.java);if(Build.VERSION.SDK_INT>=26)context.startForegroundService(i)else context.startService(i)}
    }
}
