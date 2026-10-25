package app.tgdrive.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Audio has persistent transport controls; it never needs a blank video surface. */
@Composable internal fun NativeAudioTransport(player:Player?,enabled:Boolean) {
    var position by remember(player){mutableLongStateOf(0)}
    var duration by remember(player){mutableLongStateOf(0)}
    var playWhenReady by remember(player){mutableStateOf(false)}
    var ended by remember(player){mutableStateOf(false)}
    var buffering by remember(player){mutableStateOf(false)}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    fun refresh(){
        if(player==null)return
        position=player.currentPosition.coerceAtLeast(0)
        duration=player.duration.coerceAtLeast(0)
        ended=player.playbackState==Player.STATE_ENDED
        playWhenReady=player.playWhenReady&&!ended
        buffering=player.playbackState==Player.STATE_BUFFERING
    }
    DisposableEffect(player){
        val listener=object:Player.Listener{override fun onEvents(player:Player,events:Player.Events){refresh()}}
        player?.addListener(listener);refresh()
        onDispose{player?.removeListener(listener)}
    }
    LaunchedEffect(player,enabled,lifecycle){
        if(enabled)lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED){while(isActive){refresh();delay(500)}}
    }
    AudioTransportControls(if(enabled)position else 0,if(enabled)duration else 0,enabled&&playWhenReady,enabled,enabled&&buffering,enabled&&ended,
        onToggle={player?.let{if(it.playWhenReady&&it.playbackState!=Player.STATE_ENDED)it.pause()else{if(it.playbackState==Player.STATE_ENDED)it.seekTo(0);it.play()}}},
        onSeek={player?.seekTo(it)})
}

@Composable internal fun AudioTransportControls(
    position:Long,duration:Long,playWhenReady:Boolean,enabled:Boolean,buffering:Boolean=false,ended:Boolean=false,
    onToggle:()->Unit,onSeek:(Long)->Unit,
) {
    var scrub by remember{mutableStateOf<Float?>(null)}
    val total=duration.coerceAtLeast(1).toFloat()
    val seekable=enabled&&duration>0
    LaunchedEffect(enabled,duration){scrub=null}
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Slider(value=(scrub?:position.toFloat()).coerceIn(0f,total),onValueChange={scrub=it},
            onValueChangeFinished={scrub?.let{onSeek(it.toLong().coerceIn(0,duration.coerceAtLeast(0)))};scrub=null},
            valueRange=0f..total,enabled=seekable,modifier=Modifier.fillMaxWidth().semantics{contentDescription="Seek audio"})
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
            Text(mediaTime(scrub?.toLong()?:position),style=MaterialTheme.typography.labelMedium)
            Text(mediaTime(duration),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(20.dp,Alignment.CenterHorizontally),verticalAlignment=Alignment.CenterVertically) {
            TextButton({onSeek((position-10_000).coerceIn(0,duration.coerceAtLeast(0)))},enabled=seekable,
                modifier=Modifier.sizeIn(minWidth=52.dp,minHeight=52.dp).semantics{contentDescription="Back 10 seconds"}){Text("−10s")}
            FilledIconButton(onToggle,enabled=enabled,modifier=Modifier.size(64.dp),
                colors=IconButtonDefaults.filledIconButtonColors(containerColor=DriveStyle.ActionBlue,contentColor=DriveStyle.OnAction)) {
                Icon(if(playWhenReady)DriveIcons.Pause else DriveIcons.PlayArrow,
                    if(playWhenReady)"Pause audio"else if(ended)"Replay audio"else"Play audio",Modifier.size(32.dp))
            }
            TextButton({onSeek((position+10_000).coerceIn(0,duration.coerceAtLeast(0)))},enabled=seekable,
                modifier=Modifier.sizeIn(minWidth=52.dp,minHeight=52.dp).semantics{contentDescription="Forward 10 seconds"}){Text("+10s")}
        }
        if(buffering)Text("Buffering…",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.align(Alignment.CenterHorizontally))
    }
}
