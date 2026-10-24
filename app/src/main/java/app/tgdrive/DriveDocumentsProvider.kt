package app.tgdrive

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import app.tgdrive.library.*
import kotlinx.coroutines.*
import java.io.FileNotFoundException

/** Read-only Android document picker integration. It never exposes databases, keys, or login sessions. */
class DriveDocumentsProvider:DocumentsProvider(){
    private val app get()=context!!.applicationContext as DriveApp
    override fun onCreate()=true
    override fun queryRoots(projection:Array<out String>?):Cursor{
        val out=MatrixCursor(projection?:ROOT_COLUMNS)
        for(profile in app.sessions.profiles.value.filter{it.userId!=0L}){
            val accessible=!app.sessions.lockEnabled(profile.id)||app.authorized(profile.id)
            out.newRow().add(DocumentsContract.Root.COLUMN_ROOT_ID,profile.id).add(DocumentsContract.Root.COLUMN_DOCUMENT_ID,id(profile.id,"folder","root"))
                .add(DocumentsContract.Root.COLUMN_TITLE,"TG Drive")
                .add(DocumentsContract.Root.COLUMN_SUMMARY,if(accessible)profile.label else "Unlock TG Drive first")
                .add(DocumentsContract.Root.COLUMN_FLAGS,DocumentsContract.Root.FLAG_SUPPORTS_SEARCH or DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD)
                .add(DocumentsContract.Root.COLUMN_ICON,R.drawable.ic_drive)
        }
        return out
    }
    private fun bundle(profile:String):SessionBundle{
        if(app.sessions.lockEnabled(profile)&&!app.authorized(profile))throw FileNotFoundException("Unlock TG Drive to access this account.")
        return app.sessions.bundle(profile)
    }
    override fun queryDocument(documentId:String,projection:Array<out String>?):Cursor{
        val (profile,kind,key)=parse(documentId);val state=bundle(profile).library.state.value
        val out=MatrixCursor(projection?:DOCUMENT_COLUMNS)
        if(kind=="folder"&&key=="root")addFolder(out,documentId,"TG Drive")
        else if(kind=="folder")addFolder(out,documentId,state.folders.firstOrNull{it.id==key&&it.deletedAt==0L}?.name?:throw FileNotFoundException("Folder unavailable or locked"))
        else addItem(out,profile,state.items.firstOrNull{it.id==key&&it.deletedAt==0L}?:throw FileNotFoundException("File unavailable or locked"))
        return out
    }
    override fun queryChildDocuments(parentDocumentId:String,projection:Array<out String>?,sortOrder:String?):Cursor{
        val(profile,kind,key)=parse(parentDocumentId);require(kind=="folder")
        val state=bundle(profile).library.state.value;val folder=if(key=="root")""else key
        val out=MatrixCursor(projection?:DOCUMENT_COLUMNS)
        if(!state.loaded){out.extras=Bundle().apply{putString(DocumentsContract.EXTRA_INFO,"Open TG Drive to finish signing in and unlock your library.")};return out}
        state.folders.filter{it.parentId==folder&&it.deletedAt==0L}.sortedBy{it.name.lowercase()}.forEach{addFolder(out,id(profile,"folder",it.id),it.name)}
        state.items.filter{it.folderId==folder&&it.deletedAt==0L&&it.versionId.isNotEmpty()}.sortedBy{it.name.lowercase()}.forEach{addItem(out,profile,it)}
        return out
    }
    override fun querySearchDocuments(rootId:String,query:String,projection:Array<out String>?):Cursor{
        val state=bundle(rootId).library.state.value
        return MatrixCursor(projection?:DOCUMENT_COLUMNS).also{out->state.items.filter{it.deletedAt==0L&&it.name.contains(query,true)&&it.versionId.isNotEmpty()}.take(200).forEach{addItem(out,rootId,it)}}
    }
    override fun isChildDocument(parentDocumentId:String,documentId:String):Boolean{
        val(parentProfile,parentKind,parent)=parse(parentDocumentId);val(profile,kind,key)=parse(documentId)
        if(parentProfile!=profile||parentKind!="folder")return false
        val state=bundle(profile).library.state.value
        var folder=if(kind=="folder")state.folders.firstOrNull{it.id==key}?.parentId else state.items.firstOrNull{it.id==key}?.folderId
        val target=if(parent=="root")""else parent;val seen=mutableSetOf<String>()
        while(folder!=null&&seen.add(folder)){if(folder==target)return true;folder=state.folders.firstOrNull{it.id==folder}?.parentId}
        return false
    }
    override fun openDocument(documentId:String,mode:String,signal:CancellationSignal?):ParcelFileDescriptor{
        if(mode!="r")throw FileNotFoundException("TG Drive's document integration is read-only. Save changes through the app as a new version.")
        val(profile,kind,key)=parse(documentId);if(kind!="file")throw FileNotFoundException("Select a file")
        val b=bundle(profile)
        return try{runBlocking(Dispatchers.IO){
            val job=currentCoroutineContext()[Job];signal?.setOnCancelListener{job?.cancel()}
            try{withTimeout(120_000){
                b.library.awaitReady();val item=b.library.item(key);if(item.deletedAt!=0L)throw FileNotFoundException("File is in Trash")
                b.library.cache!!.lease(item.versionId).use {
                val file=b.library.cache!!.materialize(item,b.library.version(item.versionId),b.library.state.value.itemVaults[key].orEmpty())
                bundle(profile);check(b.library.vaults.isUnlocked(b.library.itemVault(key)))
                ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)
                }
            }}finally{signal?.setOnCancelListener(null)}
        }}catch(e:Exception){throw FileNotFoundException("Open TG Drive, download or pin this file, and retry. ${e.message?.take(120).orEmpty()}")}
    }
    private fun addFolder(out:MatrixCursor,documentId:String,name:String){out.newRow().add(DocumentsContract.Document.COLUMN_DOCUMENT_ID,documentId).add(DocumentsContract.Document.COLUMN_DISPLAY_NAME,name).add(DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.MIME_TYPE_DIR).add(DocumentsContract.Document.COLUMN_FLAGS,0)}
    private fun addItem(out:MatrixCursor,profile:String,item:Item){out.newRow().add(DocumentsContract.Document.COLUMN_DOCUMENT_ID,id(profile,"file",item.id)).add(DocumentsContract.Document.COLUMN_DISPLAY_NAME,item.name).add(DocumentsContract.Document.COLUMN_MIME_TYPE,item.mime).add(DocumentsContract.Document.COLUMN_SIZE,item.size).add(DocumentsContract.Document.COLUMN_LAST_MODIFIED,item.modifiedAt).add(DocumentsContract.Document.COLUMN_FLAGS,0)}
    companion object{
        private val ROOT_COLUMNS=arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID,DocumentsContract.Root.COLUMN_DOCUMENT_ID,DocumentsContract.Root.COLUMN_TITLE,DocumentsContract.Root.COLUMN_SUMMARY,DocumentsContract.Root.COLUMN_FLAGS,DocumentsContract.Root.COLUMN_ICON)
        private val DOCUMENT_COLUMNS=arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED,DocumentsContract.Document.COLUMN_FLAGS)
        fun id(profile:String,kind:String,key:String)="$profile|$kind|$key"
        fun parse(value:String):Triple<String,String,String>{val parts=value.split('|');require(parts.size==3&&parts[0].matches(Regex("[A-Za-z0-9-]{1,80}"))&&parts[1] in setOf("file","folder")&&parts[2].matches(Regex("[A-Za-z0-9_-]{1,120}"))){"Invalid document identifier"};return Triple(parts[0],parts[1],parts[2])}
    }
}
