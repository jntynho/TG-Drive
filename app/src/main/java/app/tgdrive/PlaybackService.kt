package app.tgdrive

import android.content.Context
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.tgdrive.library.*
import kotlinx.coroutines.*

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService:MediaSessionService(){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private lateinit var player:ExoPlayer
    private var mediaSession:MediaSession?=null
    private var currentVault=""
    private var bundle:SessionBundle?=null
    private var versionId=""
    override fun onCreate(){
        super.onCreate();instance=this
        val app=application as DriveApp
        player=ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(DriveDataSource.Factory(app.sessions))).build()
        mediaSession=MediaSession.Builder(this,player).build()
        scope.launch{while(isActive){delay(10_000);savePosition()}}
    }
    override fun onGetSession(controllerInfo:MediaSession.ControllerInfo):MediaSession?=if(controllerInfo.packageName==packageName||controllerInfo.isTrusted)mediaSession else null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        if(intent?.action==PLAY){
            scope.launch{
                try{
                    val id=intent.getStringExtra("profile")?:return@launch
                    val itemId=intent.getStringExtra("item")?:return@launch
                    val b=(application as DriveApp).sessions.bundle(id);b.library.awaitReady()
                    val item=b.library.item(itemId)
                    currentVault=b.library.state.value.itemVaults[item.id].orEmpty();require(b.library.vaults.isUnlocked(currentVault)){"Unlock the vault before playback."}
                    savePosition();bundle=b;versionId=item.versionId
                    val uri="tgdrive://file/$id/${item.id}/${item.versionId}"
                    if(player.currentMediaItem?.localConfiguration?.uri.toString()!=uri){
                        val position=withContext(Dispatchers.IO){b.library.db().get("settings","playback:${item.versionId}")?.toLongOrNull()?:0}
                        val metadata=MediaMetadata.Builder().setTitle(if(b.library.state.value.preferences.hideNotifications || currentVault.isNotEmpty())"TG Drive playback" else item.name).build()
                        player.setMediaItem(MediaItem.Builder().setUri(uri).setMimeType(item.mime).setMediaMetadata(metadata).build(),position)
                        player.prepare()
                    } else if(player.playbackState==androidx.media3.common.Player.STATE_IDLE) player.prepare()
                    player.play()
                }catch(e:Exception){bundle?.library?.report(e);player.stop();stopSelf()}
            }
        }
        return super.onStartCommand(intent,flags,startId)
    }
    private suspend fun savePosition(){
        val b=bundle?:return;if(versionId.isEmpty())return
        val position=player.currentPosition;val version=versionId
        withContext(Dispatchers.IO){runCatching{b.library.db().put("settings","playback:$version",position.toString())}}
    }
    override fun onTimeout(startId:Int,fgsType:Int){player.stop();stopSelf()}
    override fun onDestroy(){mediaSession?.release();player.release();scope.cancel();if(instance===this)instance=null;super.onDestroy()}
    companion object{
        const val PLAY="app.tgdrive.PLAY"
        @Volatile private var instance:PlaybackService?=null
        fun stopProtected(context:Context){androidx.core.content.ContextCompat.getMainExecutor(context).execute{instance?.let{if(it.currentVault.isNotEmpty())it.player.stop()}}}
    }
}
