package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B561: 车道指引（0x5204）与路口几何（0x5202 的 0x09/0x0A）的解析契约。
 *
 * 这些字段号对齐 Apple `+[ACCNavigationRouteGuidanceUpdateInfo keyForType:]`
 * —— 写错一位就会把箭头画到隔壁车道上，所以这里逐个字段钉死。
 *
 * 走 Robolectric 是因为 [BydHudRouteState] 解析失败/成功时会打 `android.util.Log`
 * （原hex dump 与 `laneSource`），纯 JUnit 下那是未 mock 的桩。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ClusterLaneGuidanceTest {

    @Test fun laneEventParsesIndexLanesAndPrimary() {
        val lanes = laneRecords(
            laneRecord(position = 0, status = 2, angles = listOf(0)),   // 推荐：直行
            laneRecord(position = 1, status = 1, angles = listOf(-90)), // 左转
            laneRecord(position = 2, status = 1, angles = listOf(90)),  // 右转
        )
        val payload = tlv(0x01, u16(7)) + tlv(0x02, lanes) + tlv(0x03, "示例".toByteArray())

        val parsed = ClusterLaneGuidance.parse(payload)
        assertNotNull(parsed)
        assertEquals(7, parsed!!.eventIndex)
        assertEquals(3, parsed.laneCount)
        assertEquals(listOf(listOf(0), listOf(-90), listOf(90)), parsed.angles)
        assertEquals(listOf(0, 1, 2), parsed.positions)
        assertEquals(0, parsed.primaryLane)
        assertTrue(parsed.sane)
    }

    @Test fun onlyStatusTwoCountsAsTheRecommendedLane() {
        val lanes = laneRecords(
            // 第一条直行但 status=1（不是推荐），第二条左转 status=2（推荐）。
            laneRecord(position = 0, status = 1, angles = listOf(0)),
            laneRecord(position = 1, status = 2, angles = listOf(-90)),
        )
        val parsed = ClusterLaneGuidance.parse(tlv(0x01, u16(1)) + tlv(0x02, lanes))
        assertNotNull(parsed)
        assertEquals(1, parsed!!.primaryLane)
    }

    @Test fun negativeAnglesSurviveAsSignedDegrees() {
        val lanes = laneRecord(position = 0, status = 2, angles = listOf(-135, 180))
        val parsed = ClusterLaneGuidance.parse(tlv(0x01, u16(2)) + tlv(0x02, lanes))
        assertNotNull(parsed)
        assertEquals(listOf(listOf(-135, 180)), parsed!!.angles)
        assertTrue(parsed.sane)
    }

    /**
     * 半个车道（长度对不上）必须**整条丢弃**。
     *
     * 部分解析会把两条车道错位一格，箭头画到隔壁车道上 —— 比不画危险得多。
     */
    @Test fun aTruncatedLaneRecordDiscardsTheWholeEvent() {
        val lanes = laneRecord(position = 0, status = 2, angles = listOf(0))
        val truncated = lanes.copyOf(lanes.size - 1) // 少一个角度
        assertNull(ClusterLaneGuidance.parse(tlv(0x01, u16(1)) + tlv(0x02, truncated)))
    }

    @Test fun anOutOfRangeAngleDiscardsTheWholeEvent() {
        // 0x7FFF = 32767 度，不可能来自 iOS。
        val lanes = byteArrayOf(0, 0, 2, 1, 0, 0, 0x7F, 0xFF.toByte(), 0x00)
        assertNull(ClusterLaneGuidance.parse(tlv(0x01, u16(1)) + tlv(0x02, lanes)))
    }

    /**
     * `position` 是 **u16**，可以大于 8 —— 限制的是车道**条数**（≤8），不是序号。
     *
     * 把它当 u8 读会让序号 256 的那条车道变成 0，箭头直接画到最左边。
     */
    @Test fun positionIsSixteenBitsSoLargeIndexesSurvive() {
        val lanes = laneRecords(
            laneRecord(position = 300, status = 1, angles = listOf(-90)),
            laneRecord(position = 301, status = 2, angles = listOf(0)),
        )
        val parsed = ClusterLaneGuidance.parse(tlv(0x01, u16(1)) + tlv(0x02, lanes))
        assertNotNull(parsed)
        assertEquals(listOf(300, 301), parsed!!.positions)
        assertEquals(1, parsed.primaryLane)
    }

    /**
     * ★ 布局对不上就**整条丢弃**，绝不半解析。
     *
     * 车道数据错一位就会把箭头画到隔壁车道上 —— 实车上那是危险的，
     * 比"路口放大图不出现"糟得多。这条守住"宁缺勿画"。
     */
    @Test fun trailingBytesThatDoNotFormARecordDiscardTheEvent() {
        val lanes = laneRecords(laneRecord(position = 0, status = 2, angles = listOf(0)))
        val padded = lanes + byteArrayOf(0x00, 0x01) // 多出两字节
        assertNull(ClusterLaneGuidance.parse(tlv(0x01, u16(1)) + tlv(0x02, padded)))
    }

    @Test fun aMissingIndexDiscardsTheEvent() {
        val lanes = laneRecord(position = 0, status = 2, angles = listOf(0))
        assertNull(ClusterLaneGuidance.parse(tlv(0x02, lanes)))
    }

    /**
     * 诊断转储：解析失败时靠它把真实字节带回来。
     *
     * 车道记录的布局是推出来的（见 [ClusterLaneGuidance.parseLanes]），
     * 第一次实车验证必须能看到原始字节才能钉死偏移。
     */
    @Test fun theDiagnosticDumpKeepsTheLeadingBytes() {
        val payload = ByteArray(60) { it.toByte() }
        val dump = ClusterLaneGuidance.dump(payload)
        assertTrue(dump, dump.startsWith("000102030405060708090A0B0C0D0E0F"))
        assertTrue(dump, dump.contains("60B"))
    }

    @Test fun aTruncatedOuterTlvDiscardsTheEvent() {
        val payload = tlv(0x01, u16(1))
        assertNull(ClusterLaneGuidance.parse(payload.copyOf(payload.size - 1)))
    }

    @Test fun junctionGeometrySurvivesOnTheManeuverAndOnlyShowsWhileFlagged() {
        val state = BydHudRouteState(nanoTime = { 0L })
        val junctionAngles = tlv(0x01, u16(0)) + tlv(0x01, u16(90)) + tlv(0x01, u16(0xFFB4.toInt().let { 0x10000 - 76 }))
        val maneuver = tlv(0x01, u16(0)) +
            tlv(0x03, byteArrayOf(3)) +
            tlv(0x04, "转入路".toByteArray() + 0) +
            tlv(0x08, byteArrayOf(0)) +
            tlv(0x09, byteArrayOf(1)) +
            tlv(0x0A, junctionAngles)
        state.accept(BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE, maneuver)

        val lanes = laneRecord(position = 0, status = 2, angles = listOf(0))
        state.accept(ClusterLaneGuidance.ROUTE_GUIDANCE_LANE_INFO, tlv(0x01, u16(4)) + tlv(0x02, lanes))

        // iOS 还没说"该显示" → 不给数据（仪表上不该留着车道箭头）。
        assertNull(state.currentLaneGuidance())

        // 0x5201 指向事件 4 并打开显示开关。
        state.accept(
            BydHudRouteState.ROUTE_GUIDANCE_UPDATE,
            tlv(0x01, byteArrayOf(1)) +
                tlv(0x0a, u32(120)) +
                tlv(0x0d, u16(0)) +
                tlv(0x10, u16(4)) +
                tlv(0x12, byteArrayOf(1)),
        )
        val guidance = state.currentLaneGuidance()
        assertNotNull(guidance)
        assertEquals(4, guidance!!.eventIndex)
        assertTrue(guidance.drawable)
        // 路口几何来自 maneuver（0x5204 里没有 0x09/0x0A）。
        assertEquals(ClusterLaneGuidance.JunctionKind.ROUNDABOUT, guidance.junctionKind)
        assertEquals(listOf(0, 90, -76), guidance.junctionAngles)
    }

    @Test fun showingOffHidesTheLanesEvenWithAnEventCached() {
        val state = BydHudRouteState(nanoTime = { 0L })
        state.accept(
            BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE,
            tlv(0x01, u16(0)) + tlv(0x03, byteArrayOf(3)) + tlv(0x04, "路".toByteArray() + 0),
        )
        val lanes = laneRecord(position = 0, status = 2, angles = listOf(0))
        state.accept(ClusterLaneGuidance.ROUTE_GUIDANCE_LANE_INFO, tlv(0x01, u16(1)) + tlv(0x02, lanes))
        state.accept(
            BydHudRouteState.ROUTE_GUIDANCE_UPDATE,
            tlv(0x01, byteArrayOf(1)) + tlv(0x0d, u16(0)) + tlv(0x10, u16(1)) + tlv(0x12, byteArrayOf(0)),
        )
        assertNull(state.currentLaneGuidance())
    }

    @Test fun clearDropsTheLaneCacheSoTheNextRouteDoesNotInheritArrows() {
        val state = BydHudRouteState(nanoTime = { 0L })
        state.accept(
            BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE,
            tlv(0x01, u16(0)) + tlv(0x03, byteArrayOf(3)) + tlv(0x04, "路".toByteArray() + 0),
        )
        val lanes = laneRecord(position = 0, status = 2, angles = listOf(0))
        state.accept(ClusterLaneGuidance.ROUTE_GUIDANCE_LANE_INFO, tlv(0x01, u16(1)) + tlv(0x02, lanes))
        state.accept(
            BydHudRouteState.ROUTE_GUIDANCE_UPDATE,
            tlv(0x01, byteArrayOf(1)) + tlv(0x0d, u16(0)) + tlv(0x10, u16(1)) + tlv(0x12, byteArrayOf(1)),
        )
        assertNotNull(state.currentLaneGuidance())

        // 路线结束 → NoRouteSet。
        state.accept(BydHudRouteState.ROUTE_GUIDANCE_UPDATE, tlv(0x01, byteArrayOf(0)))
        assertNull(state.currentLaneGuidance())

        // 新路线复用了同一个 eventIndex，也不该继承上一条路线的箭头。
        state.accept(
            BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE,
            tlv(0x01, u16(0)) + tlv(0x03, byteArrayOf(3)) + tlv(0x04, "新路".toByteArray() + 0),
        )
        state.accept(
            BydHudRouteState.ROUTE_GUIDANCE_UPDATE,
            tlv(0x01, byteArrayOf(1)) + tlv(0x0d, u16(0)) + tlv(0x10, u16(1)) + tlv(0x12, byteArrayOf(1)),
        )
        assertNull(state.currentLaneGuidance())
    }

    // ── TLV helpers ──────────────────────────────────────────────────────

    /** 把多条车道记录拼成 `0x02` 容器的内容。 */
    private fun laneRecords(vararg records: ByteArray): ByteArray {
        val out = ByteArray(records.sumOf { it.size })
        var at = 0
        records.forEach { record ->
            record.copyInto(out, at)
            at += record.size
        }
        return out
    }

    /**
     * 车道记录：`u16 position | u8 status | u8 angleCount | i16 primary | i16[n] angles`。
     *
     * 固定头 6 字节，与 [ClusterLaneGuidance.parseLanes] 的布局一致；
     * [primary] 缺省用第一个方向（`0xFFFF` = 无）。
     */
    private fun laneRecord(
        position: Int,
        status: Int,
        angles: List<Int>,
        primary: Int? = null,
    ): ByteArray {
        val out = ArrayList<Byte>()
        out.addAll(u16(position).toList())
        out.add(status.toByte())
        out.add(angles.size.toByte())
        out.addAll(i16(primary ?: angles.firstOrNull() ?: 0).toList())
        angles.forEach { out.addAll(i16(it).toList()) }
        return out.toByteArray()
    }

    // ──扁平 u8 回退路径（DiPlay-CN 解读）────────────────────────────────

    /**
     * 扁平布局：`0x01` u8 推荐下标 + `0x02`/`0x03` 各 u8 方向。
     *
     * 严格路径要求 `0x01` 值区 ≥ 2 字节（u16 事件序号），这里只有 1 字节，
     * 所以严格路径自然落空，由扁平路径接手。
     */
    @Test fun flatDirectionBytesFallBackToTheFlatParser() {
        val payload = tlv(0x01, byteArrayOf(1)) + // 推荐车道 = 下标 1
            tlv(0x02, byteArrayOf(2)) + // 左转
            tlv(0x03, byteArrayOf(1, 0, 3)) // 直 / 不用 / 右 → 共 4 条车道

        val parsed = ClusterLaneGuidance.parse(payload)
        assertNotNull(parsed)
        assertEquals(ClusterLaneGuidance.LaneSource.FLAT, parsed!!.source)
        // 0x02 的 1 条 + 0x03 的 3 条 = 4 条车道
        assertEquals(4, parsed.laneCount)
        // 枚举转成与严格路径一致的角度：2左=-90, 1直=0, 0不用=空列表
        assertEquals(listOf(-90), parsed.angles[0])
        assertEquals(listOf(0), parsed.angles[1])
        assertEquals(emptyList<Int>(), parsed.angles[2])
        assertEquals(listOf(90), parsed.angles[3])
        assertEquals(1, parsed.primaryLane)
    }

    /**
     * ★ 扁平路径**不能**吞掉严格布局的载荷。
     *
     * 这是实测翻过的车：严格布局的车道记录里`u16 position` 的高位字节、
     * `status`、`angleCount` 常常恰好落在 0..3（方向枚举的值域），
     * 没有结构性判据时会被当成 4~8 条合法车道凭空"解析"出来，
     * 画出与真实车道完全无关的箭头带。
     *
     * 判据是 `0x02` 值区长度：扁平每条车道 1 字节，严格是 6 字节定长头的容器。
     */
    @Test fun theFlatPathRefusesAStrictLayoutContainer() {
        val lanes = laneRecord(position = 0, status = 2, angles = listOf(0))
        val parsed = ClusterLaneGuidance.parse(tlv(0x02, lanes))
        // 严格路径因缺 0x01 失败；扁平路径必须因0x02 太长而拒绝，而不是硬解。
        assertNull(parsed)
    }

    /** 同上，但带上 u16 的 `0x01` —— 此时严格路径成功，扁平路径根本不该被调用。 */
    @Test fun aStrictLayoutWithAnIndexIsNeverStolenByTheFlatPath() {
        val lanes = laneRecord(position = 0, status = 2, angles = listOf(0))
        val parsed = ClusterLaneGuidance.parse(tlv(0x01, u16(3)) + tlv(0x02, lanes))
        assertNotNull(parsed)
        assertEquals(ClusterLaneGuidance.LaneSource.STRICT, parsed!!.source)
        assertEquals(1, parsed.laneCount)
    }

    /**
     * 缺 `0x01` 的短载荷也不能走扁平 —— 没有推荐车道字段就不是扁平布局。
     *
     * 堵住最后一个漏洞：严格布局的 `0x03`（Description 短文本）字节
     * 恰好全落在 0..3 时仍可能被误收。
     */
    @Test fun theFlatPathRequiresTheSingleByteHighlightField() {
        // 只有 0x03，且内容恰好全是 0..3 —— 没有 0x01 就是不能解。
        assertNull(ClusterLaneGuidance.parse(tlv(0x03, byteArrayOf(1, 2, 3))))
    }

    /**
     * 方向字节必须落在 0..3。
     *
     * 枚举外的值说明这不是扁平布局 —— 整条丢弃，**不能跳过那一个字节继续收**，
     * 否则错位的数据会画出"看起来合理但完全错误"的车道带。
     */
    @Test fun anOutOfRangeFlatDirectionDiscardsTheWholeEvent() {
        val payload = tlv(0x01, byteArrayOf(0)) + tlv(0x02, byteArrayOf(1, 9, 3))
        assertNull(ClusterLaneGuidance.parse(payload))
    }

    /** 没有任何方向 TLV → 两条路径都不适用。 */
    @Test fun aPayloadWithoutDirectionTlvsIsDiscarded() {
        assertNull(ClusterLaneGuidance.parse(tlv(0x01, byteArrayOf(0))))
    }

    /** 严格路径优先：定长头布局不会被扁平路径抢走。 */
    @Test fun theStrictLayoutWinsWhenItFits() {
        val lanes = laneRecord(position = 0, status = 2, angles = listOf(0))
        val parsed = ClusterLaneGuidance.parse(tlv(0x01, u16(7)) + tlv(0x02, lanes))
        assertNotNull(parsed)
        assertEquals(ClusterLaneGuidance.LaneSource.STRICT, parsed!!.source)
        assertEquals(7, parsed.eventIndex)
    }

    /** 扁平布局超过 8 条车道时截断而不是丢弃 —— iOS 上限就是 8。 */
    @Test fun flatLanesBeyondTheCapAreTruncated() {
        // 两个 TLV 各 4 字节（都在 FLAT_MAX_DIRECTION_TLV=8 以内），
        // 合计 8 条刚好到上限；要测截断得用 0x02 + 0x03 各 4 字节 = 8… 
        // 所以直接构造 7+4=11 条：0x02 给 7 字节、0x03 给 4 字节。
        val payload = tlv(0x01, byteArrayOf(0)) +
            tlv(0x02, ByteArray(7) { 1 }) +
            tlv(0x03, ByteArray(4) { 1 })
        val parsed = ClusterLaneGuidance.parse(payload)
        assertNotNull(parsed)
        assertEquals(8, parsed!!.laneCount)
    }

    private fun tlv(type: Int, value: ByteArray): ByteArray = u16(value.size + 4) + u16(type) + value
    private fun u16(value: Int): ByteArray =
        byteArrayOf((value ushr 8).toByte(), value.toByte())

    private fun u32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
    )

    private fun i16(value: Int): ByteArray {
        val raw = value and 0xFFFF
        return byteArrayOf((raw ushr 8).toByte(), raw.toByte())
    }
}
