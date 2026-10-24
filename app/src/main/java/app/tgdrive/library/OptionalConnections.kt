package app.tgdrive.library

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Opt-in copy exports. Endpoints and credentials are bound when the task is approved. */
class OptionalConnections(private val repository: LibraryRepository) {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(30,TimeUnit.SECONDS).readTimeout(5,TimeUnit.MINUTES).writeTimeout(20,TimeUnit.MINUTES).build()
    fun https(value:String):HttpUrl {
        val url=value.toHttpUrl()
        require(url.isHttps&&url.username.isEmpty()&&url.password.isEmpty()&&url.fragment==null&&url.query==null){"Use a final HTTPS endpoint without embedded credentials or a query."}
        return url
    }
    suspend fun bind(task:TransferTask) {
        val prefs=repository.state.value.preferences
        val binding=if(task.kind==TaskKind.WEBDAV)ConnectionBinding(https(prefs.webDavUrl).toString(),prefs.webDavUser,repository.session.secret("webdav-password").orEmpty())
            else ConnectionBinding(https(prefs.sharingUrl).toString(),token=repository.session.secret("sharing-token").orEmpty())
        repository.session.saveSecret("connection:${task.id}",binding.json())
    }
    suspend fun forget(task:TransferTask) {
        repository.session.saveSecret("connection:${task.id}","");repository.session.saveSecret("share-password:${task.id}","")
    }
    suspend fun execute(task:TransferTask)=withContext(Dispatchers.IO) {
        val version=repository.version(task.versionId)
        val item=repository.item(task.itemId).copy(versionId=version.id,size=version.size,sha256=version.sha256)
        val binding=repository.session.secret("connection:${task.id}")?.takeIf{it.isNotEmpty()}?.model<ConnectionBinding>()?:error("Recreate this export to bind its destination settings.")
        val file=repository.cache!!.materialize(item,version,task.vaultId)
        repository.checkAccount(task.accountId)
        repository.setTask(task.copy(state=TaskState.TRANSFERRING,error=""))
        val checksum=file.sha256()
        when(task.kind) {
            TaskKind.WEBDAV -> {
                val base=https(binding.endpoint);val auth=Credentials.basic(binding.user,binding.password,Charsets.UTF_8)
                val folder=base.newBuilder().addPathSegment("TG-Drive").build()
                exchange(Request.Builder().url(folder).header("Authorization",auth).method("MKCOL",byteArrayOf().toRequestBody()).build()){response,_->
                    require(response.code in setOf(200,201,204,405)){"WebDAV folder request failed (${response.code}). Check endpoint and credentials."}
                }
                val target=folder.newBuilder().addPathSegment("${item.id}-${version.id}-${item.name}").build()
                exchange(Request.Builder().url(target).header("Authorization",auth).put(file.asRequestBody("application/octet-stream".toMediaType())).build()){response,_->
                    require(response.code in setOf(200,201,204)){"WebDAV upload failed (${response.code}). Redirects are disabled to protect credentials."}
                }
                exchange(Request.Builder().url(target).header("Authorization",auth).get().build()){response,checkActive->
                    check(response.isSuccessful){"WebDAV accepted the upload but verification failed (${response.code})."}
                    val digest=MessageDigest.getInstance("SHA-256")
                    response.body!!.byteStream().use { input->val b=ByteArray(128*1024);while(true){checkActive();val n=input.read(b);if(n<0)break;digest.update(b,0,n)} }
                    check(digest.digest().joinToString(""){"%02x".format(it)}==checksum){"WebDAV copy checksum did not match."}
                }
                repository.notice("WebDAV copy uploaded and its downloaded content verified.")
            }
            TaskKind.SHARE -> {
                require(binding.token.length>=32){"Enter the sharing server's owner token in Settings."}
                val endpoint=https(binding.endpoint).newBuilder().addPathSegments("api/shares").build()
                val spec=task.payload.model<ShareOptions>();val password=repository.session.secret("share-password:${task.id}").orEmpty()
                val request=Request.Builder().url(endpoint).header("Authorization","Bearer ${binding.token}")
                    .header("X-File-Name",Base64.getUrlEncoder().withoutPadding().encodeToString(item.name.toByteArray()))
                    .header("X-Share-Expires-In",spec.hours.coerceIn(1,168).times(3600).toString())
                    .header("X-Content-SHA256",checksum).header("X-Upload-Id",task.id)
                    .header("X-Share-Password",Base64.getUrlEncoder().withoutPadding().encodeToString(password.toByteArray()))
                    .post(file.asRequestBody("application/octet-stream".toMediaType())).build()
                val result=exchange(request){response,_->
                    require(response.isSuccessful){"Sharing server rejected the export (${response.code}). No link was reported as created."}
                    val value=readLimited(response.body!!.byteStream(),64*1024).decodeToString().model<ShareResult>()
                    val url=value.url.toHttpUrl();val configured=https(binding.endpoint)
                    require(value.id.matches(Regex("[A-Za-z0-9_-]{32,80}"))&&url.isHttps&&url.host==configured.host&&url.port==configured.port&&url.username.isEmpty()&&url.password.isEmpty()){"The server returned an invalid public URL or a different HTTPS origin."}
                    value
                }
                repository.checkAccount(task.accountId)
                repository.mutate{it.put("settings","share:${result.id}",result.json())}
                repository.session.saveSecret("share-owner:${result.id}",binding.json());repository.shareResult(result)
            }
            else -> error("Unsupported optional connection")
        }
    }
    suspend fun revoke(id:String) {
        require(id.matches(Regex("[A-Za-z0-9_-]{32,80}")))
        val binding=repository.session.secret("share-owner:$id")?.takeIf{it.isNotEmpty()}?.model<ConnectionBinding>()?:error("The original sharing connection is required to revoke this link.")
        val url=https(binding.endpoint).newBuilder().addPathSegments("api/shares").addPathSegment(id).build()
        exchange(Request.Builder().url(url).header("Authorization","Bearer ${binding.token}").delete().build()){response,_->require(response.isSuccessful){"Could not revoke this link (${response.code})."}}
        repository.mutate{it.remove("settings","share:$id")};repository.session.saveSecret("share-owner:$id","")
        repository.notice("Future access revoked. Already downloaded copies cannot be recalled.")
    }
    suspend fun testWebDav() {
        val prefs=repository.state.value.preferences;val url=https(prefs.webDavUrl)
        val auth=Credentials.basic(prefs.webDavUser,repository.session.secret("webdav-password").orEmpty(),Charsets.UTF_8)
        exchange(Request.Builder().url(url).header("Authorization",auth).header("Depth","0").method("PROPFIND",byteArrayOf().toRequestBody()).build()){response,_->require(response.code in setOf(200,207)){"WebDAV connection failed (${response.code}). Check final endpoint and credentials."}}
        repository.notice("WebDAV endpoint responded successfully. No file was uploaded.")
    }
    /** The cancellation hook remains attached until response consumption finishes. */
    private suspend fun <T> exchange(request:Request,consume:(Response,()->Unit)->T):T=suspendCancellableCoroutine { continuation->
        val call=client.newCall(request);continuation.invokeOnCancellation{call.cancel()}
        call.enqueue(object:Callback {
            override fun onFailure(call:Call,e:IOException){if(continuation.isActive)continuation.resumeWithException(IOException("Optional service connection failed. Check HTTPS connectivity and certificate.",e))}
            override fun onResponse(call:Call,response:Response){
                try{val value=response.use{consume(it){continuation.context.ensureActive()}};if(continuation.isActive)continuation.resume(value)}
                catch(e:Exception){if(continuation.isActive)continuation.resumeWithException(e)}
            }
        })
    }
}
@kotlinx.serialization.Serializable data class ShareOptions(val hours:Int=24)
@kotlinx.serialization.Serializable private data class ConnectionBinding(val endpoint:String,val user:String="",val password:String="",val token:String="")
