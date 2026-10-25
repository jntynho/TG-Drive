package app.tgdrive.library

import android.graphics.Bitmap
import android.util.LruCache
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Account-scoped decoded cache with shared, cancellable requests and bounded decoder concurrency. */
class ThumbnailLoader(private val repository:LibraryRepository){
    private data class Key(val account:Long,val version:String,val vault:String)
    private class Request(val task:Deferred<Bitmap?>,var readers:Int=0)
    private val cache=object:LruCache<Key,Bitmap>(8*1024*1024){
        override fun sizeOf(key:Key,value:Bitmap)=value.allocationByteCount
    }
    private val missing=mutableMapOf<Key,Long>()
    private val pending=mutableMapOf<Key,Request>()
    private val decoders=Semaphore(3)
    private var generation=0L

    suspend fun read(item:Item,refresh:Boolean=false):Bitmap? {
        val account=repository.state.value.accountId
        val vault=repository.itemVault(item.id)
        check(repository.vaults.isUnlocked(vault))
        val key=Key(account,item.versionId,vault)
        val request=synchronized(this){
            if(refresh){cache.remove(key);missing.remove(key)}
            cache.get(key)?.let{return it}
            if((missing[key]?:0)>now())return null
            val epoch=generation
            val shared=pending.getOrPut(key){Request(repository.scope.async(Dispatchers.IO,start=CoroutineStart.LAZY){
                decoders.withPermit{
                    repository.checkAccount(account);check(repository.vaults.isUnlocked(vault))
                    val bitmap=ThumbnailTools(repository).read(item)
                    ensureActive();repository.checkAccount(account);check(repository.vaults.isUnlocked(vault))
                    synchronized(this@ThumbnailLoader){
                        check(epoch==generation){"Preview was invalidated."}
                        if(bitmap!=null)cache.put(key,bitmap)else{
                            missing[key]=now()+30_000
                            if(missing.size>512)missing.remove(missing.keys.first())
                        }
                    }
                    bitmap
                }
            })}
            shared.readers++;shared
        }
        try{return request.task.await().also{repository.checkAccount(account);check(repository.vaults.isUnlocked(vault))}}
        finally{synchronized(this){
            request.readers--
            if(request.readers==0){
                if(pending[key]===request)pending.remove(key)
                if(!request.task.isCompleted)request.task.cancel()
            }
        }}
    }
    @Synchronized fun clear(){
        generation++;cache.evictAll();missing.clear()
        pending.values.toList().forEach{it.task.cancel()};pending.clear()
    }
}
