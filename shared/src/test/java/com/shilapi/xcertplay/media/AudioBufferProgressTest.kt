package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class AudioBufferProgressTest {
    /**
     * B561: 恢复下限。
     *
     * 用 0 会退回`== 0L` 的老行为 —— 那正是"underrun 后再也不重新武装"的 bug。
     * 测试里显式给一个非零下限，确保判定走的是新语义。
     */
    private val floor = 100L

    @Test fun musicRebuffersOnlyAfterHardwareAndIncomingQueuesDrain() {
        val buffer = AudioBufferProgress(4)
        buffer.written(4000)
        // 每个 shouldRebuffer 都会采样一次 queuedBytes，从而推进 playedFrames。
        // 所以这里的 head 必须**单调递增**，否则采样点会互相干扰。
        // head=999 → 999×4=3996 → queued=4（低于下限 100 → 会触发，用来看门控）
        assertFalse(buffer.shouldRebuffer(true, true, true, true, 900, floor))   // queued=400
        assertFalse(buffer.shouldRebuffer(true, true, true, false, 900, floor))  // 压缩队列非空
        assertFalse(buffer.shouldRebuffer(true, true, false, true, 900, floor))  // 本次播放无 underrun
        assertTrue(buffer.shouldRebuffer(true, true, true, true, 999, floor))    // queued=4
        assertFalse(buffer.shouldRebuffer(true, false, true, true, 999, floor))  // 已暂停
        buffer.written(400)
        assertEquals(404L, buffer.queuedBytes(999))
        assertFalse(buffer.shouldRebuffer(true, true, true, true, 999, floor))   // 补帧后回到上限之上
    }

    @Test fun doesNotAddRebufferDelayToCallsOrSpeech() {
        val buffer = AudioBufferProgress(2)
        assertFalse(buffer.shouldRebuffer(false, true, true, true, 0, floor))
    }

    /**
     * B561: 排空判定必须命中"接近排空"而不只是"精确等于 0"。
     *
     * 这是本次修复的核心 —— 上游在 **MT8666 Android 9**（B561 同款 SoC）上
     * 观察到 underrun 持续二十分钟而 `rebufferCount` 始终不动，
     * 根因就是 `== 0L` 在有残差时永远不成立。
     */
    @Test fun aResidualBelowTheFloorStillTriggersRecovery() {
        val buffer = AudioBufferProgress(4)
        // 写 1000 字节 → 播放头 998 帧 × 4 字节 = 9992? 不对：帧数 998 意味着
        // 还没写那么多。这里直接写够：写 4000，播放头 999 帧 = 3996 字节 → 余 4 字节。
        buffer.written(4000)
        assertEquals(4L, buffer.queuedBytes(999))
        assertTrue(
            "残余 4 字节应低于下限 100，必须触发恢复",
            buffer.shouldRebuffer(true, true, true, true, 999, floor),
        )
    }

    /** 超出下限时**不**触发 —— 否则会把正常的短暂波动当成 starvation。 */
    @Test fun aQueueAboveTheFloorDoesNotTriggerRecovery() {
        val buffer = AudioBufferProgress(4)
        buffer.written(4000)
        // 播放头 900 帧 = 3600 字节 → 余 400 字节，远超下限。
        assertEquals(400L, buffer.queuedBytes(900))
        assertFalse(buffer.shouldRebuffer(true, true, true, true, 900, floor))
    }

    /**
     * 反复采样不再精确回到 0 时，恢复流程仍会执行。
     *
     * 这是老 bug 的完整复现：写 4000 → 播放头走到 1000 帧（queued=0）→
     * 再补一点帧使电平弹回 40 → 此后 queued 恒为 40，
     * `== 0L` 再也不成立，pause→resume 恢复流程永远不执行。
     */
    @Test fun recoveryStillFiresAfterTheLevelBouncesBackAboveZero() {
        val buffer = AudioBufferProgress(4)
        buffer.written(4000)
        assertTrue(buffer.shouldRebuffer(true, true, true, true, 1000, floor))
        buffer.written(40)
        // 播放头没动，电平从 0弹到 40 —— 老实现在这里就永远失效了。
        assertEquals(40L, buffer.queuedBytes(1000))
        assertTrue(buffer.shouldRebuffer(true, true, true, true, 1000, floor))
    }

    @Test fun unsignedPlaybackHeadWrapKeepsQueuedAudio() {
        val buffer = AudioBufferProgress(2)
        repeat(8) { buffer.written(1_073_741_824) }
        buffer.written(40)
        assertEquals(42, buffer.queuedBytes(-1))
        assertEquals(40, buffer.queuedBytes(0))
        assertEquals(0, buffer.queuedBytes(20))
    }
}
