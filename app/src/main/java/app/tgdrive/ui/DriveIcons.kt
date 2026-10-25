package app.tgdrive.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Curated Lucide vectors; see third_party/lucide for source and license. */
object DriveIcons {
    val AccountCircle: ImageVector by lazy { vector("circle-user-round", "M17.925 20.056a6 6 0 0 0-11.851.001", "M8.0,11.0a4.0,4.0 0 1 0 8.0,0a4.0,4.0 0 1 0 -8.0,0", "M2.0,12.0a10.0,10.0 0 1 0 20.0,0a10.0,10.0 0 1 0 -20.0,0") }
    val Add: ImageVector by lazy { vector("plus", "M5 12h14", "M12 5v14") }
    val ArrowBack: ImageVector by lazy { vector("arrow-left", "m12 19-7-7 7-7", "M19 12H5") }
    val AudioFile: ImageVector by lazy { vector("file-music", "M11.65 22H18a2 2 0 0 0 2-2V8a2.4 2.4 0 0 0-.706-1.706l-3.588-3.588A2.4 2.4 0 0 0 14 2H6a2 2 0 0 0-2 2v10.35", "M14 2v5a1 1 0 0 0 1 1h5", "M8 20v-7l3 1.474", "M4.0,20.0a2.0,2.0 0 1 0 4.0,0a2.0,2.0 0 1 0 -4.0,0") }
    val AutoAwesome: ImageVector by lazy { vector("sparkles", "M11.017 2.814a1 1 0 0 1 1.966 0l1.051 5.558a2 2 0 0 0 1.594 1.594l5.558 1.051a1 1 0 0 1 0 1.966l-5.558 1.051a2 2 0 0 0-1.594 1.594l-1.051 5.558a1 1 0 0 1-1.966 0l-1.051-5.558a2 2 0 0 0-1.594-1.594l-5.558-1.051a1 1 0 0 1 0-1.966l5.558-1.051a2 2 0 0 0 1.594-1.594z", "M20 2v4", "M22 4h-4", "M2.0,20.0a2.0,2.0 0 1 0 4.0,0a2.0,2.0 0 1 0 -4.0,0") }
    val Backup: ImageVector by lazy { vector("cloud-backup", "M21 15.251A4.5 4.5 0 0 0 17.5 8h-1.79A7 7 0 1 0 3 13.607", "M7 11v4h4", "M8 19a5 5 0 0 0 9-3 4.5 4.5 0 0 0-4.5-4.5 4.82 4.82 0 0 0-3.41 1.41L7 15") }
    val Check: ImageVector by lazy { vector("check", "M20 6 9 17l-5-5") }
    val CheckCircle: ImageVector by lazy { vector("circle-check", "M2.0,12.0a10.0,10.0 0 1 0 20.0,0a10.0,10.0 0 1 0 -20.0,0", "m16 9-5.5 5.5L8 12") }
    val ChevronRight: ImageVector by lazy { vector("chevron-right", "m9 18 6-6-6-6") }
    val CleaningServices: ImageVector by lazy { vector("brush-cleaning", "m16 22-1-4", "M19 14a1 1 0 0 0 1-1v-1a2 2 0 0 0-2-2h-3a1 1 0 0 1-1-1V4a2 2 0 0 0-4 0v5a1 1 0 0 1-1 1H6a2 2 0 0 0-2 2v1a1 1 0 0 0 1 1", "M19 14H5l-1.973 6.767A1 1 0 0 0 4 22h16a1 1 0 0 0 .973-1.233z", "m8 22 1-4") }
    val Close: ImageVector by lazy { vector("x", "M18 6 6 18", "m6 6 12 12") }
    val CloudDone: ImageVector by lazy { vector("cloud-check", "m17 15-5.5 5.5L9 18", "M5.516 16.07A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 3.501 7.327") }
    val CloudQueue: ImageVector by lazy { vector("cloud", "M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9Z") }
    val CloudSync: ImageVector by lazy { vector("cloud-sync", "m17 18-1.535 1.605a5 5 0 0 1-8-1.5", "M17 22v-4h-4", "M20.996 15.251A4.5 4.5 0 0 0 17.495 8h-1.79a7 7 0 1 0-12.709 5.607", "M7 10v4h4", "m7 14 1.535-1.605a5 5 0 0 1 8 1.5") }
    val CloudUpload: ImageVector by lazy { vector("cloud-upload", "M12 13v8", "M4 14.899A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 2.5 8.242", "m8 17 4-4 4 4") }
    val CreateNewFolder: ImageVector by lazy { vector("folder-plus", "M12 10v6", "M9 13h6", "M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z") }
    val DeleteForever: ImageVector by lazy { vector("trash", "M10 11v6", "M14 11v6", "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6", "M3 6h18", "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2") }
    val DeleteOutline: ImageVector by lazy { vector("trash", "M10 11v6", "M14 11v6", "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6", "M3 6h18", "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2") }
    val Description: ImageVector by lazy { vector("file-text", "M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z", "M14 2v5a1 1 0 0 0 1 1h5", "M10 9H8", "M16 13H8", "M16 17H8") }
    val DocumentScanner: ImageVector by lazy { vector("scan-text", "M3 7V5a2 2 0 0 1 2-2h2", "M17 3h2a2 2 0 0 1 2 2v2", "M21 17v2a2 2 0 0 1-2 2h-2", "M7 21H5a2 2 0 0 1-2-2v-2", "M7 8h8", "M7 12h10", "M7 16h6") }
    val Download: ImageVector by lazy { vector("download", "M12 15V3", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "m7 10 5 5 5-5") }
    val DriveFileMove: ImageVector by lazy { vector("folder-input", "M2 9V5a2 2 0 0 1 2-2h3.9a2 2 0 0 1 1.69.9l.81 1.2a2 2 0 0 0 1.67.9H20a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-1", "M2 13h10", "m9 16 3-3-3-3") }
    val Edit: ImageVector by lazy { vector("pencil", "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z", "m15 5 4 4") }
    val EnhancedEncryption: ImageVector by lazy { vector("shield-check", "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z", "m9 12 2 2 4-4") }
    val FactCheck: ImageVector by lazy { vector("list-checks", "M13 5h8", "M13 12h8", "M13 19h8", "m3 17 2 2 4-4", "m3 7 2 2 4-4") }
    val FileOpen: ImageVector by lazy { vector("file-input", "M4 11V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.706.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-1", "M14 2v5a1 1 0 0 0 1 1h5", "M2 15h10", "m9 18 3-3-3-3") }
    val Folder: ImageVector by lazy { vector("folder", "M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z") }
    val Fullscreen: ImageVector by lazy { vector("maximize", "M8 3H5a2 2 0 0 0-2 2v3", "M21 8V5a2 2 0 0 0-2-2h-3", "M3 16v3a2 2 0 0 0 2 2h3", "M16 21h3a2 2 0 0 0 2-2v-3") }
    val GridView: ImageVector by lazy { vector("grid-2x2", "M12 3v18", "M3 12h18", "M5.0,3.0h14.0a2.0,2.0 0 0 1 2.0,2.0v14.0a2.0,2.0 0 0 1 -2.0,2.0h-14.0a2.0,2.0 0 0 1 -2.0,-2.0v-14.0a2.0,2.0 0 0 1 2.0,-2.0Z") }
    val HealthAndSafety: ImageVector by lazy { vector("heart-pulse", "M2 9.5a5.5 5.5 0 0 1 9.591-3.676.56.56 0 0 0 .818 0A5.49 5.49 0 0 1 22 9.5c0 2.29-1.5 4-3 5.5l-5.492 5.313a2 2 0 0 1-3 .019L5 15c-1.5-1.5-3-3.2-3-5.5", "M3.22 13H9.5l.5-1 2 4.5 2-7 1.5 3.5h5.27") }
    val History: ImageVector by lazy { vector("rotate-ccw", "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5") }
    val Home: ImageVector by lazy { vector("house", "M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8", "M3 10a2 2 0 0 1 .709-1.528l7-6a2 2 0 0 1 2.582 0l7 6A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z") }
    val Image: ImageVector by lazy { vector("image", "M5.0,3.0h14.0a2.0,2.0 0 0 1 2.0,2.0v14.0a2.0,2.0 0 0 1 -2.0,2.0h-14.0a2.0,2.0 0 0 1 -2.0,-2.0v-14.0a2.0,2.0 0 0 1 2.0,-2.0Z", "M7.0,9.0a2.0,2.0 0 1 0 4.0,0a2.0,2.0 0 1 0 -4.0,0", "m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21") }
    val Info: ImageVector by lazy { vector("info", "M2.0,12.0a10.0,10.0 0 1 0 20.0,0a10.0,10.0 0 1 0 -20.0,0", "M12 16v-4", "M12 8h.01") }
    val InsertDriveFile: ImageVector by lazy { vector("file", "M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z", "M14 2v5a1 1 0 0 0 1 1h5") }
    val Key: ImageVector by lazy { vector("key-round", "M2.586 17.414A2 2 0 0 0 2 18.828V21a1 1 0 0 0 1 1h3a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h1a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h.172a2 2 0 0 0 1.414-.586l.814-.814a6.5 6.5 0 1 0-4-4z", "M16.0,7.5a0.5,0.5 0 1 0 1.0,0a0.5,0.5 0 1 0 -1.0,0") }
    val KeyboardArrowRight: ImageVector by lazy { vector("chevron-right", "m9 18 6-6-6-6") }
    val Label: ImageVector by lazy { vector("tag", "M12.586 2.586A2 2 0 0 0 11.172 2H4a2 2 0 0 0-2 2v7.172a2 2 0 0 0 .586 1.414l8.704 8.704a2.426 2.426 0 0 0 3.42 0l6.58-6.58a2.426 2.426 0 0 0 0-3.42z", "M7.0,7.5a0.5,0.5 0 1 0 1.0,0a0.5,0.5 0 1 0 -1.0,0") }
    val Link: ImageVector by lazy { vector("link", "M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71", "M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71") }
    val Lock: ImageVector by lazy { vector("lock-keyhole", "M11.0,16.0a1.0,1.0 0 1 0 2.0,0a1.0,1.0 0 1 0 -2.0,0", "M5.0,10.0h14.0a2.0,2.0 0 0 1 2.0,2.0v8.0a2.0,2.0 0 0 1 -2.0,2.0h-14.0a2.0,2.0 0 0 1 -2.0,-2.0v-8.0a2.0,2.0 0 0 1 2.0,-2.0Z", "M7 10V7a5 5 0 0 1 10 0v3") }
    val LockOpen: ImageVector by lazy { vector("lock-keyhole-open", "M11.0,16.0a1.0,1.0 0 1 0 2.0,0a1.0,1.0 0 1 0 -2.0,0", "M5.0,10.0h14.0a2.0,2.0 0 0 1 2.0,2.0v8.0a2.0,2.0 0 0 1 -2.0,2.0h-14.0a2.0,2.0 0 0 1 -2.0,-2.0v-8.0a2.0,2.0 0 0 1 2.0,-2.0Z", "M7 10V7a5 5 0 0 1 9.33-2.5") }
    val Logout: ImageVector by lazy { vector("log-out", "m16 17 5-5-5-5", "M21 12H9", "M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4") }
    val ManageSearch: ImageVector by lazy { vector("search-check", "m8 11 2 2 4-4", "M3.0,11.0a8.0,8.0 0 1 0 16.0,0a8.0,8.0 0 1 0 -16.0,0", "m21 21-4.3-4.3") }
    val Merge: ImageVector by lazy { vector("merge", "m8 6 4-4 4 4", "M12 2v10.3a4 4 0 0 1-1.172 2.872L4 22", "m20 22-5-5") }
    val MoreHoriz: ImageVector by lazy { vector("ellipsis", "M11.0,12.0a1.0,1.0 0 1 0 2.0,0a1.0,1.0 0 1 0 -2.0,0", "M18.0,12.0a1.0,1.0 0 1 0 2.0,0a1.0,1.0 0 1 0 -2.0,0", "M4.0,12.0a1.0,1.0 0 1 0 2.0,0a1.0,1.0 0 1 0 -2.0,0") }
    val MoreVert: ImageVector by lazy { vector("ellipsis-vertical", "M11.0,12.0a1.0,1.0 0 1 0 2.0,0a1.0,1.0 0 1 0 -2.0,0", "M11.0,5.0a1.0,1.0 0 1 0 2.0,0a1.0,1.0 0 1 0 -2.0,0", "M11.0,19.0a1.0,1.0 0 1 0 2.0,0a1.0,1.0 0 1 0 -2.0,0") }
    val MoveToInbox: ImageVector by lazy { vector("inbox", "M22 12 16 12 14 15 10 15 8 12 2 12", "M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z") }
    val OfflinePin: ImageVector by lazy { vector("pin", "M12 17v5", "M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V7a1 1 0 0 1 1-1 2 2 0 0 0 0-4H8a2 2 0 0 0 0 4 1 1 0 0 1 1 1z") }
    val Person: ImageVector by lazy { vector("user-round", "M7.0,8.0a5.0,5.0 0 1 0 10.0,0a5.0,5.0 0 1 0 -10.0,0", "M20 21a8 8 0 0 0-16 0") }
    val PersonAdd: ImageVector by lazy { vector("user-round-plus", "M2 21a8 8 0 0 1 13.292-6", "M5.0,8.0a5.0,5.0 0 1 0 10.0,0a5.0,5.0 0 1 0 -10.0,0", "M19 16v6", "M22 19h-6") }
    val PhotoLibrary: ImageVector by lazy { vector("images", "m22 11-1.296-1.296a2.4 2.4 0 0 0-3.408 0L11 16", "M4 8a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2", "M12.0,7.0a1.0,1.0 0 1 0 2.0,0a1.0,1.0 0 1 0 -2.0,0", "M10.0,2.0h10.0a2.0,2.0 0 0 1 2.0,2.0v10.0a2.0,2.0 0 0 1 -2.0,2.0h-10.0a2.0,2.0 0 0 1 -2.0,-2.0v-10.0a2.0,2.0 0 0 1 2.0,-2.0Z") }
    val PictureAsPdf: ImageVector by lazy { vector("file-text", "M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z", "M14 2v5a1 1 0 0 0 1 1h5", "M10 9H8", "M16 13H8", "M16 17H8") }
    val PlayArrow: ImageVector by lazy { vector("play", "M5 5a2 2 0 0 1 3.008-1.728l11.997 6.998a2 2 0 0 1 .003 3.458l-12 7A2 2 0 0 1 5 19z") }
    val PlayCircle: ImageVector by lazy { vector("circle-play", "M9 9.003a1 1 0 0 1 1.517-.859l4.997 2.997a1 1 0 0 1 0 1.718l-4.997 2.997A1 1 0 0 1 9 14.996z", "M2.0,12.0a10.0,10.0 0 1 0 20.0,0a10.0,10.0 0 1 0 -20.0,0") }
    val Refresh: ImageVector by lazy { vector("refresh-cw", "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8", "M21 3v5h-5", "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16", "M8 16H3v5") }
    val Restore: ImageVector by lazy { vector("rotate-ccw", "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5") }
    val SaveAlt: ImageVector by lazy { vector("download", "M12 15V3", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "m7 10 5 5 5-5") }
    val Search: ImageVector by lazy { vector("search", "m21 21-4.34-4.34", "M3.0,11.0a8.0,8.0 0 1 0 16.0,0a8.0,8.0 0 1 0 -16.0,0") }
    val SearchOff: ImageVector by lazy { vector("search-x", "m13.5 8.5-5 5", "m8.5 8.5 5 5", "M3.0,11.0a8.0,8.0 0 1 0 16.0,0a8.0,8.0 0 1 0 -16.0,0", "m21 21-4.3-4.3") }
    val Share: ImageVector by lazy { vector("share-2", "M15.0,5.0a3.0,3.0 0 1 0 6.0,0a3.0,3.0 0 1 0 -6.0,0", "M3.0,12.0a3.0,3.0 0 1 0 6.0,0a3.0,3.0 0 1 0 -6.0,0", "M15.0,19.0a3.0,3.0 0 1 0 6.0,0a3.0,3.0 0 1 0 -6.0,0", "M8.59,13.51L15.42,17.49", "M15.41,6.51L8.59,10.49") }
    val Shield: ImageVector by lazy { vector("shield", "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z") }
    val Sort: ImageVector by lazy { vector("arrow-down-wide-narrow", "m3 16 4 4 4-4", "M7 20V4", "M11 4h10", "M11 8h7", "M11 12h4") }
    val Star: ImageVector by lazy { vector("star", "M11.525 2.295a.53.53 0 0 1 .95 0l2.31 4.679a2.123 2.123 0 0 0 1.595 1.16l5.166.756a.53.53 0 0 1 .294.904l-3.736 3.638a2.123 2.123 0 0 0-.611 1.878l.882 5.14a.53.53 0 0 1-.771.56l-4.618-2.428a2.122 2.122 0 0 0-1.973 0L6.396 21.01a.53.53 0 0 1-.77-.56l.881-5.139a2.122 2.122 0 0 0-.611-1.879L2.16 9.795a.53.53 0 0 1 .294-.906l5.165-.755a2.122 2.122 0 0 0 1.597-1.16z") }
    val StarOutline: ImageVector by lazy { vector("star", "M11.525 2.295a.53.53 0 0 1 .95 0l2.31 4.679a2.123 2.123 0 0 0 1.595 1.16l5.166.756a.53.53 0 0 1 .294.904l-3.736 3.638a2.123 2.123 0 0 0-.611 1.878l.882 5.14a.53.53 0 0 1-.771.56l-4.618-2.428a2.122 2.122 0 0 0-1.973 0L6.396 21.01a.53.53 0 0 1-.77-.56l.881-5.139a2.122 2.122 0 0 0-.611-1.879L2.16 9.795a.53.53 0 0 1 .294-.906l5.165-.755a2.122 2.122 0 0 0 1.597-1.16z") }
    val Storage: ImageVector by lazy { vector("hard-drive", "M10 16h.01", "M2.212 11.577a2 2 0 0 0-.212.896V18a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-5.527a2 2 0 0 0-.212-.896L18.55 5.11A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z", "M21.946 12.013H2.054", "M6 16h.01") }
    val SwapVert: ImageVector by lazy { vector("arrow-up-down", "m21 16-4 4-4-4", "M17 20V4", "m3 8 4-4 4 4", "M7 4v16") }
    val Sync: ImageVector by lazy { vector("refresh-cw", "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8", "M21 3v5h-5", "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16", "M8 16H3v5") }
    val Tune: ImageVector by lazy { vector("sliders-horizontal", "M10 5H3", "M12 19H3", "M14 3v4", "M16 17v4", "M21 12h-9", "M21 19h-5", "M21 5h-7", "M8 10v4", "M8 12H3") }
    val Upload: ImageVector by lazy { vector("upload", "M12 3v12", "m17 8-5-5-5 5", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4") }
    val Verified: ImageVector by lazy { vector("badge-check", "M3.85 8.62a4 4 0 0 1 4.78-4.77 4 4 0 0 1 6.74 0 4 4 0 0 1 4.78 4.78 4 4 0 0 1 0 6.74 4 4 0 0 1-4.77 4.78 4 4 0 0 1-6.75 0 4 4 0 0 1-4.78-4.77 4 4 0 0 1 0-6.76Z", "m16 9-5.5 5.5L8 12") }
    val Videocam: ImageVector by lazy { vector("video", "m16 13 5.223 3.482a.5.5 0 0 0 .777-.416V7.87a.5.5 0 0 0-.752-.432L16 10.5", "M4.0,6.0h10.0a2.0,2.0 0 0 1 2.0,2.0v8.0a2.0,2.0 0 0 1 -2.0,2.0h-10.0a2.0,2.0 0 0 1 -2.0,-2.0v-8.0a2.0,2.0 0 0 1 2.0,-2.0Z") }
    val ViewList: ImageVector by lazy { vector("list", "M3 5h.01", "M3 12h.01", "M3 19h.01", "M8 5h13", "M8 12h13", "M8 19h13") }
    val WarningAmber: ImageVector by lazy { vector("triangle-alert", "m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3", "M12 9v4", "M12 17h.01") }
    private fun vector(name:String,vararg paths:String):ImageVector = ImageVector.Builder(name,24.dp,24.dp,24f,24f).apply {
        paths.forEach { data -> addPath(PathParser().parsePathString(data).toNodes(),fill=null,stroke=SolidColor(Color.Black),strokeLineWidth=1.8f,strokeLineCap=StrokeCap.Round,strokeLineJoin=StrokeJoin.Round) }
    }.build()
}
