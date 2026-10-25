package app.tgdrive.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.PersistableBundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.tgdrive.SessionBundle
import app.tgdrive.library.*
import kotlinx.coroutines.*
import java.text.DateFormat
import java.util.Date

@Composable fun VaultsPage(state:LibraryState,bundle:SessionBundle){
    var create by remember{mutableStateOf(false)};var unlock by remember{mutableStateOf<VaultDescriptor?>(null)}
    var recoveryKey by remember{mutableStateOf<String?>(null)};val ui=rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=16.dp,end=16.dp,top=16.dp,bottom=32.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
        item{InfoCard("Keep recovery separate","Losing both the vault password and recovery key can permanently prevent access. A Telegram phone code does not recover an encryption key. The vault label itself is not secret.",DriveIcons.Key)}
        item{DriveButton(onClick={create=true},Modifier.fillMaxWidth()){Icon(DriveIcons.Add,null);Text("Create encrypted vault")}}
        items(state.vaults,key={it.id}){vault->DriveCard(Modifier.fillMaxWidth()){Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(vault.label,style=MaterialTheme.typography.titleMedium)
            Text(if(vault.id in state.unlockedVaultIds)"Unlocked on this device" else "Locked",color=MaterialTheme.colorScheme.primary)
            if(vault.id in state.unlockedVaultIds)TextButton(onClick={bundle.actions.run{app.tgdrive.PlaybackService.stopProtected(bundle.library.context);bundle.transfers.pauseProtected();bundle.library.lockVaults()}}){Text("Lock vaults and pause protected work")}
            else DriveButton(onClick={unlock=vault}){Text("Unlock with password or recovery")}
        }}}
        item{InfoCard("Temporary previews are protected","Locking clears assembled preview copies and removes decrypted metadata from the visible library. Exports you explicitly saved outside the app cannot be recalled. Protected background jobs wait for unlocking.")}
    }
    if(create){
        var label by remember{mutableStateOf("")};var password by remember{mutableStateOf("")};var confirmation by remember{mutableStateOf("")};var busy by remember{mutableStateOf(false)}
        AlertDialog(onDismissRequest={if(!busy)create=false},title={Text("Create vault")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
            DriveTextField(label,{label=it},label={Text("Vault label")},singleLine=true,enabled=!busy)
            DriveTextField(password,{password=it},label={Text("Password · at least 12 characters")},visualTransformation=PasswordVisualTransformation(),singleLine=true,enabled=!busy)
            DriveTextField(confirmation,{confirmation=it},label={Text("Confirm password")},visualTransformation=PasswordVisualTransformation(),singleLine=true,enabled=!busy)
            Text("A recovery key is shown once after creation. Save it somewhere independent of this phone.",style=MaterialTheme.typography.bodySmall)
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        }},confirmButton={TextButton(onClick={ui.launch{busy=true;try{recoveryKey=withContext(Dispatchers.Default){bundle.library.createVault(label,password.toCharArray())};password="";confirmation="";create=false}catch(e:Exception){bundle.library.report(e)}finally{busy=false}}},enabled=label.isNotBlank()&&password.length>=12&&password==confirmation&&!busy){Text("Create vault")}},dismissButton={TextButton(onClick={create=false},enabled=!busy){Text("Cancel")}})
    }
    unlock?.let{vault->
        var value by remember(vault.id){mutableStateOf("")};var recovery by remember{mutableStateOf(false)};var busy by remember{mutableStateOf(false)}
        AlertDialog(onDismissRequest={if(!busy)unlock=null},title={Text("Unlock ${vault.label}")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
            DriveCheckboxRow("Use recovery key",recovery,{recovery=it},enabled=!busy)
            DriveTextField(value,{value=it},label={Text(if(recovery)"64-character recovery key" else "Vault password")},visualTransformation=PasswordVisualTransformation(),enabled=!busy)
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        }},confirmButton={TextButton(onClick={ui.launch{busy=true;try{withContext(Dispatchers.Default){bundle.library.unlockVault(vault.id,value.toCharArray(),recovery)};value="";unlock=null}catch(e:Exception){bundle.library.report(e)}finally{busy=false}}},enabled=value.isNotBlank()&&!busy){Text("Unlock")}},dismissButton={TextButton(onClick={unlock=null},enabled=!busy){Text("Cancel")}})
    }
    recoveryKey?.let{key->
        var saved by remember{mutableStateOf(false)};var confirmCopy by remember{mutableStateOf(false)};val context=LocalContext.current
        AlertDialog(onDismissRequest={},title={Text("Save your recovery key")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text("Anyone holding this key and the encrypted files can unlock this vault. Do not send it to support, chat or the repository.")
            SelectionContainer{Text(key.chunked(8).joinToString(" "),style=MaterialTheme.typography.bodyLarge)}
            TextButton(onClick={confirmCopy=true}){Text("Copy key…")}
            DriveCheckboxRow("I saved the key outside this app.",saved,{saved=it})
        }},confirmButton={TextButton(onClick={recoveryKey=null},enabled=saved){Text("I saved it")}})
        if(confirmCopy)ConfirmDialog("Copy recovery key?","Other software may have access to your clipboard. Prefer a secure password manager or a written copy.","Copy sensitive key",{confirmCopy=false}){
            val clip=ClipData.newPlainText("Vault recovery key",key)
            clip.description.extras=PersistableBundle().apply{putBoolean("android.content.extra.IS_SENSITIVE",true)}
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip);confirmCopy=false
        }
    }
}

@Composable fun ConnectionsPage(state:LibraryState,bundle:SessionBundle){
    var webdav by remember{mutableStateOf(state.preferences.webDavUrl)};var user by remember{mutableStateOf(state.preferences.webDavUser)}
    var password by remember{mutableStateOf("")};var server by remember{mutableStateOf(state.preferences.sharingUrl)};var token by remember{mutableStateOf("")}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=16.dp,end=16.dp,top=16.dp,bottom=32.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
        item{InfoCard("Your own HTTPS endpoints","Ordinary Telegram uploads do not need these settings. WebDAV and public links require services you choose and control. No Telegram session is sent to either service.",DriveIcons.Link)}
        item{Text("WebDAV backup copy",style=MaterialTheme.typography.titleLarge)}
        item{DriveTextField(webdav,{webdav=it},label={Text("Final HTTPS WebDAV endpoint")},singleLine=true,modifier=Modifier.fillMaxWidth())}
        item{DriveTextField(user,{user=it},label={Text("WebDAV username")},singleLine=true,modifier=Modifier.fillMaxWidth())}
        item{DriveTextField(password,{password=it},label={Text("Password · blank keeps existing")},singleLine=true,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())}
        item{DriveButton(modifier=Modifier.fillMaxWidth(),onClick={val p=password;bundle.actions.run{
            if(webdav.isNotBlank())bundle.connections.https(webdav.trim())
            if(p.isNotEmpty())bundle.library.saveAccountSecret("webdav-password",p)
            bundle.library.updatePreferences{it.copy(webDavUrl=webdav.trim(),webDavUser=user.trim())};bundle.library.notice("WebDAV configuration saved. No file has been exported.")
        };password=""}){Text("Save WebDAV settings")}}
        item{TextButton(onClick={bundle.actions.run{bundle.connections.testWebDav()}},enabled=state.preferences.webDavUrl.isNotBlank()){Text("Test saved WebDAV connection")}}
        item{HorizontalDivider();Text("Public-copy sharing server",style=MaterialTheme.typography.titleLarge)}
        item{DriveTextField(server,{server=it},label={Text("HTTPS sharing origin")},singleLine=true,modifier=Modifier.fillMaxWidth())}
        item{DriveTextField(token,{token=it},label={Text("Owner token · blank keeps existing")},singleLine=true,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())}
        item{DriveButton(modifier=Modifier.fillMaxWidth(),onClick={val t=token;bundle.actions.run{
            if(server.isNotBlank())bundle.connections.https(server.trim())
            if(t.isNotEmpty()){require(t.length>=32&&t.none{it.isISOControl()}){"The owner token needs at least 32 characters without control characters."};bundle.library.saveAccountSecret("sharing-token",t)}
            bundle.library.updatePreferences{it.copy(sharingUrl=server.trim())};bundle.library.notice("Sharing endpoint saved. A link is only created when you explicitly export a file.")
        };token=""}){Text("Save sharing settings")}}
        item{InfoCard("Self-hosted component included","The repository's sharing-server folder contains the service, tests and HTTPS proxy example. It stores explicit exported copies, never Telegram login sessions. Configuring a URL does not deploy a server.")}
        if(state.shares.isNotEmpty())item{Text("Created links",style=MaterialTheme.typography.titleLarge)}
        items(state.shares,key={it.id}){share->DriveCard(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            SelectionContainer{Text(share.url,style=MaterialTheme.typography.bodySmall)}
            Text("Expires ${DateFormat.getDateTimeInstance().format(Date(share.expiresAt))}",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={bundle.actions.run{bundle.connections.revoke(share.id)}}){Text("Revoke future access")}
        }}}
        item{Spacer(Modifier.height(20.dp))}
    }
}
