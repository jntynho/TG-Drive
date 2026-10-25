package app.tgdrive.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import app.tgdrive.SessionBundle
import kotlinx.coroutines.*
import org.drinkless.tdlib.TdApi

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DriveAppHeader(title:String,onBack:(()->Unit)?,onAccounts:()->Unit,name:String,accountId:Long,photoId:Int,bundle:SessionBundle?=null) {
    val avatar by produceState<Bitmap?>(null,bundle,accountId,photoId) {
        value=null
        if(bundle!=null && accountId!=0L && photoId!=0) {
            value=try { withContext(Dispatchers.IO) {
                val file=bundle.drive.call(TdApi.DownloadFile(photoId,1,0,0,true),30_000) as TdApi.File
                if(!file.local.isDownloadingCompleted || bundle.drive.state.value.accountId!=accountId) return@withContext null
                val path=java.io.File(file.local.path)
                if(path.length() !in 1..2_097_152) return@withContext null
                val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                BitmapFactory.decodeFile(path.path,bounds)
                var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>128)sample*=2
                BitmapFactory.decodeFile(path.path,BitmapFactory.Options().apply{inSampleSize=sample})
            }} catch(e:CancellationException){throw e}catch(_:Exception){null}
        }
    }
    TopAppBar(title={Text(title,style=MaterialTheme.typography.titleLarge,maxLines=1,overflow=TextOverflow.Ellipsis)},
        navigationIcon={if(onBack!=null) DriveCircleButton(DriveIcons.ArrowBack,"Back",onBack,Modifier.padding(start=12.dp))},
        actions={
            FilledIconButton(onAccounts,Modifier.padding(end=12.dp).size(48.dp).semantics{contentDescription="Manage accounts"},shape=CircleShape,
                colors=IconButtonDefaults.filledIconButtonColors(containerColor=MaterialTheme.colorScheme.surface)) {
                val image=avatar
                if(image!=null) Image(image.asImageBitmap(),null,Modifier.fillMaxSize().clip(CircleShape),contentScale=androidx.compose.ui.layout.ContentScale.Crop)
                else if(name.isNotBlank()) Text(name.take(1).uppercase(),Modifier,style=MaterialTheme.typography.titleMedium)
                else Icon(DriveIcons.Person,null)
            }
        },colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.background))
}
