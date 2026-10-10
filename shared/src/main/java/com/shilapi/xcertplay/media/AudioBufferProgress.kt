package com.shilapi.xcertplay.media

/** Tracks the unsigned AudioTrack head across wrap without flushing already queued sound. */
internal class AudioBufferProgress(private val frameBytes: Int) {
    private var writtenBytes = 0L
    private var playedFrames = 0L
    private var lastHead = 0L

    fun written(bytes: Int) { writtenBytes += bytes }

    fun queuedBytes(rawHead: Int): Long {
        val head = rawHead.toLong() and 0xffff_ffffL
        playedFrames += (head - lastHead) and 0xffff_ffffL
        lastHead = head
        return (writtenBytes - playedFrames * frameBytes).coerceAtLeast(0)
    }

    /**
     * B561: 是否需要重新预缓冲。
     *
     * ## 为什么下限是 `<= floorBytes` 而不是 `== 0L`
     *
     * 上游 0.2.14（及本项目 0.2.14 基线）判定"硬件缓冲真的排空了"用的是
     * `queuedBytes(rawHead) == 0L`。这个精确等于在实车上**再也命不中**：
     *
     * 一次 underrun 会在"已写入账本"（`writtenBytes`）与"播放头"
     * （`playedFrames * frameBytes`）之间留下残差；或者维护循环在部分补帧之后
     * 才采样，把电平弹回 0 以上。此后 `== 0L` **永远不成立**，于是
     * "暂停 → 按start 阈值重新预缓冲"这套恢复流程再也不执行 ——
     * 每个饥饿窗口都退化成连续 underrun。
     *
     * 上游作者在 **MT8666 Android 9**（B561 的 `spm8666p1_64_car` 同款 SoC）上
     * 观察到：预估缓冲停在 ~140 ms、underrun 持续二十分钟、`rebufferCount` 却不動，
     * 只有暂停再恢复音乐才清得掉。
     *
     * 改成"低于恢复下限"即触发，语义也更贴近意图：我们关心的是** starving **，
     * 不是"某个精确的电平值"。
     *
     * @param floorBytes 恢复下限。调用方传 `startThresholdBytes / 2` ——
     *   取一半而不是 0，是为了让恢复留出余量：完全排空后重新灌满 start 阈值
     *   才有意义，而排到一半就已经明确 starvation 了。
     */
    fun shouldRebuffer(isMedia: Boolean, playing: Boolean, underrunSinceStart: Boolean,
        compressedQueueEmpty: Boolean, rawHead: Int, floorBytes: Long): Boolean =
        isMedia && playing && underrunSinceStart && compressedQueueEmpty &&
            queuedBytes(rawHead) <= floorBytes
}
