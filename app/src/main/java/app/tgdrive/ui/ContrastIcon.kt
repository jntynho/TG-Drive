package app.tgdrive.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** Dark glyph outline preserves contrast over photos without a filled toolbar or back plate. */
@Composable internal fun ContrastIcon(icon:ImageVector,label:String,outlined:Boolean){
    if(!outlined)Icon(icon,label)else Box(Modifier.size(24.dp)){
        listOf(-1 to 0,1 to 0,0 to -1,0 to 1).forEach{(x,y)->Icon(icon,null,Modifier.offset(x.dp,y.dp),tint=Color.Black.copy(alpha=.8f))}
        Icon(icon,label,tint=Color.White)
    }
}
