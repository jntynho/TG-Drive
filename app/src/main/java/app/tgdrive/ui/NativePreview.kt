package app.tgdrive.ui

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import app.tgdrive.PlaybackService
import app.tgdrive.SessionBundle
import app.tgdrive.data.formatBytes
import app.tgdrive.library.*
import kotlinx.coroutines.*
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun NativePreview(item:Item,bundle:SessionBundle,system:SystemActions,onBack:()->Unit){
    DisposableEffect(item.versionId) { val lease=bundle.library.cache!!.lease(item.versionId); onDispose{lease.close()} }
    val isMedia=item.mime.startsWith("video/")||item.mime.startsWith("audio/")
    var file by remember(item.versionId){mutableStateOf<File?>(null)};var error by remember(item.versionId){mutableStateOf<String?>(null)}
    var editText by remember{mutableStateOf(false)}
    var retry by remember{mutableIntStateOf(0)}
    val state by bundle.library.state.collectAsState()
    LaunchedEffect(item.versionId,retry){
        if(!isMedia){
            error=null
            try{
                val version=bundle.library.version(item.versionId)
                file=bundle.library.cache!!.materialize(item,version,state.itemVaults[item.id].orEmpty())
                if(version.sha256.isNotEmpty())bundle.library.setLocal(bundle.library.db().local(item.id).copy(verifiedVersion=version.id,verifiedAt=now(),missing=false))
            }catch(e:CancellationException){throw e}catch(e:Exception){error=e.message?:"Could not open this file."}
        }
    }
    Scaffold(topBar={TopAppBar(title={Column{Text(item.name,maxLines=1);Text(formatBytes(item.size),style=MaterialTheme.typography.labelSmall)}},navigationIcon={IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Back to files")}},actions={
        IconButton(onClick={system.save(item)}){Icon(Icons.Outlined.Download,"Save file")}
        IconButton(onClick={system.shareExternal(item)}){Icon(Icons.Outlined.Share,"Share exported copy")}
    })}){padding->
        Column(Modifier.fillMaxSize().padding(padding),verticalArrangement=Arrangement.spacedBy(10.dp)){
            when{
                isMedia->Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    MediaPlayerPanel()
                    Button(onClick={system.play(item)},Modifier.fillMaxWidth()){Icon(Icons.Outlined.PlayArrow,null);Text("Play / resume")}
                    Text("Playback requests ranges as needed. Starting playback is not a full-file integrity check. Network and codec support affect availability.",style=MaterialTheme.typography.bodySmall)
                }
                error!=null->Column(Modifier.padding(24.dp)){Text(error!!,color=MaterialTheme.colorScheme.error);Button(onClick={retry++}){Text("Retry opening")}}
                file==null->Column(Modifier.fillMaxWidth().padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally){CircularProgressIndicator();Spacer(Modifier.height(12.dp));Text("Downloading and checking content…")}
                item.mime.startsWith("image/")->ImagePreview(file!!,bundle,Modifier.weight(1f))
                item.mime=="application/pdf"->PdfPreview(file!!,Modifier.weight(1f))
                item.mime.startsWith("text/")||item.name.endsWith(".json",true)||item.name.endsWith(".csv",true)->TextPreview(file!!)
                else->Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
                    Icon(fileIcon(item.mime),null,Modifier.size(64.dp),tint=MaterialTheme.colorScheme.primary)
                    Text("This format has no built-in preview. The file is available for export.")
                    Button(onClick={system.openExternal(item)}){Text("Open in another application")}
                }
            }
            state.local[item.id]?.ocrText?.takeIf{it.isNotBlank()}?.let{text->ElevatedCard(Modifier.fillMaxWidth().padding(16.dp).heightIn(max=220.dp)){
                Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())){Row(verticalAlignment=Alignment.CenterVertically){Text("Extracted text",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);TextButton(onClick={editText=true}){Text("Edit text")}};SelectionContainer{Text(text,style=MaterialTheme.typography.bodySmall)}}
            }}
        }
    }
    if(editText){
        var text by remember{mutableStateOf(state.local[item.id]?.ocrText.orEmpty())}
        AlertDialog(onDismissRequest={editText=false},title={Text("Correct extracted text")},text={OutlinedTextField(text,{if(it.length<=500_000)text=it},modifier=Modifier.fillMaxWidth().heightIn(min=200.dp,max=400.dp),label={Text("Local searchable text")})},confirmButton={TextButton(onClick={bundle.actions.run{bundle.library.setLocal(bundle.library.db().local(item.id).copy(ocrText=text))};editText=false}){Text("Save text")}},dismissButton={TextButton(onClick={editText=false}){Text("Cancel")}})
    }
}
@Composable private fun ImagePreview(file:File,bundle:SessionBundle,modifier:Modifier){
    var error by remember(file){mutableStateOf(false)}
    val bitmap by produceState<Bitmap?>(null,file){value=withContext(Dispatchers.IO){runCatching{DocumentTools(bundle.library).decode(file)}.getOrElse{error=true;null}}}
    var scale by remember(file){mutableFloatStateOf(1f)};var offset by remember(file){mutableStateOf(Offset.Zero)}
    val transform=rememberTransformableState{zoom,pan,_->scale=(scale*zoom).coerceIn(1f,6f);offset=if(scale==1f)Offset.Zero else offset+pan}
    Box(modifier.fillMaxWidth().clipToBounds().transformable(transform),contentAlignment=Alignment.Center){
        bitmap?.let{Image(it.asImageBitmap(),contentDescription="Image preview",modifier=Modifier.fillMaxSize().graphicsLayer{scaleX=scale;scaleY=scale;translationX=offset.x;translationY=offset.y})}?:if(error)Text("This image cannot be decoded. Save it to open in another app.") else CircularProgressIndicator()
    }
}
@Composable private fun PdfPreview(file:File,modifier:Modifier){
    var page by remember(file){mutableIntStateOf(0)};var pages by remember(file){mutableIntStateOf(1)}
    var error by remember{mutableStateOf<String?>(null)}
    val bitmap by produceState<Bitmap?>(null,file,page){
        value=withContext(Dispatchers.IO){
            try{ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use{fd->PdfRenderer(fd).use{pdf->
                pages=pdf.pageCount
                pdf.openPage(page.coerceIn(0,pdf.pageCount-1)).use{p->val ratio=minOf(2f,2200f/maxOf(p.width,p.height));Bitmap.createBitmap((p.width*ratio).toInt().coerceAtLeast(1),(p.height*ratio).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888).also{it.eraseColor(Color.WHITE);p.render(it,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)}}
            }}}catch(e:Exception){error="This PDF cannot be previewed. It may be password-protected or unsupported.";null}
        }
    }
    Column(modifier.fillMaxWidth().padding(12.dp),horizontalAlignment=Alignment.CenterHorizontally){
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically){TextButton(onClick={page--},enabled=page>0){Text("Previous")};Text("${page+1} / $pages");TextButton(onClick={page++},enabled=page+1<pages){Text("Next")}}
        val image=bitmap
        if(image!=null)Image(image.asImageBitmap(),"PDF page ${page+1}",Modifier.fillMaxWidth().weight(1f)) else if(error==null)CircularProgressIndicator()
    }
}
@Composable private fun TextPreview(file:File){
    val text by produceState("",file){value=withContext(Dispatchers.IO){file.bufferedReader().use{r->val chars=CharArray(100_000);val n=r.read(chars);if(n<0)"Empty file" else String(chars,0,n)+(if(n==chars.size)"\n\n[Preview limited to 100,000 characters]" else "")}}}
    SelectionContainer{Text(text,Modifier.fillMaxWidth().padding(20.dp).verticalScroll(rememberScrollState()),style=MaterialTheme.typography.bodyMedium)}
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable private fun MediaPlayerPanel(){
    val context=LocalContext.current;var controller by remember{mutableStateOf<MediaController?>(null)}
    DisposableEffect(context){
        val future=MediaController.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync()
        future.addListener({runCatching{controller=future.get()}},ContextCompat.getMainExecutor(context))
        onDispose{controller?.release();MediaController.releaseFuture(future)}
    }
    AndroidView(factory={PlayerView(it).apply{useController=true;controllerShowTimeoutMs=0}},update={it.player=controller},modifier=Modifier.fillMaxWidth().aspectRatio(16f/9f))
}
