package app.tgdrive

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.tgdrive.data.*
import app.tgdrive.library.*
import kotlinx.coroutines.*
import org.drinkless.tdlib.TdApi
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AudioArtworkTest {
    @Test fun embeddedAudioCoverIsExtractedAndMissingCoverRemainsEmpty()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val settings=object:SessionSettings {
            override fun get(name:String):String?=null
            override fun put(name:String,value:String){}
            override fun databaseKey()=ByteArray(32)
        }
        val factory=object:TdConnectionFactory {
            override fun create(onUpdate:(TdApi.Object)->Unit,onError:()->Unit):TdConnection=error("This test must not connect to Telegram")
        }
        val drive=TelegramDrive(context,settings,factory,scope)
        val repository=LibraryRepository(context,drive,scope)
        val files=mutableListOf<File>()
        try {
            fun fixture(name:String)=File(context.cacheDir,"artwork-test-$name").also{file->files+=file;instrumentation.context.assets.open(name).use{input->file.outputStream().use{input.copyTo(it)}}}
            val bytes=ThumbnailTools(repository).create(Uri.fromFile(fixture("cover-song.mp3")),"audio/mpeg")
            assertNotNull(bytes)
            val bitmap=BitmapFactory.decodeByteArray(bytes!!,0,bytes.size)
            assertTrue(bitmap.width in 1..320&&bitmap.height in 1..320);bitmap.recycle()
            assertNull(ThumbnailTools(repository).create(Uri.fromFile(fixture("no-cover-song.mp3")),"audio/mpeg"))
        } finally {scope.cancel();files.forEach{it.delete()}}
    }
}
