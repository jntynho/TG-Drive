package app.tgdrive.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

internal val LocalDriveGroup = compositionLocalOf { false }

@Composable
fun DriveGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = DriveStyle.CardShape, color = MaterialTheme.colorScheme.surface) {
        CompositionLocalProvider(LocalDriveGroup provides true) { Column(content = content) }
    }
}

@Composable
fun DriveFilterChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit, leadingIcon: (@Composable () -> Unit)? = null) {
    FilterChip(selected, onClick, label, shape = DriveStyle.PillShape, leadingIcon = leadingIcon,
        border = BorderStroke(0.dp, Color.Transparent), colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surface,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ))
}

@Composable
fun DriveAssistChip(onClick: () -> Unit, label: @Composable () -> Unit, leadingIcon: (@Composable () -> Unit)? = null) {
    AssistChip(onClick, label, shape = DriveStyle.PillShape, leadingIcon = leadingIcon,
        border = BorderStroke(0.dp, Color.Transparent), colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surface, labelColor = MaterialTheme.colorScheme.onSurface,
            leadingIconContentColor = MaterialTheme.colorScheme.onSurface,
        ))
}

@Composable
fun DriveButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    Button(onClick, modifier.heightIn(min = 52.dp), enabled = enabled, shape = DriveStyle.PillShape,
        colors = ButtonDefaults.buttonColors(disabledContainerColor = Color(0xFF123552), disabledContentColor = Color(0xFF8A9AA8)),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp), content = content)
}

@Composable
fun DriveSecondaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    FilledTonalButton(onClick, modifier.heightIn(min = 52.dp), enabled = enabled, shape = DriveStyle.PillShape,
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface), content = content)
}

@Composable
fun DriveTextField(
    value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null, leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = false, shape: Shape = DriveStyle.CardShape,
) {
    OutlinedTextField(value, onValueChange, modifier.heightIn(min = 56.dp), enabled = enabled,
        textStyle = MaterialTheme.typography.bodyLarge, label = label, placeholder = placeholder,
        leadingIcon = leadingIcon, trailingIcon = trailingIcon, visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions, singleLine = singleLine, shape = shape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            disabledContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedBorderColor = Color.Transparent, disabledBorderColor = Color.Transparent,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ))
}

@Composable
fun DriveCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier, shape = DriveStyle.CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), content = content)
}

@Composable
fun DriveCard(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(onClick, modifier, shape = DriveStyle.CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), content = content)
}

@Composable
fun DriveCircleButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledIconButton(onClick, modifier.size(48.dp), shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface)) {
        Icon(icon, description, Modifier.size(24.dp))
    }
}

@Composable
fun DrivePageHeader(title: String, onBack: () -> Unit, close: Boolean = false, backDescription: String = "Back to settings") {
    Box(Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(horizontal = DriveStyle.PageInset, vertical = 12.dp)) {
        DriveCircleButton(if (close) DriveIcons.Close else DriveIcons.ArrowBack,
            if (close) "Close settings" else backDescription, onBack, Modifier.align(Alignment.CenterStart))
        Text(title, Modifier.align(Alignment.Center).padding(horizontal = 60.dp),
            style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun DriveDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant)
}

/** Reference proportions: a wide capsule thumb inside a 64 × 28 dp track. */
@Composable
fun DriveSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier) {
    val track by animateColorAsState(if(checked) MaterialTheme.colorScheme.primary else Color(0xFFA7AAAE), tween(140), label="switch-track")
    val thumb by animateColorAsState(if(checked) Color.White else Color(0xFF111111), tween(140), label="switch-thumb")
    val offset by animateDpAsState(if(checked) 22.dp else 2.dp, tween(140), label="switch-position")
    val touch = if(onCheckedChange != null) Modifier.toggleable(checked, role=Role.Switch, onValueChange=onCheckedChange) else Modifier
    Box(modifier.then(touch).size(width=64.dp,height=48.dp),contentAlignment=Alignment.Center) {
        Box(Modifier.size(64.dp,28.dp).background(track,DriveStyle.PillShape)) {
            Box(Modifier.offset(x=offset,y=2.dp).size(40.dp,24.dp).background(thumb,DriveStyle.PillShape))
        }
    }
}

@Composable
fun DriveCheckboxRow(label:String,checked:Boolean,onChange:(Boolean)->Unit,enabled:Boolean=true) {
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(checked,enabled=enabled,role=Role.Checkbox,onValueChange=onChange),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Checkbox(checked,onCheckedChange=null,enabled=enabled)
        Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
    }
}
