package app.tgdrive.ui

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Modifier

class SolarIconsTest {
    @Test fun allCuratedIconsBuildAsLocalSolarVectors() {
        val getters=DriveIcons::class.java.declaredMethods.filter{Modifier.isPublic(it.modifiers)&&it.parameterCount==0&&it.returnType==ImageVector::class.java}
        assertTrue(getters.size>=79)
        getters.forEach{getter->
            val icon=getter.invoke(DriveIcons) as ImageVector
            assertTrue(getter.name,icon.name.startsWith("solar:"))
            assertEquals(24.dp,icon.defaultWidth);assertEquals(24.dp,icon.defaultHeight)
            assertEquals(24f,icon.viewportWidth);assertEquals(24f,icon.viewportHeight)
            assertTrue(getter.name,icon.root.size>0)
        }
    }
}
