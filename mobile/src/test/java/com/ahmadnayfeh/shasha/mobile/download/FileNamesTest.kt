package com.ahmadnayfeh.shasha.mobile.download

import com.ahmadnayfeh.shasha.mobile.data.db.DownloadKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FileNamesTest {

    @Test
    fun aTitleLosesWhatAFileNameCannotHold() {
        assertThat(FileNames.sanitize("AC/DC: Back <in> \"Black\"?", "id")).isEqualTo("AC DC Back in Black")
        assertThat(FileNames.sanitize("  many    spaces\tand\nlines ", "id")).isEqualTo("many spaces and lines")
        assertThat(FileNames.sanitize("trailing dots...", "id")).isEqualTo("trailing dots")
        assertThat(FileNames.sanitize("a|b*c\\d", "id")).isEqualTo("a b c d")
    }

    @Test
    fun arabicTitlesStayAsTheyAre() {
        assertThat(FileNames.sanitize("القرآن الكريم: سورة الفاتحة", "id")).isEqualTo("القرآن الكريم سورة الفاتحة")
    }

    @Test
    fun aTitleThatIsNothingFallsBackToTheVideoId() {
        assertThat(FileNames.sanitize("???", "dQw4w9WgXcQ")).isEqualTo("dQw4w9WgXcQ")
        assertThat(FileNames.sanitize("", "dQw4w9WgXcQ")).isEqualTo("dQw4w9WgXcQ")
        assertThat(FileNames.sanitize("...", "dQw4w9WgXcQ")).isEqualTo("dQw4w9WgXcQ")
    }

    @Test
    fun aLongTitleIsCut() {
        val name = FileNames.sanitize("x".repeat(300), "id")
        assertThat(name).hasLength(100)
    }

    @Test
    fun theDisplayNameNamesTheQualityOfAVideoAndTheKindOfAFile() {
        assertThat(FileNames.displayName("My video", "id", DownloadKind.VIDEO, 720)).isEqualTo("My video [720p].mp4")
        assertThat(FileNames.displayName("My video", "id", DownloadKind.AUDIO, 0)).isEqualTo("My video.m4a")
        assertThat(FileNames.displayName("?", "abc", DownloadKind.AUDIO, 0)).isEqualTo("abc.m4a")
    }

    @Test
    fun folderAndTypeFollowTheKind() {
        assertThat(FileNames.relativeFolder(DownloadKind.VIDEO)).isEqualTo("Movies/Shasha")
        assertThat(FileNames.relativeFolder(DownloadKind.AUDIO)).isEqualTo("Music/Shasha")
        assertThat(FileNames.mimeType(DownloadKind.VIDEO)).isEqualTo("video/mp4")
        assertThat(FileNames.mimeType(DownloadKind.AUDIO)).isEqualTo("audio/mp4")
    }

    @Test
    fun aTakenNameGetsTheNextFreeNumber() {
        assertThat(FileNames.unique("a.mp4") { false }).isEqualTo("a.mp4")
        assertThat(FileNames.unique("a.mp4") { it == "a.mp4" }).isEqualTo("a (2).mp4")
        assertThat(FileNames.unique("a.mp4") { it in setOf("a.mp4", "a (2).mp4", "a (3).mp4") }).isEqualTo("a (4).mp4")
        assertThat(FileNames.unique("noext") { it == "noext" }).isEqualTo("noext (2)")
    }
}
