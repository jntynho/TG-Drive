package app.tgdrive.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tgdrive.PlaybackService
import app.tgdrive.SessionBundle

@Composable
internal fun AudioMiniPlayer(bundle:SessionBundle,onOpen:(String)->Unit) {
    val playback by PlaybackService.nowPlaying.collectAsStateWithLifecycle()
    val state by bundle.library.state.collectAsStateWithLifecycle()
    val context=LocalContext.current
    val current=playback?:return
    if(current.profile!=bundle.profile.id)return
    val item=remember(state.items,current.item){state.items.firstOrNull{it.id==current.item&&it.deletedAt==0L}}?:return
    Surface(color=MaterialTheme.colorScheme.surface,tonalElevation=0.dp) {
        Row(Modifier.fillMaxWidth().clickable(onClickLabel="Open audio player"){onOpen(item.id)}.padding(start=16.dp,end=8.dp,top=8.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            FileThumbnail(item,bundle,Modifier.size(42.dp))
            Column(Modifier.weight(1f)) {Text(item.name,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall);Text(if(current.playing)"Now playing"else"Paused",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            IconButton({PlaybackService.toggle(context,current.profile,current.item)}){Icon(if(current.playing)DriveIcons.Pause else DriveIcons.PlayArrow,if(current.playing)"Pause audio"else"Resume audio")}
            IconButton({PlaybackService.dismiss(context,current.profile,current.item)}){Icon(DriveIcons.Close,"Close audio player")}
        }
    }
}
