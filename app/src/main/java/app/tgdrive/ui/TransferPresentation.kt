package app.tgdrive.ui

import app.tgdrive.library.*
import java.util.Calendar

enum class TransferFilter(val label:String) { ALL("All transfers"), ACTIVE("Active"), DONE("Done"), FAILED("Failed") }
internal data class TransferSection(val key:String,val title:String,val tasks:List<TransferTask>)
internal val runningTransferStates=setOf(TaskState.PREPARING,TaskState.TRANSFERRING,TaskState.SYNCING_METADATA)
internal val finishedTransferStates=setOf(TaskState.COMPLETED,TaskState.CANCELLED)
internal fun transferSections(tasks:List<TransferTask>,filter:TransferFilter,time:Long=now()):List<TransferSection> {
    val today=Calendar.getInstance().apply{timeInMillis=time;set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)}.timeInMillis
    val visible=tasks.filter{when(filter){
        TransferFilter.ALL->true
        TransferFilter.ACTIVE->it.state !in finishedTransferStates&&it.state!=TaskState.FAILED
        TransferFilter.DONE->it.state==TaskState.COMPLETED
        TransferFilter.FAILED->it.state==TaskState.FAILED
    }}
    val queue=compareByDescending<TransferTask>{it.priority}.thenBy{it.createdAt}.thenBy{it.id}
    val history=compareByDescending<TransferTask>{it.updatedAt.takeIf{date->date>0}?:it.createdAt}.thenBy{it.id}
    fun completionDate(task:TransferTask)=task.updatedAt.takeIf{it>0}?:task.createdAt
    return listOf(
        TransferSection("active","Active",visible.filter{it.state in runningTransferStates}.sortedWith(queue)),
        TransferSection("waiting","Up next",visible.filter{it.state !in runningTransferStates&&it.state !in finishedTransferStates&&it.state !in setOf(TaskState.FAILED,TaskState.PAUSED)}.sortedWith(queue)),
        TransferSection("paused","Paused",visible.filter{it.state==TaskState.PAUSED}.sortedWith(queue)),
        TransferSection("failed","Needs attention",visible.filter{it.state==TaskState.FAILED}.sortedWith(history)),
        TransferSection("today","Completed today",visible.filter{it.state==TaskState.COMPLETED&&completionDate(it)>=today}.sortedWith(history)),
        TransferSection("earlier","Completed earlier",visible.filter{it.state==TaskState.COMPLETED&&completionDate(it)<today}.sortedWith(history)),
        TransferSection("cancelled","Cancelled",visible.filter{it.state==TaskState.CANCELLED}.sortedWith(history)),
    ).filter{it.tasks.isNotEmpty()}
}

internal fun transferCanResume(task:TransferTask,cooldown:Long,time:Long=now())=task.state in setOf(TaskState.PAUSED,TaskState.FAILED,TaskState.RETRY_WAIT,TaskState.WAITING_FOR_DEVICE)&&
    (task.kind!=TaskKind.UPLOAD||cooldown<=time)

internal fun completedTransferLabel(kind:TaskKind)=when(kind){
    TaskKind.UPLOAD->"Saved";TaskKind.DOWNLOAD->"Downloaded";TaskKind.PIN->"Available offline";TaskKind.VERIFY->"Verified"
    TaskKind.EXPORT->"Exported";TaskKind.WEBDAV->"Copied";TaskKind.SHARE->"Link created";TaskKind.OCR->"Text extracted"
}

internal data class TransferCallbacks(
    val pause:(String)->Unit={},val retry:(String)->Unit={},val prioritize:(String)->Unit={},val cancel:(String)->Unit={},
    val start:()->Unit={},val pauseAll:()->Unit={},val clear:()->Unit={},val rules:()->Unit={},val vaults:()->Unit={},
)
