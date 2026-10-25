package app.tgdrive.ui

import app.tgdrive.library.*
import org.junit.Assert.*
import org.junit.Test

class MediaSortTest {
    private val a=Item(id="a",name="Zebra.mp3",size=20,versionId="va",createdAt=1)
    private val b=Item(id="b",name="alpha.mp3",size=10,versionId="vb",createdAt=3)
    private val versions=listOf(Version("va","a",20,"",emptyList(),createdAt=5))
    @Test fun uploadedDateUsesCurrentVersionAndSortsBothWays() {
        assertEquals(listOf(a,b),sortMedia(listOf(b,a),MediaSort.NEWEST,versions))
        assertEquals(listOf(b,a),sortMedia(listOf(a,b),MediaSort.OLDEST,versions))
    }
    @Test fun namesIgnoreCaseSizesAreNumericAndTiesAreStable() {
        assertEquals(listOf(b,a),sortMedia(listOf(a,b),MediaSort.NAME_ASC,versions))
        assertEquals(listOf(a,b),sortMedia(listOf(b,a),MediaSort.LARGEST,versions))
        val same=a.copy(id="c")
        assertEquals(listOf(a,same),sortMedia(listOf(same,a),MediaSort.SMALLEST,versions))
    }
    @Test fun mediaTimeHandlesHoursAndUnsetDurations() {
        assertEquals("0:00",mediaTime(-1));assertEquals("1:05",mediaTime(65_000));assertEquals("1:01:01",mediaTime(3_661_000))
    }
}
