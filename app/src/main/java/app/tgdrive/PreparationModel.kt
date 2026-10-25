package app.tgdrive

import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.Job

internal data class PreparedExport(val uri:Uri,val mime:String,val name:String,val share:Boolean,val profile:String)
/** Retained across rotation; contains no vault password or recovery credential. */
internal class PreparationModel:ViewModel(){
    var message by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var export by mutableStateOf<PreparedExport?>(null)
    var job:Job?=null
    override fun onCleared(){job?.cancel()}
}
