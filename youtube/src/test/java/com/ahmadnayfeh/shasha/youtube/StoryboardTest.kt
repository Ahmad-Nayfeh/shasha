package com.ahmadnayfeh.shasha.youtube

import com.ahmadnayfeh.shasha.youtube.newpipe.StreamMapper
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.schabi.newpipe.extractor.stream.Frameset

/** The frame above the seek bar: which sheet of a storyboard, and where in it, for a position; and which storyboard to use. */
class StoryboardTest {
    /** Two sheets of 3 × 2 frames of 160 × 90, one frame every 2 s, 10 frames (the second sheet is not full). */
    private val board = Storyboard(
        urls = listOf("https://i.ytimg.com/sb/v/storyboard3_L2/M0.jpg", "https://i.ytimg.com/sb/v/storyboard3_L2/M1.jpg"),
        frameWidth = 160,
        frameHeight = 90,
        framesPerPageX = 3,
        framesPerPageY = 2,
        totalCount = 10,
        durationPerFrameMs = 2_000,
    )

    @Test
    fun theFramesAreReadLeftToRightThenTopToBottom_sheetAfterSheet() {
        assertThat(board.frameAt(0)).isEqualTo(StoryboardFrame(board.urls[0], 0, 0, 160, 90))
        assertThat(board.frameAt(4_100)).isEqualTo(StoryboardFrame(board.urls[0], 320, 0, 160, 90)) // the 3rd frame
        assertThat(board.frameAt(6_000)).isEqualTo(StoryboardFrame(board.urls[0], 0, 90, 160, 90)) // the 4th: second row
        assertThat(board.frameAt(12_000)).isEqualTo(StoryboardFrame(board.urls[1], 0, 0, 160, 90)) // the 7th: second sheet
    }

    @Test
    fun aPositionPastTheLastFrameShowsTheLastFrame_andANegativeOneTheFirst() {
        assertThat(board.frameAt(10 * 60_000)).isEqualTo(StoryboardFrame(board.urls[1], 0, 90, 160, 90)) // the 10th: 4th of the second sheet
        assertThat(board.frameAt(-5)).isEqualTo(board.frameAt(0))
    }

    @Test
    fun withoutATimePerFrameTheFramesAreSpreadOverTheVideo() {
        val unknown = board.copy(durationPerFrameMs = 0)

        assertThat(unknown.frameAt(12_000, durationMs = 20_000)).isEqualTo(board.frameAt(12_000))
        assertThat(unknown.frameAt(12_000)).isNull() // and without the video's length there is no telling
    }

    @Test
    fun aStoryboardThatCannotBeDrawnGivesNoFrame() {
        assertThat(board.copy(urls = emptyList()).frameAt(0)).isNull()
        assertThat(board.copy(framesPerPageX = 0).frameAt(0)).isNull()
        assertThat(board.copy(totalCount = 0).frameAt(0)).isNull()
    }

    @Test
    fun thePreviewUsesTheBiggestFramesThatAreSmallEnough_elseTheSmallest() {
        val tiny = board.copy(frameWidth = 48, frameHeight = 27)
        val small = board.copy(frameWidth = 80, frameHeight = 45)
        val big = board.copy(frameWidth = 320, frameHeight = 180)
        assertThat(listOf(tiny, small, board, big).forPreview(maxFrameWidth = 200)).isEqualTo(board)
        assertThat(listOf(big).forPreview(maxFrameWidth = 200)).isEqualTo(big)
        assertThat(listOf(board.copy(urls = emptyList())).forPreview(maxFrameWidth = 200)).isNull()
        assertThat(emptyList<Storyboard>().forPreview(maxFrameWidth = 200)).isNull()
    }

    @Test
    fun newPipesFrameSetsBecomeStoryboards_smallestFirst_withoutTheUnusableOnes() {
        val frames = listOf(
            // NewPipe's order: the sheets, the frame's width and height, the count, the time per frame, the grid.
            Frameset(listOf("https://i.ytimg.com/sb/v/L2/M0.jpg"), 160, 90, 10, 2_000, 5, 5),
            Frameset(listOf("https://i.ytimg.com/sb/v/L0/default.jpg"), 48, 27, 100, 0, 10, 10),
            Frameset(emptyList(), 80, 45, 50, 1_000, 10, 10),
        )

        val boards = StreamMapper.storyboards(frames)

        assertThat(boards.map { it.frameWidth }).containsExactly(48, 160).inOrder()
        assertThat(boards[1]).isEqualTo(Storyboard(listOf("https://i.ytimg.com/sb/v/L2/M0.jpg"), 160, 90, 5, 5, 10, 2_000))
    }
}
