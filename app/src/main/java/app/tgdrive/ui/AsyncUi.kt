package app.tgdrive.ui

import androidx.compose.runtime.*
import kotlinx.coroutines.*

/** A previous query/scope can never paint one frame of results for a new query/scope. */
@Composable internal fun <T> computedList(vararg keys:Any?, compute:()->List<T>):List<T>? {
    val token=remember(*keys){Any()}
    val result by produceState<Pair<Any,List<T>>?>(null,token){
        value=token to withContext(Dispatchers.Default){compute()}
    }
    return result?.takeIf{it.first===token}?.second
}

@Stable internal class FormAction(private val scope:CoroutineScope) {
    var busy by mutableStateOf(false);private set
    var error by mutableStateOf<String?>(null);private set
    fun run(success:()->Unit={},block:suspend()->Unit){
        if(busy)return
        busy=true;error=null
        scope.launch{try{withContext(Dispatchers.IO){block()};success()}
            catch(e:CancellationException){throw e}
            catch(e:Exception){error=e.message?:"Could not complete this action. Please try again."}
            finally{busy=false}}
    }
}
@Composable internal fun rememberFormAction():FormAction {val scope=rememberCoroutineScope();return remember(scope){FormAction(scope)}}
