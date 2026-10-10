package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterTurnCardOverlayTest {
    @Test
    fun manualOffsetsReachThePanelEdges() {
        val left = ClusterTurnCardOverlay.card(
            1920, 720, 10, 28, 40,
        )
        val right = ClusterTurnCardOverlay.card(
            1920, 720, 90, 28, 40,
        )
        assertTrue(left.left < 1920 * 0.12)
        assertTrue(right.left + right.width > 1920 * 0.88)
        assertTrue(right.left - left.left > 1920 * 0.45)
    }

    @Test
    fun defaultSitsOnTheRightOfThePanel() {
        val card = ClusterTurnCardOverlay.card(
            1920, 720,
            ClusterTurnCardOverlay.DEFAULT_X_PERCENT,
            ClusterTurnCardOverlay.DEFAULT_Y_PERCENT,
            55,
        )
        assertTrue(card.left > 1920 / 2)
        assertTrue(card.top < 720 / 2)
        assertTrue(card.left + card.width <= 1920)
        assertTrue(card.top + card.height <= 720)
    }

    @Test
    fun largeCardDoesNotCoverTheWholePanel() {
        val large = ClusterTurnCardOverlay.card(
            1920, 720, 75, 28, 75,
        )
        assertTrue(large.width < 1920 / 2)
        assertTrue(large.height < 720 / 2)
    }

    @Test
    fun percentStepsStayOnThePanel() {
        for (x in ClusterTurnCardOverlay.xPercents) {
            for (y in ClusterTurnCardOverlay.yPercents) {
                val card = ClusterTurnCardOverlay.card(
                    1920, 720, x, y, 55,
                )
                assertTrue(card.left >= 0)
                assertTrue(card.top >= 0)
                assertTrue(card.left + card.width <= 1920)
                assertTrue(card.top + card.height <= 720)
            }
        }
        assertEquals(76, ClusterTurnCardOverlay.snap(76, ClusterTurnCardOverlay.xPercents))
        // B561: 位置滑条改 1% 步进（与上游 0.2.15 对齐）。
        // 2% 在 1920 px 面板上是 38 px 一档，调三张卡错开时常常差一点。
        assertEquals(1, ClusterTurnCardOverlay.STEP_PERCENT)
        assertEquals(77, ClusterTurnCardOverlay.snap(77, ClusterTurnCardOverlay.xPercents))
        // 步进必须是 1 —— 值域连续，没有跳档。
        assertEquals((8..92).toList(), ClusterTurnCardOverlay.xPercents)
        assertEquals((12..72).toList(), ClusterTurnCardOverlay.yPercents)
    }

    // ---------------------------------------------------------- B561 v67/v69 三种排放方向

    /**
     * B561 v69: 选「竖版」必须真的拿到 **1:1.35** 的卡片（高 : 宽）。
     *
     * v67 曾改成 1:1 正方，v69 按用户实测反馈改回 1:1.35 ——
     * 正方卡片封面上下都留不出空间，三行文字挤在半张卡里。
     */
    @Test
    fun verticalOrientationIsOneToOnePointThreeFive() {
        for (size in listOf(30, 55, 75, 95)) {
            val card = ClusterTurnCardOverlay.mediaCard(
                1920, 720, 50, 50, size, size,
                ClusterTurnCardOverlay.CardOrientation.VERTICAL,
            )
            val ratio = card.height.toFloat() / card.width
            assertEquals("size=$size ratio=$ratio", 1.35f, ratio, 0.02f)
        }
    }

    /** B561 v67: 横幅仍是 2.64:1，且宽 > 高 —— 封面在左、文字在右。 */
    @Test
    fun horizontalOrientationStaysWide() {
        val card = ClusterTurnCardOverlay.mediaCard(
            1920, 720, 50, 50, 55, 55,
            ClusterTurnCardOverlay.CardOrientation.HORIZONTAL,
        )
        assertTrue(card.width > card.height)
        // 2.64:1 允许取整误差，±2% 之内。
        val ratio = card.width.toFloat() / card.height
        assertTrue("ratio=$ratio", ratio in 2.5f..2.8f)
    }

    /**
     * B561 v69: 自由模式是唯一用两个独立滑块的：宽按屏高、高按屏高，各自独立。
     */
    @Test
    fun freeformUsesIndependentWidthAndHeight() {
        val wide = ClusterTurnCardOverlay.mediaCard(
            1920, 720, 50, 50, 80, 20,
            ClusterTurnCardOverlay.CardOrientation.FREEFORM,
        )
        assertTrue(wide.width > wide.height)

        val tall = ClusterTurnCardOverlay.mediaCard(
            1920, 720, 50, 50, 20, 75,
            ClusterTurnCardOverlay.CardOrientation.FREEFORM,
        )
        assertTrue(tall.height > tall.width)
    }

    /**
     * B561 v69: 三种方向 × 全滑块值域，卡片都必须**留在面板内**。
     *
     * 仪表屏上下有车辆数据叠层，所以还要额外确认高度不超可见区。
     */
    @Test
    fun everyOrientationStaysInsideThePanel() {
        val orientations = ClusterTurnCardOverlay.CardOrientation.entries
        for (orientation in orientations) {
            for (size in ClusterTurnCardOverlay.sizePercents) {
                for (x in listOf(8, 50, 92)) {
                    for (y in listOf(12, 42, 72)) {
                        val card = ClusterTurnCardOverlay.mediaCard(
                            1920, 720, x, y, size, size, orientation,
                        )
                        assertTrue("$orientation L", card.left >= 0)
                        assertTrue("$orientation T", card.top >= 0)
                        assertTrue("$orientation R", card.left + card.width <= 1920)
                        assertTrue("$orientation B", card.top + card.height <= 720)
                    }
                }
            }
        }
    }

    /**
     * B561 v69: 竖版卡片不能高过可见区 —— 否则会盖住仪表上下的车辆数据。
     */
    @Test
    fun verticalCardNeverExceedsVisibleHeight() {
        val window = ClusterTurnCardOverlay.visibleWindow(1920, 720)
        for (size in ClusterTurnCardOverlay.sizePercents) {
            val card = ClusterTurnCardOverlay.mediaCard(
                1920, 720, 50, 50, size, size,
                ClusterTurnCardOverlay.CardOrientation.VERTICAL,
            )
            assertTrue("size=$size h=${card.height}", card.height <= window.height)
        }
    }

    /**
     * B561 v69: 宽/高百分比**以屏幕高度为基数**。
     *
     * 回归点：原来宽按可见区宽度算（1920 × 0.55 ≈ 1056 px），
     * 一张卡横跨半个仪表屏，位置滑块怎么调都躲不开别的卡。
     * 现在宽按 720 × 0.55 ≈ 396 px —— 直接对应"占屏高 55%"。
     */
    @Test
    fun widthIsAPercentOfPanelHeightNotWidth() {
        val card = ClusterTurnCardOverlay.card(1920, 720, 50, 50, 55)
        // 55% 屏高 = 396 px，取整误差 ±2 px。
        assertEquals(396.0, card.width.toDouble(), 2.0)
    }

    /**
     * B561 v69: 换一块**更高**的屏，sizePercent 的实际像素随之变化 ——
     * 说明基数确实是高度，不是宽度。
     *
     * 用 size=30 而不是 50：1440 高的屏上 50% 会算出 720 px 宽，
     * 而 1920×720 下安全区只留 557 px 宽 —— 会被`coerceAtMost` 截断，
     * 测的就不是基数而是上限了。30% 时两档都不撞上限（216/ 432 px）。
     */
    @Test
    fun sizeScalesWithPanelHeight() {
        val short = ClusterTurnCardOverlay.card(1920, 720, 50, 50, 30)
        val tall = ClusterTurnCardOverlay.card(1920, 1440, 50, 50, 30)
        assertEquals(720 * 0.30, short.width.toDouble(), 2.0)
        assertEquals(1440 * 0.30, tall.width.toDouble(), 2.0)
    }
}
