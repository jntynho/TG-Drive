package app.tgdrive.ui

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import app.tgdrive.PlaybackService
import app.tgdrive.SessionBundle
import app.tgdrive.library.*
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.min

@Composable fun NativePreview(item:Item,bundle:SessionBundle,system:SystemActions,onBack:()->Unit,snackbar:SnackbarHostState=remember{SnackbarHostState()},gallery:List<Item> = emptyList(),onNavigate:(String)->Unit={}) {
    val pages=if(gallery.any{it.id==item.id})gallery else listOf(item)
    val pager=rememberPagerState(initialPage=pages.indexOfFirst{it.id==item.id}.coerceAtLeast(0),pageCount={pages.size})
    var chrome by androidx.compose.runtime.saveable.rememberSaveable{mutableStateOf(true)}
    val visual=pages.getOrNull(pager.settledPage)?.mime?.let{it.startsWith("image/")||it.startsWith("video/")}==true
    val context=LocalContext.current
    val activity=context as? android.app.Activity
    LaunchedEffect(activity,visual,chrome) {
        val controller=activity?.window?.let{WindowCompat.getInsetsController(it,it.decorView)}
        if(visual&&!chrome) {controller?.systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;controller?.hide(WindowInsetsCompat.Type.systemBars())}
        else controller?.show(WindowInsetsCompat.Type.systemBars())
    }
    DisposableEffect(activity){onDispose{activity?.window?.let{WindowCompat.getInsetsController(it,it.decorView).show(WindowInsetsCompat.Type.systemBars())}}}
    var zoomed by remember{mutableStateOf(false)}
    val currentNavigate by rememberUpdatedState(onNavigate)
    LaunchedEffect(pager.settledPage,pages){pages.getOrNull(pager.settledPage)?.let{if(it.id!=item.id)currentNavigate(it.id)}}
    HorizontalPager(pager,userScrollEnabled=!zoomed,modifier=Modifier.fillMaxSize(),key={pages[it].id}){index->
        val active=index==pager.settledPage
        NativePreviewPage(pages[index],bundle,system,onBack,snackbar,active,{value->if(active){zoomed=value;if(value)chrome=false}},chrome,{chrome=it})
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun NativePreviewPage(item:Item,bundle:SessionBundle,system:SystemActions,onBack:()->Unit,snackbar:SnackbarHostState,active:Boolean,onZoom:(Boolean)->Unit,chrome:Boolean,onChrome:(Boolean)->Unit){
    DisposableEffect(item.versionId) { val lease=bundle.library.cache!!.lease(item.versionId); onDispose{lease.close()} }
    val isMedia=item.mime.startsWith("video/")||item.mime.startsWith("audio/")
    val isPhoto=item.mime.startsWith("image/")
    val visual=isPhoto||item.mime.startsWith("video/")
    var file by remember(item.versionId){mutableStateOf<File?>(null)}
    var error by remember(item.versionId){mutableStateOf<String?>(null)}
    var editText by remember(item.versionId){mutableStateOf(false)}
    var menu by remember(item.versionId){mutableStateOf(false)}
    var retry by remember(item.versionId){mutableIntStateOf(0)}
    val state by bundle.library.state.collectAsState()
    LaunchedEffect(item.versionId,retry,active){
        if(!active)return@LaunchedEffect
        error=null;file=null
        if(isMedia) {if(item.mime.startsWith("audio/"))system.play(item)}
        else try {
            val version=bundle.library.version(item.versionId)
            file=withContext(Dispatchers.IO){bundle.library.cache!!.materialize(item,version,state.itemVaults[item.id].orEmpty())}
            if(version.sha256.isNotEmpty())withContext(Dispatchers.IO){bundle.library.setLocal(bundle.library.db().local(item.id).copy(verifiedVersion=version.id,verifiedAt=now(),missing=false))}
        } catch(e:CancellationException){throw e}catch(e:Exception){error=e.message?:"Could not open this file."}
    }
    var toolbarPixels by remember{mutableIntStateOf(0)}
    val density=androidx.compose.ui.platform.LocalDensity.current
    val toolbarHeight=with(density){toolbarPixels.toDp()}
    val toolbar: @Composable ()->Unit = {
        TopAppBar(modifier=Modifier.onSizeChanged{toolbarPixels=it.height},title={Text(item.name,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall)},
            navigationIcon={IconButton(onBack,Modifier.padding(start=8.dp)){Icon(DriveIcons.ArrowBack,"Back")}},
            actions={IconButton({menu=true}){Icon(DriveIcons.MoreHoriz,"File actions")}},
            colors=TopAppBarDefaults.topAppBarColors(containerColor=if(visual)androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.background,
                titleContentColor=if(visual)androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onBackground,
                navigationIconContentColor=if(visual)androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onBackground,
                actionIconContentColor=if(visual)androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onBackground))
    }
    // The visual viewport is invariant when chrome toggles: no padding resize, crop or zoom jump.
    Box(Modifier.fillMaxSize().background(if(visual)androidx.compose.ui.graphics.Color.Black else MaterialTheme.colorScheme.background)) {
        Box(Modifier.fillMaxSize().then(if(visual)Modifier else Modifier.padding(top=toolbarHeight))) {
            when {
                isMedia && active -> key(retry){if(item.mime.startsWith("video/"))GalleryVideoPlayer(item,bundle,{retry++},chrome,{onChrome(!chrome)},{onChrome(false)},Modifier.fillMaxSize())else MediaPlayerPanel(item,bundle,onRetry={retry++},modifier=Modifier.fillMaxSize())}
                isMedia -> FileThumbnail(item,bundle,Modifier.fillMaxSize(),square=true)
                error!=null -> Column(Modifier.align(Alignment.Center).padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(error!!,color=MaterialTheme.colorScheme.error)
                    DriveButton({retry++}){Text("Retry opening")}
                }
                file==null -> Column(Modifier.align(Alignment.Center),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    FileThumbnail(item,bundle,Modifier.size(160.dp),square=true);CircularProgressIndicator();Text("Opening original…")
                }
                isPhoto -> ImagePreview(file!!,bundle,Modifier.fillMaxSize(),onTap={onChrome(!chrome)},onZoom=onZoom)
                else -> Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    when {
                        item.mime=="application/pdf" -> PdfPreview(file!!,Modifier.weight(1f))
                        item.mime.startsWith("text/")||item.name.endsWith(".json",true)||item.name.endsWith(".csv",true) -> TextPreview(file!!)
                        else -> Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                            Icon(fileIcon(item.mime),null,Modifier.size(64.dp),tint=MaterialTheme.colorScheme.primary)
                            Text("Open this file with a compatible application.")
                            DriveButton({system.openExternal(item)}){Text("Open in another app")}
                        }
                    }
                    state.local[item.id]?.ocrText?.takeIf{it.isNotBlank()}?.let {text->
                        DriveCard(Modifier.fillMaxWidth().padding(16.dp).heightIn(max=220.dp)) {
                            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                                Row(verticalAlignment=Alignment.CenterVertically){Text("Extracted text",Modifier.weight(1f));TextButton({editText=true}){Text("Edit")}}
                                SelectionContainer{Text(text,style=MaterialTheme.typography.bodySmall)}
                            }
                        }
                    }
                }
            }

        }
        AnimatedVisibility(chrome,enter=fadeIn(tween(160)),exit=fadeOut(tween(120)),modifier=Modifier.align(Alignment.TopCenter)) { toolbar() }
        if(active)SnackbarHost(snackbar,Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }

    if(menu)FileActionsSheet(item,state,bundle,system,onDismiss={menu=false},onPreview={menu=false;onChrome(true)})
    if(editText){
        var text by remember(item.id){mutableStateOf(state.local[item.id]?.ocrText.orEmpty())}
        AlertDialog(onDismissRequest={editText=false},title={Text("Correct extracted text")},text={DriveTextField(text,{if(it.length<=500_000)text=it},modifier=Modifier.fillMaxWidth().heightIn(min=200.dp,max=400.dp))},confirmButton={TextButton({bundle.actions.run{bundle.library.setLocal(bundle.library.db().local(item.id).copy(ocrText=text))};editText=false}){Text("Save")}},dismissButton={TextButton({editText=false}){Text("Cancel")}})
    }
}

/** Pan is limited to the actual aspect-fitted image, not the whole viewport. */
internal fun boundImagePan(pan:Offset,scale:Float,viewport:IntSize,image:IntSize):Offset {
    if(viewport.width<=0||viewport.height<=0||image.width<=0||image.height<=0)return Offset.Zero
    val fit=min(viewport.width.toFloat()/image.width,viewport.height.toFloat()/image.height)
    val x=((image.width*fit*scale-viewport.width)/2).coerceAtLeast(0f)
    val y=((image.height*fit*scale-viewport.height)/2).coerceAtLeast(0f)
    return Offset(if(x==0f)0f else pan.x.coerceIn(-x,x),if(y==0f)0f else pan.y.coerceIn(-y,y))
}

@Composable private fun ImagePreview(file:File,bundle:SessionBundle,modifier:Modifier,onTap:()->Unit,onZoom:(Boolean)->Unit){
    var loadError by remember(file){mutableStateOf(false)}
    val bitmap by produceState<Bitmap?>(null,file) {
        try {value=withContext(Dispatchers.IO){DocumentTools(bundle.library).decode(file)}}
        catch(e:CancellationException){throw e}catch(_:Exception){loadError=true}
    }
    val image=bitmap
    if(image!=null)GalleryZoomImage(image,modifier,onTap,onZoom)
    else Box(modifier,contentAlignment=Alignment.Center){if(loadError)Text("This image cannot be displayed. Use File actions to save or open it in another app.",Modifier.padding(24.dp))else CircularProgressIndicator()}
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
@Composable private fun MediaPlayerPanel(item:Item,bundle:SessionBundle,onRetry:()->Unit,modifier:Modifier){
    val context=LocalContext.current
    val expected="tgdrive://file/${bundle.profile.id}/${item.id}/${item.versionId}"
    var controller by remember(expected){mutableStateOf<MediaController?>(null)}
    var matching by remember(expected){mutableStateOf(false)}
    var error by remember(expected){mutableStateOf<String?>(null)}
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(expected,matching) {if(!matching){delay(15000);if(!matching&&error==null)error="Playback did not start. Check your connection and retry."}}
    DisposableEffect(context,expected) {
        val future=MediaController.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync()
        var disposed=false
        var attached:MediaController?=null
        val listener=object:Player.Listener {
            override fun onEvents(player:Player,events:Player.Events) {matching=player.currentMediaItem?.localConfiguration?.uri.toString()==expected;if(matching)error=player.playerError?.let{"This media could not be played. Check your connection or open it in another app."}}
            override fun onPlayerError(e:PlaybackException){if(matching)error="This media could not be played. Check your connection or open it in another app."}
        }
        val observer=androidx.lifecycle.LifecycleEventObserver{_,event->if(event==androidx.lifecycle.Lifecycle.Event.ON_STOP&&item.mime.startsWith("video/")&&matching)attached?.pause()}
        lifecycle.addObserver(observer)
        future.addListener({
            if(disposed)return@addListener
            try { attached=future.get();controller=attached;attached?.addListener(listener);matching=attached?.currentMediaItem?.localConfiguration?.uri.toString()==expected }
            catch(_:Exception){error="Could not connect to the player."}
        },ContextCompat.getMainExecutor(context))
        onDispose {
            disposed=true
            lifecycle.removeObserver(observer)
            attached?.removeListener(listener)
            if(item.mime.startsWith("video/")&&attached?.currentMediaItem?.localConfiguration?.uri.toString()==expected)attached?.pause()
            MediaController.releaseFuture(future)
        }
    }
    Box(modifier,contentAlignment=Alignment.Center) {
        Column(Modifier.fillMaxSize().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
            FileThumbnail(item,bundle,Modifier.sizeIn(maxWidth=280.dp,maxHeight=280.dp).fillMaxWidth().aspectRatio(1f))
            Spacer(Modifier.height(20.dp))
            Text(item.name,style=MaterialTheme.typography.titleMedium,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(16.dp))
        AndroidView(factory={PlayerView(it).apply{useController=true;controllerShowTimeoutMs=3000;setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);resizeMode=androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT;setKeepContentOnPlayerReset(false)}},
            update={it.player=if(matching)controller else null},onRelease={it.player=null},modifier=Modifier.fillMaxWidth().height(160.dp))
        }
        if(!matching&&error==null)CircularProgressIndicator()
        error?.let{text->Column(Modifier.background(MaterialTheme.colorScheme.background).padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Text(text);DriveButton(onRetry){Text("Retry playback")}}}
    }
}

/** Gallery video owns its player, so another service track can never supply its surface. */
