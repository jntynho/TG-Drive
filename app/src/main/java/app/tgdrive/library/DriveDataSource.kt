package app.tgdrive.library

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import app.tgdrive.SessionPool
import kotlinx.coroutines.*
import java.io.EOFException
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/** Seekable TDLib ranges; vaults use Tink's authenticated seekable decrypting channel. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DriveDataSource(private val pool: SessionPool) : BaseDataSource(true) {
    private var lease:java.io.Closeable?=null
    private var openedUri:Uri?=null
    private var position=0L;private var remaining=0L
    private var repository:LibraryRepository?=null;private var version:Version?=null;private var vault=""
    private var channel:SeekableByteChannel?=null;private var channelIndex=-1;private var started=false
    override fun getUri()=openedUri
    override fun open(dataSpec:DataSpec):Long {
        transferInitializing(dataSpec)
        val uri=dataSpec.uri
        require(uri.scheme=="tgdrive"&&uri.host=="file"&&uri.pathSegments.size==3){"Invalid private media URI"}
        val (profile,itemId,versionId)=uri.pathSegments
        val bundle=pool.bundle(profile);val state=bundle.library.state.value
        val item=state.items.firstOrNull{it.id==itemId}?:throw IOException("Open the app and unlock this file before playback.")
        val content=state.versions.firstOrNull{it.id==versionId&&it.itemId==item.id}?:throw IOException("Content version is unavailable.")
        repository=bundle.library;version=content;vault=state.itemVaults[item.id].orEmpty()
        if(!bundle.library.vaults.isUnlocked(vault))throw IOException("Unlock the vault before playback.")
        if(dataSpec.position>content.size)throw EOFException("Playback position exceeds the file size.")
        lease=repository!!.cache!!.lease(content.id)
        openedUri=uri;position=dataSpec.position;remaining=if(dataSpec.length==C.LENGTH_UNSET.toLong())content.size-position else minOf(dataSpec.length,content.size-position)
        started=true;transferStarted(dataSpec);return remaining
    }
    override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
        if(length==0)return 0
        if(remaining<=0)return C.RESULT_END_OF_INPUT
        val content=version?:throw IOException("Media source is closed")
        val repo=repository?:throw IOException("Media source is closed")
        var base=0L
        val index=content.parts.indexOfFirst { part->if(position<base+part.plainSize)true else {base+=part.plainSize;false} }
        if(index<0)throw IOException("The content manifest has a missing range.")
        val part=content.parts[index]
        try{
            if(channelIndex!=index){
                channel?.close()
                val raw=repo.cache!!.cachedRaw(content,part)?.let{FileInputStream(it).channel}?:RemotePartChannel(repo.remote,part)
                channel=if(part.encrypted)repo.vaults.decryptingChannel(vault,raw,"${content.itemId}/${content.id}/$index") else raw
                channelIndex=index
            }
            channel!!.position(position-base)
            val wanted=minOf(length.toLong(),remaining,part.plainSize-(position-base)).toInt()
            val count=channel!!.read(ByteBuffer.wrap(buffer,offset,wanted))
            if(count<=0)throw EOFException("The requested media range ended early.")
            position+=count;remaining-=count;bytesTransferred(count);return count
        }catch(e:Exception){throw IOException("Could not read the media range. Check connectivity and vault availability.",e)}
    }
    override fun close(){channel?.close();lease?.close();lease=null;channel=null;channelIndex=-1;repository=null;version=null;openedUri=null;if(started){started=false;transferEnded()}}
    class Factory(private val pool:SessionPool):DataSource.Factory{override fun createDataSource():DataSource=DriveDataSource(pool)}
}

private class RemotePartChannel(private val storage:TelegramStorage,private val part:Part):SeekableByteChannel{
    private var offset=0L
    @Volatile private var open=true
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val native by lazy{runBlocking{storage.document(part.messageId).document.document}}
    override fun isOpen()=open
    override fun position()=offset
    override fun position(newPosition:Long):SeekableByteChannel{require(newPosition>=0);offset=newPosition;return this}
    override fun size()=part.storedSize
    override fun truncate(size:Long):SeekableByteChannel=throw java.nio.channels.NonWritableChannelException()
    override fun write(src:ByteBuffer):Int=throw java.nio.channels.NonWritableChannelException()
    override fun read(dst:ByteBuffer):Int{
        check(open);if(offset>=part.storedSize)return -1
        val count=minOf(dst.remaining().toLong(),part.storedSize-offset,512*1024L).toInt()
        val data=runBlocking{scope.async{storage.range(part.messageId,offset,count,native)}.await()}
        if(data.isEmpty())return -1
        dst.put(data);offset+=data.size;return data.size
    }
    override fun close(){open=false;scope.cancel()}
}
