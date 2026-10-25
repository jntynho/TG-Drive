package app.tgdrive

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.tgdrive.library.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** User-initiated work; durable tasks and metadata live in the account database, not this process. */
class TransferService : Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private val lanes=Semaphore(1)
    private var pending=0
    private val processingProfiles=mutableSetOf<String>()
    private val processAgain=mutableSetOf<String>()
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        val profile=intent?.getStringExtra("profile")?:return START_NOT_STICKY
        val process=intent?.action!=SCAN
        if(process&&!processingProfiles.add(profile)){
            processAgain.add(profile)
            return START_NOT_STICKY
        }
        try{
            val initial=LibraryWorker.foregroundInfo(this,profile)
            if(pending==0)ServiceCompat.startForeground(this,profileNotificationId(profile),initial.notification,if(android.os.Build.VERSION.SDK_INT>=29)android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        }catch(e:Exception){
            if(process){processingProfiles.remove(profile);processAgain.remove(profile)}
            BackgroundWork.enqueue(this,profile)
            if(pending==0)stopSelf()
            return START_NOT_STICKY
        }
        pending++
        scope.launch {
            try {
                lanes.withPermit {
                    val b=(application as DriveApp).sessions.bundle(profile)
                    b.library.awaitReady()
                    val info=LibraryWorker.foregroundInfo(this@TransferService,profile)
                    ServiceCompat.startForeground(this@TransferService,profileNotificationId(profile),info.notification,if(android.os.Build.VERSION.SDK_INT>=29)android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
                    val observer=launch {
                        b.library.state.map{state->
                            val current=state.tasks.firstOrNull{it.state in setOf(TaskState.PREPARING,TaskState.TRANSFERRING)}
                            val title=if(state.preferences.hideNotifications || current?.vaultId?.isNotEmpty()==true)"Processing your saved transfers" else current?.title?:"Syncing your library"
                            title to state.tasks.count{it.state in setOf(TaskState.PREPARING,TaskState.TRANSFERRING)}
                        }.distinctUntilChanged().collect{(title,activeCount)->
                            val open=notificationRoute(this@TransferService,profile,"activity")
                            val notification=NotificationCompat.Builder(this@TransferService,"transfers").setSmallIcon(R.drawable.ic_drive).setContentTitle("TG Drive")
                                .setContentText(title).setSubText("${activeCount} active operations").setGroup("transfers:$profile").setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open).build()
                            getSystemService(android.app.NotificationManager::class.java).notify(profileNotificationId(profile),notification)
                        }
                    }
                    try {
                        withTimeout(55*60*1000L){
                            if(intent.action==SCAN)b.backups.scan(intent.getStringExtra("rule"),intent.getBooleanExtra("force",false))
                            do {
                                if(process)processAgain.remove(profile)
                                b.transfers.drain()
                            }while(process&&processAgain.remove(profile)&&hasReadyWork(b))
                        }
                    }finally{observer.cancel();getSystemService(android.app.NotificationManager::class.java).cancel(profileNotificationId(profile))}
                }
            }catch(e:Exception){if(e !is CancellationException)runCatching{(application as DriveApp).sessions.bundle(profile)}.getOrNull()?.library?.report(e)}
            finally{if(process){processingProfiles.remove(profile);processAgain.remove(profile)};pending--;if(pending==0){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}}
        }
        return START_NOT_STICKY
    }
    private fun hasReadyWork(bundle:SessionBundle):Boolean {
        val state=bundle.library.state.value
        val time=now()
        if(state.pendingOperations>0&&state.sendCooldownUntil<=time)return true
        return state.tasks.any{it.state !in setOf(TaskState.COMPLETED,TaskState.CANCELLED,TaskState.PAUSED,TaskState.FAILED,TaskState.WAITING_FOR_UNLOCK)&&it.nextAttemptAt<=time&&(it.kind!=TaskKind.UPLOAD||state.sendCooldownUntil<=time)}
    }
    override fun onTimeout(startId:Int,fgsType:Int){scope.cancel();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
    override fun onDestroy(){scope.cancel();super.onDestroy()}
    override fun onBind(intent:Intent?):IBinder?=null
    companion object{const val PROCESS="app.tgdrive.PROCESS_QUEUE";const val SCAN="app.tgdrive.SCAN_BACKUPS"}
}
