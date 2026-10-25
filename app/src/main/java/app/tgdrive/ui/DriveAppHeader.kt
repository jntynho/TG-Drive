package app.tgdrive.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription

data class HeaderSearch(val value:String,val onChange:(String)->Unit,val hint:String,val onFilters:(()->Unit)?=null)

/** Fixed title-bar geometry; each route owns its search row so page motion never changes its inset. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DriveAppHeader(title:String,onBack:(()->Unit)?,search:HeaderSearch?=null,containerColor:Color=MaterialTheme.colorScheme.background,actions:@Composable RowScope.()->Unit={}) {
    Column {
        TopAppBar(title={
            AnimatedContent(title,transitionSpec={
                (fadeIn(tween(180,80)) togetherWith fadeOut(tween(120))).using(SizeTransform(clip=true,sizeAnimationSpec={_,_->tween(240)}))
            },label="header-title") { text->Text(text,modifier=Modifier.semantics{heading()},style=MaterialTheme.typography.titleLarge,maxLines=1,overflow=TextOverflow.Ellipsis) }
        },navigationIcon={if(onBack!=null)IconButton(onBack,Modifier.padding(start=8.dp)){Icon(DriveIcons.ArrowBack,"Back")}},
            actions=actions,colors=TopAppBarDefaults.topAppBarColors(containerColor=containerColor))
        if(search!=null)HeaderSearchField(search)
    }
}

@Composable internal fun HeaderSearchField(search:HeaderSearch) {
    val focus=androidx.compose.ui.platform.LocalFocusManager.current
    DriveTextField(search.value,search.onChange,Modifier.fillMaxWidth().padding(horizontal=16.dp).padding(bottom=12.dp).semantics{contentDescription=search.hint},
        shape=DriveStyle.PillShape,
        keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(imeAction=androidx.compose.ui.text.input.ImeAction.Search),keyboardActions=androidx.compose.foundation.text.KeyboardActions(onSearch={focus.clearFocus()}),placeholder={Text(search.hint,maxLines=1,overflow=TextOverflow.Ellipsis)},singleLine=true,
        leadingIcon={Icon(DriveIcons.Search,null)},trailingIcon={Row {
            if(search.value.isNotEmpty())IconButton({search.onChange("")}){Icon(DriveIcons.Close,"Clear search")}
            search.onFilters?.let{filters->IconButton(filters){Icon(DriveIcons.Tune,"Search filters")}}
        }})
}
