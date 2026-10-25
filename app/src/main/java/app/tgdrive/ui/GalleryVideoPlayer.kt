package app.tgdrive.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.semantics.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.tgdrive.PlaybackService
import app.tgdrive.SessionBundle
import app.tgdrive.library.DriveDataSource
import kotlinx.coroutines.*

internal fun mediaTime(milliseconds:Long):String {
    val seconds=milliseconds.coerceAtLeast(0)/1000
    return if(seconds>=3600)"%d:%02d:%02d".format(java.util.Locale.ROOT,seconds/3600,seconds/60%60,seconds%60)else"%d:%02d".format(java.util.Locale.ROOT,seconds/60,seconds%60)
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable internal fun GalleryVideoPlayer(item:app.tgdrive.library.Item,bundle:SessionBundle,onRetry:()->Unit,controls:Boolean,onToggle:()->Unit,onHide:()->Unit,modifier:Modifier,onOpen:()->Unit={}) {
    val touchExploration=rememberTouchExploration()
    val context=LocalContext.current
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val player=remember(item.versionId){ExoPlayer.Builder(context)
        .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context).setDataSourceFactory(DriveDataSource.Factory((context.applicationContext as app.tgdrive.DriveApp).sessions)))
        .setAudioAttributes(AudioAttributes.DEFAULT,true).setHandleAudioBecomingNoisy(true).build()}
    var error by remember(item.versionId){mutableStateOf<String?>(null)}
    var position by rememberSaveable(item.versionId){mutableLongStateOf(0)}
    var duration by remember{mutableLongStateOf(0)}
    var buffering by remember{mutableStateOf(true)}
    var playing by remember{mutableStateOf(false)}
    var ended by remember{mutableStateOf(false)}
    var wantsPlay by rememberSaveable(item.versionId){mutableStateOf(true)}
    var scrub by remember{mutableStateOf<Float?>(null)}
    var speed by remember{mutableStateOf(false)}
    var interaction by remember{mutableIntStateOf(0)}
    var rate by remember{mutableFloatStateOf(1f)}
    val hide by rememberUpdatedState(onHide)
    val libraryState by bundle.library.state.collectAsStateWithLifecycle()
    val account=libraryState.accountId
    fun checkpoint(){val saved=player.currentPosition
        bundle.library.scope.launch(Dispatchers.IO){runCatching{bundle.library.checkAccount(account);bundle.library.db().put("settings","playback:${item.versionId}",saved.toString())}}
    }
    DisposableEffect(player) {
        PlaybackService.pause(context)
        val listener=object:Player.Listener {
            override fun onEvents(p:Player,events:Player.Events){playing=p.isPlaying;ended=p.playbackState==Player.STATE_ENDED;buffering=p.playbackState==Player.STATE_BUFFERING;duration=p.duration.takeIf{it>0}?:0}
            override fun onPlayWhenReadyChanged(ready:Boolean,reason:Int){wantsPlay=ready;if(!ready)checkpoint()}
            override fun onPlayerError(e:PlaybackException){buffering=false;error="This video could not be played. Retry or open it in another app."}
        }
        val observer=androidx.lifecycle.LifecycleEventObserver{_,event->if(event==androidx.lifecycle.Lifecycle.Event.ON_STOP){checkpoint();player.pause()}}
        player.addListener(listener);lifecycle.addObserver(observer)
        onDispose {
            val saved=player.currentPosition
            bundle.library.scope.launch(Dispatchers.IO){runCatching{bundle.library.checkAccount(account);bundle.library.db().put("settings","playback:${item.versionId}",saved.toString())}}
            lifecycle.removeObserver(observer);player.removeListener(listener);player.release()
        }
    }
    LaunchedEffect(player) {
        if(position==0L)position=withContext(Dispatchers.IO){bundle.library.db().get("settings","playback:${item.versionId}")?.toLongOrNull()?:0}
        player.setMediaItem(MediaItem.Builder().setUri("tgdrive://file/${bundle.profile.id}/${item.id}/${item.versionId}").setMimeType(item.mime).build(),position)
        player.prepare();player.playWhenReady=wantsPlay
        var ticks=0
        while(isActive){position=player.currentPosition;if(++ticks%20==0)checkpoint();delay(250)}
    }
    LaunchedEffect(controls,playing,scrub,speed,interaction,touchExploration){if(controls&&playing&&scrub==null&&!speed&&!touchExploration){delay(3500);hide()}}
    Box(modifier.background(Color.Black)) {
        AndroidView(factory={PlayerView(it).apply{useController=false;resizeMode=androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT}},update={it.player=player},onRelease={it.player=null},modifier=Modifier.fillMaxSize())
        Box(Modifier.matchParentSize().clickable(onClickLabel=if(controls)"Hide video controls"else"Show video controls",onClick=onToggle))
        if(buffering&&error==null)CircularProgressIndicator(Modifier.align(Alignment.Center),color=Color.White)
        AnimatedVisibility(controls&&error==null,enter=fadeIn(),exit=fadeOut(),modifier=Modifier.align(Alignment.BottomCenter)) {
            Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.85f)))).navigationBarsPadding().padding(horizontal=20.dp).padding(top=40.dp,bottom=12.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text(mediaTime(scrub?.toLong()?:position),Modifier.weight(1f),color=Color.White,style=MaterialTheme.typography.labelMedium)
                    Box {TextButton({speed=true}){Text("${rate}×",color=Color.White)}
                        DropdownMenu(speed,{speed=false}){listOf(.5f,1f,1.25f,1.5f,2f).forEach{value->DropdownMenuItem(text={Text("${value}×")},onClick={rate=value;player.setPlaybackSpeed(value);speed=false;interaction++})}}
                    }
                    Text(mediaTime(duration),color=Color.White,style=MaterialTheme.typography.labelMedium)
                }
                Slider(value=scrub?:position.toFloat().coerceIn(0f,duration.toFloat().coerceAtLeast(1f)),onValueChange={scrub=it},onValueChangeFinished={scrub?.let{player.seekTo(it.toLong())};scrub=null;interaction++},valueRange=0f..duration.toFloat().coerceAtLeast(1f),enabled=duration>0,
                    modifier=Modifier.fillMaxWidth().semantics{contentDescription="Seek video"},colors=SliderDefaults.colors(thumbColor=Color.White,activeTrackColor=Color.White,inactiveTrackColor=Color.White.copy(alpha=.3f)))
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
                    TextButton({player.seekTo((player.currentPosition-10_000).coerceAtLeast(0));interaction++}){Text("−10s",color=Color.White)}
                    IconButton({if(player.playWhenReady&&player.playbackState!=Player.STATE_ENDED)player.pause()else{if(player.playbackState==Player.STATE_ENDED)player.seekTo(0);player.play()};interaction++},Modifier.padding(horizontal=24.dp).size(56.dp)) {Icon(if(wantsPlay&&!ended)DriveIcons.Pause else DriveIcons.PlayArrow,if(wantsPlay&&!ended)"Pause video"else if(ended)"Replay video"else"Play video",Modifier.size(36.dp),tint=Color.White)}
                    TextButton({player.seekTo((player.currentPosition+10_000).coerceAtMost(duration.coerceAtLeast(0)));interaction++}){Text("+10s",color=Color.White)}
                }
            }
        }
        error?.let{text->Column(Modifier.align(Alignment.Center).background(Color.Black).padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Text(text,color=Color.White);DriveButton(onRetry){Text("Retry video")};TextButton(onOpen){Text("Open in another app",color=Color.White)}}}
    }
}
