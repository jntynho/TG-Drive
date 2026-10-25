package app.tgdrive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tgdrive.R
import app.tgdrive.data.*
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveScreen(
    state: DriveState, folder: String, onFolder: (String) -> Unit,
    configure: (String, String) -> Unit, login: (String) -> Unit, refresh: () -> Unit,
    createFolder: (String) -> Unit, delete: (DriveFile) -> Unit, logout: () -> Unit,
    reconnect: () -> Unit, clearNotice: () -> Unit, upload: () -> Unit, download: (DriveFile) -> Unit,
    editCredentials: () -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    var privacy by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<DriveFile?>(null) }
    var signOut by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var order by rememberSaveable { mutableStateOf(SortOrder.NEWEST) }
    var sorting by remember { mutableStateOf(false) }
    val ready = state.login == LoginStep.READY
    val transferring = state.transfer != null
    LaunchedEffect(state.error, state.info) {
        val message = state.error ?: state.info
        if (message != null) { snackbar.showSnackbar(message); clearNotice() }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.CloudQueue, null, tint = MaterialTheme.colorScheme.primary)
                    Text("TG Drive", fontWeight = FontWeight.Bold)
                }
            }, actions = {
                IconButton(onClick = { privacy = true }) { Icon(Icons.Outlined.Shield, stringResource(R.string.privacy)) }
                if (ready) {
                    IconButton(onClick = refresh, enabled = !state.syncing && !state.busy) { Icon(Icons.Outlined.Refresh, stringResource(R.string.refresh)) }
                    IconButton(onClick = { signOut = true }, enabled = !transferring && !state.busy) { Icon(Icons.AutoMirrored.Outlined.Logout, stringResource(R.string.sign_out)) }
                }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        },
        floatingActionButton = {
            if (ready) ExtendedFloatingActionButton(onClick = { if (!transferring && !state.busy) upload() }, expanded = true,
                icon = { Icon(Icons.Outlined.Add, null) }, text = { Text(stringResource(R.string.upload)) },
                containerColor = if (transferring) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer)
        },
    ) { padding ->
        if (!ready) {
            AuthPanel(state, configure, login, reconnect, editCredentials, Modifier.padding(padding))
        } else {
            val files = remember(state.files, folder, query, order) { selectFiles(state.files, folder, query, order) }
            val folders = (state.folders + state.files.map { it.folder }).filter { it.isNotEmpty() }.sorted()
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Text(stringResource(R.string.your_files), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.connected_as, state.account), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatCard(Icons.Outlined.Description, state.files.size.toString(), stringResource(R.string.files), Modifier.weight(1f))
                        StatCard(Icons.Outlined.CloudDone, formatBytes(state.files.sumOf { it.size }), stringResource(R.string.stored), Modifier.weight(1f))
                    }
                }
                item {
                    DriveTextField(value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text(stringResource(R.string.search)) }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        shape = DriveStyle.CardShape, modifier = Modifier.fillMaxWidth())
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        item { DriveFilterChip(selected = folder.isEmpty(), onClick = { onFolder(""); query = "" }, label = { Text(stringResource(R.string.home)) }, leadingIcon = { Icon(Icons.Outlined.Home, null, Modifier.size(18.dp)) }) }
                        items(folders, key = { it }) { name -> DriveFilterChip(selected = folder == name, onClick = { onFolder(name); query = "" }, label = { Text(name) }, leadingIcon = { Icon(Icons.Outlined.Folder, null, Modifier.size(18.dp)) }) }
                        item { DriveAssistChip(onClick = { newFolder = true }, label = { Text(stringResource(R.string.new_folder)) }, leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null, Modifier.size(18.dp)) }) }
                    }
                }
                if (state.syncing) item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.syncing), style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (transferring) item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(state.transfer.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                            if (state.progress != null) LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                            else LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (query.isNotBlank()) stringResource(R.string.results) else folder.ifEmpty { stringResource(R.string.home) }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Box {
                            IconButton(onClick = { sorting = true }) { Icon(Icons.AutoMirrored.Outlined.Sort, stringResource(R.string.sort)) }
                            DropdownMenu(expanded = sorting, onDismissRequest = { sorting = false }) {
                                SortOrder.entries.forEach { value -> DropdownMenuItem(text = { Text(stringResource(when(value) { SortOrder.NEWEST -> R.string.newest; SortOrder.NAME -> R.string.by_name; SortOrder.SIZE -> R.string.by_size })) }, onClick = { order = value; sorting = false }) }
                            }
                        }
                    }
                }
                if (files.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(if (query.isBlank()) Icons.Outlined.CloudUpload else Icons.Outlined.SearchOff, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(if (query.isBlank()) R.string.empty_title else R.string.no_results), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(if (query.isBlank()) R.string.empty_body else R.string.try_search), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(files, key = { it.messageId }) { file ->
                    FileCard(file, enabled = !transferring && !state.busy, download = { download(file) }, delete = { deleting = file })
                }
                item { Text(stringResource(R.string.storage_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
    if (privacy) AlertDialog(onDismissRequest = { privacy = false }, title = { Text(stringResource(R.string.privacy)) },
        text = { Text(stringResource(R.string.privacy_body), Modifier.verticalScroll(rememberScrollState())) }, confirmButton = { TextButton(onClick = { privacy = false }) { Text(stringResource(R.string.close)) } })
    if (newFolder) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { newFolder = false }, title = { Text(stringResource(R.string.new_folder)) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DriveTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.folder_name)) })
                Text(stringResource(R.string.folder_note), style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = { createFolder(name); newFolder = false }, enabled = FileMetadata.validFolder(name.trim())) { Text(stringResource(R.string.create)) } }, dismissButton = { TextButton(onClick = { newFolder = false }) { Text(stringResource(R.string.cancel)) } })
    }
    deleting?.let { file -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.delete_title)) }, text = { Text(stringResource(R.string.delete_body, file.name)) },
        confirmButton = { TextButton(onClick = { delete(file); deleting = null }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } }) }
    if (signOut) AlertDialog(onDismissRequest = { signOut = false }, title = { Text(stringResource(R.string.sign_out)) }, text = { Text(stringResource(R.string.sign_out_body)) },
        confirmButton = { TextButton(onClick = { logout(); signOut = false; onFolder("") }) { Text(stringResource(R.string.sign_out)) } },
        dismissButton = { TextButton(onClick = { signOut = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
fun AuthPanel(state: DriveState, configure: (String, String) -> Unit, login: (String) -> Unit, reconnect: () -> Unit, editCredentials: () -> Unit, modifier: Modifier = Modifier) {
    var apiId by remember { mutableStateOf("") }
    var apiHash by remember { mutableStateOf("") }
    val uri = LocalUriHandler.current
    Column(modifier.fillMaxSize().background(DriveStyle.SignInCanvas).imePadding().verticalScroll(rememberScrollState()).padding(horizontal=16.dp,vertical=24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(top=8.dp,bottom=8.dp), horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.spacedBy(20.dp)) {
            Icon(Icons.Outlined.CloudQueue, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Welcome to TG Drive", style=MaterialTheme.typography.headlineLarge, textAlign=androidx.compose.ui.text.style.TextAlign.Center)
        }
        androidx.compose.animation.AnimatedContent(state.login, transitionSpec={horizontalNavigation(targetState != LoginStep.CONFIGURE)},label="sign-in-step") { step ->
            var value by remember(step) { mutableStateOf("") }
        Column(Modifier.fillMaxWidth(), verticalArrangement=Arrangement.spacedBy(16.dp)) {
                state.authError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                when (step) {
                    LoginStep.CONFIGURE -> {
                        Text(stringResource(R.string.configure), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.configure_body), style = MaterialTheme.typography.bodyMedium)
                        DriveTextField(apiId, { apiId = it }, enabled = !state.busy, label = { Text("API ID") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                        DriveTextField(apiHash, { apiHash = it }, enabled = !state.busy, label = { Text("API hash") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                        DriveButton(onClick = { configure(apiId.trim(), apiHash.trim()) }, enabled = apiId.isNotBlank() && apiHash.isNotBlank() && !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.continue_label)) }
                        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        TextButton(onClick = { uri.openUri("https://my.telegram.org/apps") }) { Text(stringResource(R.string.get_api)) }
                    }
                    LoginStep.PHONE, LoginStep.CODE, LoginStep.PASSWORD -> {
                        val label = when (step) { LoginStep.PHONE -> R.string.phone; LoginStep.CODE -> R.string.code; else -> R.string.password }
                        Text(stringResource(R.string.connect_telegram), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(when (step) { LoginStep.PHONE -> R.string.phone_body; LoginStep.CODE -> R.string.code_body; else -> R.string.password_body }))
                        DriveTextField(value, { value = it }, label = { Text(stringResource(label)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            visualTransformation = if (step == LoginStep.PASSWORD) PasswordVisualTransformation() else VisualTransformation.None,
                            keyboardOptions = KeyboardOptions(keyboardType = when(step) { LoginStep.PHONE -> KeyboardType.Phone; LoginStep.CODE -> KeyboardType.Number; else -> KeyboardType.Password }))
                        DriveButton(onClick = { login(value) }, enabled = value.isNotBlank() && !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.continue_label)) }
                        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (step == LoginStep.PHONE) TextButton(onClick = editCredentials, enabled = !state.busy) { Text(stringResource(R.string.edit_api)) }
                    }
                    LoginStep.CLOSED -> {
                        Text(stringResource(R.string.signed_out))
                        DriveButton(onClick = reconnect) { Text(stringResource(R.string.connect_telegram)) }
                        TextButton(onClick = editCredentials) { Text(stringResource(R.string.edit_api)) }
                    }
                    LoginStep.CONNECTION_ERROR -> {
                        Text(stringResource(R.string.connection_failed), style = MaterialTheme.typography.titleLarge)
                        DriveButton(onClick = reconnect, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.retry_connection)) }
                        TextButton(onClick = editCredentials, enabled = !state.busy) { Text(stringResource(R.string.edit_api)) }
                    }
                    LoginStep.OTHER_DEVICE -> {
                        Text(stringResource(R.string.confirm_other_device))
                        Text(state.confirmationLink, style = MaterialTheme.typography.bodySmall)
                    }
                    LoginStep.UNSUPPORTED -> Text(stringResource(R.string.unsupported_auth))
                    else -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.connecting)) }
                }
                if (state.network != TelegramNetwork.UNKNOWN) Text(stringResource(when (state.network) {
                    TelegramNetwork.WAITING_FOR_NETWORK -> R.string.network_unavailable
                    TelegramNetwork.CONNECTING -> R.string.network_connecting
                    TelegramNetwork.CONNECTING_TO_PROXY -> R.string.network_proxy
                    TelegramNetwork.UPDATING -> R.string.network_updating
                    else -> R.string.network_ready
                }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(stringResource(R.string.auth_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("TG Drive · ${app.tgdrive.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun StatCard(icon: ImageVector, value: String, label: String, modifier: Modifier) {
    Card(modifier, shape = DriveStyle.CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FileCard(file: DriveFile, enabled: Boolean, download: () -> Unit, delete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = DriveStyle.CardShape) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val icon = when {
                file.mimeType.startsWith("image/") -> Icons.Outlined.Image
                file.mimeType.startsWith("video/") -> Icons.Outlined.Videocam
                file.mimeType.startsWith("audio/") -> Icons.Outlined.AudioFile
                else -> Icons.AutoMirrored.Outlined.InsertDriveFile
            }
            Box(Modifier.size(46.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(file.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${formatBytes(file.size)} · ${DateFormat.getDateInstance(DateFormat.SHORT).format(Date(file.date * 1000L))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (file.folder.isNotEmpty()) Text(file.folder, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Box {
                IconButton(onClick = { menu = true }, enabled = enabled) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.file_actions)) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.download)) }, leadingIcon = { Icon(Icons.Outlined.Download, null) }, onClick = { menu = false; download() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) }, onClick = { menu = false; delete() })
                }
            }
        }
    }
}
