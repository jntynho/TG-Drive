package app.tgdrive.library

import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Only disposable, app-owned copies expire. Active/retry inputs and local-original references survive. */
internal object TemporaryFiles {
    fun prune(repository:LibraryRepository){
        val context=repository.context;val profile=repository.session.profileId
        val tasks=repository.db().tasks()
        val refs=tasks.filter{it.state !in setOf(TaskState.COMPLETED,TaskState.CANCELLED)||it.pendingMessageId!=0L}.map{it.source}.toSet()+
            repository.db().all("local").map{it.model<LocalItem>().sourceUri}
        val retainedTasks=tasks.filter{it.state !in setOf(TaskState.COMPLETED,TaskState.CANCELLED)||it.pendingMessageId!=0L}.map{it.id}.toSet()
        val cutoff=now()-86_400_000L
        File(context.filesDir,"transfer-staging/$profile").listFiles()?.filter{it.name !in retainedTasks&&it.lastModified()<cutoff}?.forEach{it.deleteRecursively()}
        for(kind in listOf("owned-inputs","captures")) {
            val directory=File(context.filesDir,"$kind/$profile")
            directory.walkBottomUp().filter{it.isFile&&it.lastModified()<cutoff}.forEach{file->
                val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file).toString()
                if(uri !in refs)file.delete()
            }
            directory.walkBottomUp().filter{it!=directory&&it.isDirectory&&it.list()?.isEmpty()==true}.forEach{it.delete()}
        }
        // Shared copies have a grace period so recipient apps can finish opening them.
        File(context.cacheDir,"exports").listFiles()?.filter{dir->dir.walkTopDown().filter{it.isFile}.all{it.lastModified()<cutoff}}?.forEach{it.deleteRecursively()}
    }
    fun bytes(directory:File)=directory.walkTopDown().filter{it.isFile}.sumOf{it.length()}
}
