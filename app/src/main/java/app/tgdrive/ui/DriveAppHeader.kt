package app.tgdrive.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal data class HeaderSearch(val value:String,val onChange:(String)->Unit,val hint:String,val onFilters:(()->Unit)?=null)

/** A single native page header. Search belongs to the current route, never Gallery. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DriveAppHeader(title:String,onBack:(()->Unit)?,search:HeaderSearch?=null,actions:@Composable RowScope.()->Unit={}) {
    val focus=androidx.compose.ui.platform.LocalFocusManager.current
    Column {
        TopAppBar(title={Text(title,style=MaterialTheme.typography.titleLarge,maxLines=1,overflow=TextOverflow.Ellipsis)},
            navigationIcon={if(onBack!=null)DriveCircleButton(DriveIcons.ArrowBack,"Back",onBack,Modifier.padding(start=12.dp))},
            actions=actions,colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.background))
        if(search!=null)DriveTextField(search.value,search.onChange,Modifier.fillMaxWidth().padding(horizontal=16.dp).padding(bottom=12.dp),
            keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(imeAction=androidx.compose.ui.text.input.ImeAction.Search),keyboardActions=androidx.compose.foundation.text.KeyboardActions(onSearch={focus.clearFocus()}),placeholder={Text(search.hint,maxLines=1,overflow=TextOverflow.Ellipsis)},singleLine=true,
            leadingIcon={Icon(DriveIcons.Search,null)},trailingIcon={Row {
                if(search.value.isNotEmpty())IconButton({search.onChange("")}){Icon(DriveIcons.Close,"Clear search")}
                search.onFilters?.let{filters->IconButton(filters){Icon(DriveIcons.Tune,"Search filters")}}
            }})
    }
}
