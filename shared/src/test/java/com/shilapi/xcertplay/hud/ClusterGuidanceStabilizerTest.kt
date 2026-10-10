package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * B561: 转向卡滞回器。
 *
 * 核心不变量：**转向变了必须立即换；只有距离变才允许被滞回吃掉。**
 */
class ClusterGuidanceStabilizerTest {
    @Test fun theFirstFrameIsAlwaysPublished() {
        val stabilizer = ClusterGuidanceStabilizer()
        assertEquals(guidance(icon = 1, distance = 500), stabilizer.accept(guidance(icon = 1, distance = 500)))
    }

    @Test fun aDifferentManeuverIsPublishedImmediately() {
        val stabilizer = ClusterGuidanceStabilizer()
        stabilizer.accept(guidance(icon = 1, distance = 500))
        // 只差 1 米但箭头类型变了 —— 滞回在这里是有害的，必须立刻换。
        assertEquals(guidance(icon = 2, distance = 501), stabilizer.accept(guidance(icon = 2, distance = 501)))
    }

    @Test fun aDifferentRoadAfterTheManeuverIsPublishedImmediately() {
        val stabilizer = ClusterGuidanceStabilizer()
        stabilizer.accept(guidance(icon = 1, distance = 500, road = "A"))
        assertEquals(
            guidance(icon = 1, distance = 500, road = "B"),
            stabilizer.accept(guidance(icon = 1, distance = 500, road = "B")),
        )
    }

    /**
     * 远距离的小变化被死区吃掉：1000 m 时死区约 2 m，1 米不动。
     */
    @Test fun aTinyChangeAtLongRangeIsSwallowed() {
        val stabilizer = ClusterGuidanceStabilizer()
        stabilizer.accept(guidance(icon = 1, distance = 1000))
        assertNull(stabilizer.accept(guidance(icon = 1, distance = 999)))
    }

    /** 同一距离重复上报不产生新发布 —— 这是 1 Hz tick 的常态。 */
    @Test fun theSameDistanceIsNotRepublished() {
        val stabilizer = ClusterGuidanceStabilizer()
        stabilizer.accept(guidance(icon = 1, distance = 1000))
        assertNull(stabilizer.accept(guidance(icon = 1, distance = 1000)))
        assertNull(stabilizer.accept(guidance(icon = 1, distance = 1000)))
    }

    /**
     * 相对死区：近距离时同样1 米是**大变化**，必须立刻显示。
     *
     * 固定阈值做不到这点 —— 5 米阈值对 1000 m 太粗、对 20 m 太细。
     */
    @Test fun aTinyChangeAtShortRangeIsPublishedImmediately() {
        val stabilizer = ClusterGuidanceStabilizer()
        stabilizer.accept(guidance(icon = 1, distance = 100))
        // 100 m 时死区约 0.2 m，1 米远超死区。
        assertEquals(guidance(icon = 1, distance = 99), stabilizer.accept(guidance(icon = 1, distance = 99)))
    }

    /**
     * 两段式提交：死区内的新距离第一次**不**发布，第二次才发布。
     *
     * 挡住 iOS/GPS 在两个值之间反复抖动。
     */
    @Test fun aNewDistanceInsideTheDeadZoneNeedsTwoConsecutiveHits() {
        val stabilizer = ClusterGuidanceStabilizer()
        stabilizer.accept(guidance(icon = 1, distance = 1000))
        assertNull(stabilizer.accept(guidance(icon = 1, distance = 999)))
        assertEquals(guidance(icon = 1, distance = 999), stabilizer.accept(guidance(icon = 1, distance = 999)))
    }

    /** 抖回旧值会清掉待确认项 —— 不能让 999 第二次命中时通过。 */
    @Test fun returningToTheOldValueCancelsThePendingCommit() {
        val stabilizer = ClusterGuidanceStabilizer()
        stabilizer.accept(guidance(icon = 1, distance = 1000))
        assertNull(stabilizer.accept(guidance(icon = 1, distance = 999)))
        assertNull(stabilizer.accept(guidance(icon = 1, distance = 1000)))
        // 待确认项已被清掉，再来一次 999 要重新计两次。
        assertNull(stabilizer.accept(guidance(icon = 1, distance = 999)))
        assertEquals(guidance(icon = 1, distance = 999), stabilizer.accept(guidance(icon = 1, distance = 999)))
    }

    /**
     * 距离趋零时死区**不设下限**。
     *
     * `scale / 509` 在 scale < 509 时向下取整成 0 —— 语义是"已经贴近路口，
     * 每一米都是真变化，立即显示"。这比给死区兜一个 1 m 下限更正确：
     * 兜底会让 100 m → 99 m 这种明显的近距变化被吃掉（1 > 100/509 = 0）。
     */
    @Test fun everyMeterCountsOnceTheDistanceIsNearZero() {
        val stabilizer = ClusterGuidanceStabilizer()
        stabilizer.accept(guidance(icon = 1, distance = 1))
        // scale=1 → 死区 0 → 1 米的差异超差区，立刻显示。
        assertEquals(guidance(icon = 1, distance = 0), stabilizer.accept(guidance(icon = 1, distance = 0)))
        assertEquals(guidance(icon = 1, distance = 5), stabilizer.accept(guidance(icon = 1, distance = 5)))
    }

    /**
     * [ClusterGuidanceStabilizer.reset] 清空状态；重连后第一帧必须立即出现。
     *
     * 注意"导航结束"**不走** accept —— 收卡由 `refreshTurnOverlay` 单独处理，
     * 因为"发布 null 收卡"和"不发布"都用 `null` 表示返回值，含义相反。
     */
    @Test fun resetClearsEverythingSoTheNextSessionStartsClean() {
        val stabilizer = ClusterGuidanceStabilizer()
        stabilizer.accept(guidance(icon = 1, distance = 1000))
        stabilizer.reset()
        // 没有 reset 的话这一帧会先走死区判定被吃掉。
        assertEquals(guidance(icon = 1, distance = 1000), stabilizer.accept(guidance(icon = 1, distance = 1000)))
    }

    /** ETA 每秒变，不能让指纹跟着变 —— 否则滞回完全失效。 */
    @Test fun aChangingEtaDoesNotCountAsANewManeuver() {
        val stabilizer = ClusterGuidanceStabilizer()
        stabilizer.accept(guidance(icon = 1, distance = 1000).copy(remainingSeconds = 300))
        // ETA 变了但距离在死区内 —— 不该因为 ETA 就立刻发布。
        assertNull(
            stabilizer.accept(guidance(icon = 1, distance = 999).copy(remainingSeconds = 299)),
        )
    }

    @Test fun aBigJumpBypassesTheTwoStageCommit() {
        val stabilizer = ClusterGuidanceStabilizer()
        stabilizer.accept(guidance(icon = 1, distance = 1000))
        // 500 m 的变化远超死区 —— 一帧就够，不需要两次确认。
        assertEquals(guidance(icon = 1, distance = 500), stabilizer.accept(guidance(icon = 1, distance = 500)))
    }

    private fun guidance(
        icon: Int = 1,
        roundaboutExit: Int = 0,
        distance: Int,
        road: String = "测试路",
    ) = ClusterTurnGuidance(
        icon = icon,
        roundaboutExit = roundaboutExit,
        distanceMeters = distance,
        road = road,
    )
}
