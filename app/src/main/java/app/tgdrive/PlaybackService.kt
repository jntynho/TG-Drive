package app.tgdrive

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.tgdrive.library.*
import kotlinx.coroutines.*

enum class PlaybackStatus(val label:String){PREPARING("Preparing"),BUFFERING("Buffering"),PLAYING("Now playing"),PAUSED("Paused"),ENDED("Finished"),ERROR("Playback failed")}
data class NowPlaying(val profile:String,val item:String,val playing:Boolean,val status:PlaybackStatus=if(playing)PlaybackStatus.PLAYING else PlaybackStatus.PAUSED,val playWhenReady:Boolean=playing)

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
        player=ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(DriveDataSource.Factory(app.sessions))).setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT,true).setHandleAudioBecomingNoisy(true).build()
        player.addListener(object:androidx.media3.common.Player.Listener {
            override fun onEvents(player:androidx.media3.common.Player,events:androidx.media3.common.Player.Events){
                val uri=player.currentMediaItem?.localConfiguration?.uri
                val path=uri?.pathSegments.orEmpty()
                val status=when {player.playerError!=null->PlaybackStatus.ERROR
                    player.playbackState==androidx.media3.common.Player.STATE_BUFFERING->PlaybackStatus.BUFFERING
                    player.playbackState==androidx.media3.common.Player.STATE_ENDED->PlaybackStatus.ENDED
                    player.isPlaying->PlaybackStatus.PLAYING
                    player.playbackState==androidx.media3.common.Player.STATE_IDLE->PlaybackStatus.PREPARING
                    else->PlaybackStatus.PAUSED}
                playingState.value=if(path.size==3)NowPlaying(path[0],path[1],player.isPlaying,status,player.playWhenReady)else null
            }
        })
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
    override fun onDestroy(){playingState.value=null;mediaSession?.release();player.release();scope.cancel();if(instance===this)instance=null;super.onDestroy()}
    companion object{
        private val playingState=kotlinx.coroutines.flow.MutableStateFlow<NowPlaying?>(null)
        val nowPlaying:kotlinx.coroutines.flow.StateFlow<NowPlaying?> = playingState
        fun toggle(context:Context,profile:String,item:String){ContextCompat.getMainExecutor(context).execute{instance?.let{service->
            val parts=service.player.currentMediaItem?.localConfiguration?.uri?.pathSegments
            if(parts?.getOrNull(0)==profile&&parts.getOrNull(1)==item){if(service.player.playerError!=null){service.player.prepare();service.player.play()}else if(service.player.playbackState==androidx.media3.common.Player.STATE_ENDED){service.player.seekTo(0);service.player.play()}else if(service.player.playWhenReady)service.player.pause()else service.player.play()}
        }}}
        fun dismiss(context:Context,profile:String,item:String){ContextCompat.getMainExecutor(context).execute{instance?.let{service->service.scope.launch{
            service.savePosition()
            val parts=service.player.currentMediaItem?.localConfiguration?.uri?.pathSegments
            if(parts?.getOrNull(0)==profile&&parts.getOrNull(1)==item){service.player.stop();service.player.clearMediaItems();service.stopSelf()}
        }}}}
        const val PLAY="app.tgdrive.PLAY"
        @Volatile private var instance:PlaybackService?=null
        fun pause(context:Context){androidx.core.content.ContextCompat.getMainExecutor(context).execute{instance?.player?.pause()}}
        fun stopProtected(context:Context){androidx.core.content.ContextCompat.getMainExecutor(context).execute{instance?.let{if(it.currentVault.isNotEmpty())it.player.stop()}}}
    }
}
