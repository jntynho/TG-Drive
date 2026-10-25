package app.tgdrive.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tgdrive.library.Item
import app.tgdrive.library.Version

/** Stored by name, with stable ID tie breaks, so equal-sized files never shuffle between renders. */
enum class MediaSort(val label:String) {
    NEWEST("Newest uploads"), OLDEST("Oldest uploads"), NAME_ASC("Name: A to Z"), NAME_DESC("Name: Z to A"), LARGEST("Largest first"), SMALLEST("Smallest first")
}
internal fun sortMedia(items:List<Item>,sort:MediaSort,versions:List<Version>):List<Item> {
    val dates=versions.associate{it.id to it.createdAt}
    val comparator=when(sort) {
        MediaSort.NEWEST->compareByDescending<Item>{dates[it.versionId]?:it.createdAt}
        MediaSort.OLDEST->compareBy<Item>{dates[it.versionId]?:it.createdAt}
        MediaSort.NAME_ASC->compareBy<Item>{it.name.lowercase(java.util.Locale.ROOT)}
        MediaSort.NAME_DESC->compareByDescending<Item>{it.name.lowercase(java.util.Locale.ROOT)}
        MediaSort.LARGEST->compareByDescending<Item>{it.size}
        MediaSort.SMALLEST->compareBy<Item>{it.size}
    }
    return items.sortedWith(comparator.thenBy{it.id})
}
@Composable internal fun MediaSortMenu(gallery:Boolean,selected:MediaSort,onSelect:(MediaSort)->Unit) {
    var expanded by remember{mutableStateOf(false)}
    Box {
        IconButton({expanded=true}){Icon(DriveIcons.Sort,if(gallery)"Sort gallery"else"Sort audio")}
        // Material's anchored popup supplies native focus, Back dismissal, and scale/fade motion.
        DropdownMenu(expanded,{expanded=false},tonalElevation=0.dp) {
            MediaSort.entries.forEach{sort->DropdownMenuItem(text={Text(sort.label)},onClick={onSelect(sort);expanded=false},
                trailingIcon={if(sort==selected)Icon(DriveIcons.Check,"Selected")})}
        }
    }
}
