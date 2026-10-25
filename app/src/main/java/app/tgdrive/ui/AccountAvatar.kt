package app.tgdrive.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tgdrive.SessionBundle
import kotlinx.coroutines.*
import org.drinkless.tdlib.TdApi

@Composable
internal fun AccountAvatar(bundle:SessionBundle?,name:String,modifier:Modifier=Modifier) {
    val auth=bundle?.drive?.state?.collectAsStateWithLifecycle()?.value
    val account=auth?.accountId?:0L
    val photo=auth?.avatarFileId?:0
    val bitmap by produceState<Bitmap?>(null,bundle,account,photo) {
        value=null
        if(bundle!=null&&account!=0L&&photo!=0)value=try{withContext(Dispatchers.IO){
            val native=bundle.drive.call(TdApi.DownloadFile(photo,1,0,0,true),30000) as TdApi.File
            val file=java.io.File(native.local.path)
            if(!native.local.isDownloadingCompleted||file.length() !in 1..2_097_152||bundle.drive.state.value.accountId!=account)return@withContext null
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(file.path,bounds)
            var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>128)sample*=2
            BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply{inSampleSize=sample})
        }}catch(e:CancellationException){throw e}catch(_:Exception){null}
    }
    Box(modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),contentAlignment=Alignment.Center){
        val image=bitmap
        if(image!=null)Image(image.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=androidx.compose.ui.layout.ContentScale.Crop)
        else if(name.isNotBlank())Text(name.take(1).uppercase(),style=MaterialTheme.typography.titleMedium)
        else Icon(DriveIcons.Person,null)
    }
}
