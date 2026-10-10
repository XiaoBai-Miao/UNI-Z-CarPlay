package com.shilapi.xcertplay.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B561 v67: 双路车辆数据（logcat 通路）单测。
 *
 * ## 为什么这些必须测
 *
 * 解析器是**纯文本 → 数值**的转换，编译期完全测不出来，而它错的时候是
 * "显示出一个看似合理的错数字" —— 车速差 100 倍、挡位映射错一位，
 * 都不会崩溃，只会让人以为车坏了。这类 bug 只能靠单测挡。
 *
 * 融合规则同理：VHAL/logcat 谁优先、过期值怎么处理，
 * 都是"看起来对但语义错"的高风险点。
 */
class VehicleLogTapTest {

    // ------------------------------------------------------------ 行特征过滤

    @Test
    fun ignoresUnrelatedLogLines() {
        assertFalse(VehicleLogTap.looksLikeVehicleLine("I/ActivityManager: Start proc com.android.systemui"))
        assertFalse(VehicleLogTap.looksLikeVehicleLine("D/AudioFlinger: mixer ready"))
    }

    @Test
    fun acceptsVehiclePropertyLines() {
        assertTrue(VehicleLogTap.looksLikeVehicleLine("V/CarCabin: getIntProperty 0x2140f4a9 data=1234"))
        assertTrue(VehicleLogTap.looksLikeVehicleLine("D/Vhal: prop=0x2160f502 value=300"))
        assertTrue(VehicleLogTap.looksLikeVehicleLine("I/CarProperty: SPEED=45"))
    }

    // ------------------------------------------------------------ propId + 数值配对

    @Test
    fun parsesPropIdWithDataEquals() {
        val tap = VehicleLogTap()
        assertTrue(tap.parseLine("V/CarCabin: getIntProperty 0x2140f4a9 data=1234", 1_000L))
        val value = tap.value(CarCabinVehicleStatus.ID_SPEED, 1_000L)
        assertNotNull(value)
        // 1234 原始值 ×0.01 = 12.34 km/h
        assertEquals(12.34, value!!.scaled(CarCabinVehicleStatus.ID_SPEED), 0.001)
    }

    @Test
    fun parsesPropIdWithValueEquals() {
        val tap = VehicleLogTap()
        assertTrue(tap.parseLine("D/Vhal: prop=0x2140f477 value=24", 1_000L))
        val gear = tap.value(CarCabinVehicleStatus.ID_GEAR, 1_000L)
        assertNotNull(gear)
        assertEquals(24.0, gear!!.scaled(CarCabinVehicleStatus.ID_GEAR), 0.001)
    }

    @Test
    fun parsesHighVoltageAttributesOnlyVisibleInLogs() {
        val tap = VehicleLogTap()
        // 这两个属性挂在 car_ev/car_power 上，VHAL 反射读不到，只有日志里有。
        assertTrue(tap.parseLine("I/CarEv: prop=0x2160f502 value=1250", 1_000L))
        assertTrue(tap.parseLine("I/CarEv: prop=0x2160f503 value=380", 1_000L))
        assertNotNull(tap.value(CarCabinVehicleStatus.ID_EV_CURRENT, 1_000L))
        assertNotNull(tap.value(CarCabinVehicleStatus.ID_EV_VOLTAGE, 1_000L))
    }

    /**
     * ★ 车速单位换算是本文件最关键的一条断言。
     *
     * `0x2140f4a9` 的单位是 0.01 km/h。若换算漏掉，静止时"0"看着没问题，
     * 一起步 60 km/h 会显示成 **6000** —— 这是那种能一路开到用户发现都不知道
     * 哪错了的 bug。
     */
    @Test
    fun speedUnitIsHundredthsOfKmh() {
        val tap = VehicleLogTap()
        tap.parseLine("V/CarCabin: getIntProperty 0x2140f4a9 data=6000", 1_000L)
        val value = tap.value(CarCabinVehicleStatus.ID_SPEED, 1_000L)
        assertNotNull(value)
        assertEquals(60.0, value!!.scaled(CarCabinVehicleStatus.ID_SPEED), 0.01)
    }

    /** 未知属性不做任何换算 —— 没有实车证据的单位一律当1.0，猜错会差 100 倍。 */
    @Test
    fun unknownPropIdIsNotRescaled() {
        val tap = VehicleLogTap()
        tap.parseLine("I/CarEv: prop=0x2160f502 value=1250", 1_000L)
        val value = tap.value(CarCabinVehicleStatus.ID_EV_CURRENT, 1_000L)
        assertNotNull(value)
        assertEquals(1250.0, value!!.scaled(CarCabinVehicleStatus.ID_EV_CURRENT), 0.001)
    }

    // ------------------------------------------------------------ TTL 过期

    /**
     * ★ 过期值必须读不到。
     *
     * 原厂停止打点后，日志缓冲里会留着最后一条旧车速。若不过滤，
     * 车停下 5 分钟后仪表还显示 60km/h —— 假读数比没有读数危险得多。
     */
    @Test
    fun staleValuesExpireAfterTtl() {
        val tap = VehicleLogTap()
        tap.parseLine("V/CarCabin: getIntProperty 0x2140f4a9 data=6000", 1_000L)
        val ttl = VehicleLogTap.TTL_MILLIS
        // TTL 内可读
        assertNotNull(tap.value(CarCabinVehicleStatus.ID_SPEED, 1_000L + ttl - 1))
        // 超时即失效
        assertNull(tap.value(CarCabinVehicleStatus.ID_SPEED, 1_000L + ttl + 1))
    }

    /** [allValues] 要保留过期值 —— 诊断面板需要看"历史上抓到过什么"。 */
    @Test
    fun allValuesKeepsExpiredEntriesForDiagnostics() {
        val tap = VehicleLogTap()
        tap.parseLine("V/CarCabin: getIntProperty 0x2140f4a9 data=6000", 1_000L)
        val later = 1_000L + VehicleLogTap.TTL_MILLIS + 1
        assertTrue(tap.allValues().containsKey(CarCabinVehicleStatus.ID_SPEED))
        assertFalse(tap.snapshot(later).containsKey(CarCabinVehicleStatus.ID_SPEED))
    }

    // ------------------------------------------------------------ 后值覆盖前值

    /** logcat 是顺序流，越新越真 —— 后到的必须覆盖先到的。 */
    @Test
    fun laterValueOverwritesEarlier() {
        val tap = VehicleLogTap()
        tap.parseLine("V/CarCabin: getIntProperty 0x2140f4a9 data=1000", 1_000L)
        tap.parseLine("V/CarCabin: getIntProperty 0x2140f4a9 data=2000", 2_000L)
        val value = tap.value(CarCabinVehicleStatus.ID_SPEED, 2_000L)
        assertNotNull(value)
        assertEquals(20.0, value!!.scaled(CarCabinVehicleStatus.ID_SPEED), 0.001)
    }

    // ------------------------------------------------------------ 计数与诊断

    @Test
    fun countsMatchedAndScannedLines() {
        val tap = VehicleLogTap()
        tap.parseLine("V/CarCabin: getIntProperty 0x2140f4a9 data=1234", 1_000L)
        tap.parseLine("I/ActivityManager: something else", 1_000L)
        assertEquals(1, tap.matchedLines)
    }

    @Test
    fun explainLineDescribesAPair() {
        val text = VehicleLogTap.explainLine("V/CarCabin: getIntProperty 0x2140f4a9 data=1234")
        assertNotNull(text)
        assertTrue(text!!.contains("0x2140f4a9"))
    }
}
/**
 * B561 v67: 融合规则单测。
 *
 * 融合只有一条规则，但它是整个双路设计的核心 —— 一旦写反（logcat 优先）
 * 会导致：陈旧日志值盖住新鲜的 VHAL 主动轮询读数，车速显示滞后好几秒。
 */
class HybridVehicleStatusFuseTest {

    @Test
    fun vhalWinsWhenBothPresent() {
        val fused = HybridVehicleStatus.fuse(60.0f, 80.0f)
        assertEquals(60.0f, fused.value!!, 0.001f)
        assertEquals(HybridVehicleStatus.SOURCE_VHAL, fused.source)
        assertTrue(fused.present)
    }

    /** logcat 存在的全部意义：VHAL 读不到时能补上。 */
    @Test
    fun logFillsTheGapWhenVhalMisses() {
        val fused = HybridVehicleStatus.fuse(null, 42.0f)
        assertEquals(42.0f, fused.value!!, 0.001f)
        assertEquals(HybridVehicleStatus.SOURCE_LOG, fused.source)
    }

    /** 两路都没有 → null + none，绝不编默认值。 */
    @Test
    fun bothMissingYieldsNothing() {
        val fused = HybridVehicleStatus.fuse<Int>(null, null)
        assertNull(fused.value)
        assertEquals(HybridVehicleStatus.SOURCE_NONE, fused.source)
        assertFalse(fused.present)
    }

    /** 值为 0 是**有效读数**（车真的停着），不能被当成"没有"。 */
    @Test
    fun zeroIsAValidReadingNotAMiss() {
        val fused = HybridVehicleStatus.fuse(0f, 80.0f)
        assertEquals(0f, fused.value!!, 0.001f)
        assertEquals(HybridVehicleStatus.SOURCE_VHAL, fused.source)
    }
}
