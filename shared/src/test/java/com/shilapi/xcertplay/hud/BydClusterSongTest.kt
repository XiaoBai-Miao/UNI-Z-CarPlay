package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B561: 媒体卡的数据契约。
 *
 * `ClusterSong` 除了拼好的单行 [ClusterSong.text]（比亚迪仪表只显示一行），
 * 还携带**原始的** [ClusterSong.title] 与 [ClusterSong.artist] ——
 * 我们的悬浮媒体卡要分两行显示，需要未拼接的值。
 *
 * 所以下面每条断言都显式写出 title/artist：它们是媒体卡渲染的直接输入，
 * 漏测等于把"歌手行为空"这类问题留给实车发现。
 */
class BydClusterSongTest {
    private fun update(block: com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder.() -> Unit) =
        Iap2Messages.buildRaw(ClusterSongState.NOW_PLAYING_UPDATE, block)

    private fun song(
        text: String,
        playing: Boolean,
        line: String = text,
        artist: String? = null,
        title: String? = null,
    ) = ClusterSong(text, playing, line, source = null, artist = artist, title = title)

    @Test
    fun followsTitleArtistAndPlaybackStatus() {
        val state = ClusterSongState()

        assertEquals(song("Numb — Linkin Park", false, "Numb", artist = "Linkin Park", title = "Numb"),
            state.accept(update { group(0) { string(1, "Numb"); string(12, "Linkin Park") } }))
        assertEquals(song("Numb — Linkin Park", true, "Numb", artist = "Linkin Park", title = "Numb"),
            state.accept(update { group(1) { u8(0, 1) } }))
        // Elapsed time alone changes nothing on the card.
        assertNull(state.accept(update { group(1) { u32(1, 120_706L) } }))
        assertEquals(song("Numb — Linkin Park", false, "Numb", artist = "Linkin Park", title = "Numb"),
            state.accept(update { group(1) { u8(0, 2) } }))
        // A title-only incremental update retains the last artist.
        assertEquals(song("Podcast — Linkin Park", false, "Podcast", artist = "Linkin Park", title = "Podcast"),
            state.accept(update { group(0) { string(1, "Podcast") } }))
        assertEquals(song("Podcast — Host", false, "Podcast", artist = "Host", title = "Podcast"),
            state.accept(update { group(0) { string(12, "Host") } }))
    }

    @Test
    fun nothingWithoutATitleOrForOtherMessages() {
        val state = ClusterSongState()
        assertNull(state.accept(update { group(1) { u8(0, 1) } }))
        assertNull(state.accept(Iap2Messages.buildRaw(0x5201) { group(0) { string(1, "Numb") } }))
        assertNull(state.current())

        state.accept(update { group(0) { string(1, "Numb") } })
        state.clear()
        assertNull(state.current())
    }

    @Test
    fun clearedTitlesForgetThePreviousSongUntilANewTitleArrives() {
        val state = ClusterSongState()
        state.accept(update { group(0) { string(1, "Previous song"); string(12, "Artist") } })
        state.accept(update { group(0) { string(1, "") } })
        assertNull(state.current())
        state.accept(update { group(1) { u8(0, 1) } })
        assertNull(state.current())
        // ★ 新歌不带歌手 → artist 必须为 null，媒体卡据此不画空行。
        assertEquals(song("Next song", true, "Next song", artist = null, title = "Next song"),
            state.accept(update { group(0) { string(1, "Next song") } }))
        state.accept(update { group(0) { string(1, "  "); string(12, "Stale artist") } })
        assertNull(state.current())
        assertEquals(song("After clear", true, "After clear", artist = null, title = "After clear"),
            state.accept(update { group(0) { string(1, "After clear") } }))
    }

    @Test
    fun titleOnlyUpdatesRetainArtistAndPlaybackWhenOtherFieldsAreOmitted() {
        val state = ClusterSongState()
        state.accept(update {
            group(0) { string(1, "Track"); string(12, "Artist") }
            group(1) { u8(0, 1) }
        })
        assertEquals(song("Lyric line — Artist", true, "Lyric line", artist = "Artist", title = "Lyric line"),
            state.accept(update { group(0) { string(1, "Lyric line") } }))
        assertNull(state.accept(update { group(0) { u32(4, 180_000L) } }))
        assertEquals(song("Lyric line — Artist", true, "Lyric line", artist = "Artist", title = "Lyric line"),
            state.current())
    }

    @Test
    fun explicitEmptyArtistClearsItWithoutChangingTitleOrPlayback() {
        val state = ClusterSongState()
        state.accept(update { group(0) { string(1, "Track"); string(12, "Artist") } })
        // ★ 显式空歌手 → artist 归零，但歌名与播放状态不动。
        assertEquals(song("Track", false, "Track", artist = null, title = "Track"),
            state.accept(update { group(0) { string(12, "") } }))
        assertEquals(song("Next line", false, "Next line", artist = null, title = "Next line"),
            state.accept(update { group(0) { string(1, "Next line") } }))
    }

    @Test
    fun completeTrackUpdateReplacesBothTitleAndArtist() {
        val state = ClusterSongState()
        state.accept(update { group(0) { string(1, "First track"); string(12, "First artist") } })
        assertEquals(
            song("Second track — Second artist", false, "Second track", artist = "Second artist", title = "Second track"),
            state.accept(update { group(0) { string(1, "Second track"); string(12, "Second artist") } }),
        )
        assertEquals(song("Third track", false, "Third track", artist = null, title = "Third track"),
            state.accept(update { group(0) { string(1, "Third track"); string(12, "") } }))
    }

    @Test
    fun textFitsTheDashboard() {
        assertNull(ClusterSongState.text("  ", "Artist"))
        assertEquals("Title", ClusterSongState.text(" Title ", ""))

        val long = ClusterSongState.text("Пісня".repeat(40), "Виконавець")!!
        assertTrue(long.toByteArray(Charsets.UTF_16LE).size <= ClusterSongState.MAX_TEXT_BYTES)
        assertEquals(127, long.length)

        // An emoji is never cut in half.
        val emoji = ClusterSongState.text("a" + "🎵".repeat(100), null)!!
        assertTrue(emoji.toByteArray(Charsets.UTF_16LE).size <= ClusterSongState.MAX_TEXT_BYTES)
        assertTrue(!Character.isHighSurrogate(emoji.last()))
    }
}
