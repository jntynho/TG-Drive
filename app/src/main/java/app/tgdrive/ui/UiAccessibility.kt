package app.tgdrive.ui

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

/** Keep media controls available while a screen reader explores them. */
@Composable internal fun rememberTouchExploration():Boolean {
    val context=LocalContext.current
    val manager=remember(context){context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager}
    var enabled by remember(manager){mutableStateOf(manager?.isTouchExplorationEnabled==true)}
    DisposableEffect(manager){
        val listener=AccessibilityManager.TouchExplorationStateChangeListener{enabled=it}
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose{manager?.removeTouchExplorationStateChangeListener(listener)}
    }
    return enabled
}
