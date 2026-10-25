package app.tgdrive.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

@Composable internal fun LockedScreen(error:String?,onUnlock:()->Unit,onSecuritySettings:()->Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(32.dp),
            verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
            Icon(DriveIcons.Lock,null,Modifier.size(48.dp),tint=MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(20.dp))
            Text("Your space is locked",style=MaterialTheme.typography.headlineMedium,modifier=Modifier.semantics{heading()})
            Spacer(Modifier.height(14.dp))
            Text("Use your device credential or biometrics to continue.")
            error?.let {
                Spacer(Modifier.height(14.dp))
                Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite})
            }
            Spacer(Modifier.height(22.dp))
            DriveButton(onUnlock){Text("Unlock TG Drive")}
            if(error!=null)TextButton(onSecuritySettings){Text("Open Android security settings")}
        }
    }
}
