package com.shilapi.xcertplay

import android.hardware.display.DisplayManager
import android.view.Display
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ClusterMapPresentationTest {
    @Test fun repeatedActiveNotificationsDoNotRestartTheFade() = withMeasuredPresentation { presentation ->
        presentation.setStreamActive(true)
        val video: android.view.View = org.robolectric.util.ReflectionHelpers.getField(presentation, "videoView")
        val label: android.widget.TextView = org.robolectric.util.ReflectionHelpers.getField(presentation, "waitingLabel")
        video.alpha = 0.6f
        label.alpha = 0.4f
        presentation.setStreamActive(true)
        assertEquals(0.6f, video.alpha, 0.001f)
        assertEquals(0.4f, label.alpha, 0.001f)
    }

    @Test fun reversingTheFadeKeepsTheCurrentOpacityAndWaitingLabelVisible() = withMeasuredPresentation { presentation ->
        presentation.setStreamActive(true)
        val video: android.view.View = org.robolectric.util.ReflectionHelpers.getField(presentation, "videoView")
        val label: android.widget.TextView = org.robolectric.util.ReflectionHelpers.getField(presentation, "waitingLabel")
        video.alpha = 0.6f
        label.alpha = 0.4f
        presentation.setStreamActive(false)
        assertEquals(android.view.View.VISIBLE, label.visibility)
        assertEquals(0.4f, label.alpha, 0.001f)
        assertEquals(0.6f, video.alpha, 0.001f)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        assertEquals(android.view.View.VISIBLE, label.visibility)
    }

    @Test fun aNewPresentationStartsWithVisibleWaitingTextAndHiddenVideo() = withPresentation { presentation ->
        val video: android.view.View = org.robolectric.util.ReflectionHelpers.getField(presentation, "videoView")
        val label: android.widget.TextView = org.robolectric.util.ReflectionHelpers.getField(presentation, "waitingLabel")
        assertEquals(if (video is android.view.SurfaceView) 1f else 0f, video.alpha, 0.001f)
        assertEquals(1f, label.alpha, 0.001f)
        assertEquals(android.view.View.VISIBLE, label.visibility)
    }

    @Test fun measuredDarkClusterKeepsReadableWaitingText() {
        val original = android.os.Build.FINGERPRINT
        val contrast = DiLink51ClusterLayout.contrast(context)
        org.robolectric.shadows.ShadowBuild.setFingerprint(DiLink51ClusterLayout.FINGERPRINT)
        DiLink51ClusterLayout.saveContrast(context, DiLink51ClusterLayout.Contrast.DARK)
        try {
            withPresentation(
                name = DiLink51ClusterLayout.FULL,
                theme = DiLink51ClusterLayout.Theme.MAP,
            ) { presentation ->
                val label: android.widget.TextView = org.robolectric.util.ReflectionHelpers.getField(presentation, "waitingLabel")
                assertEquals(android.graphics.Color.WHITE, label.currentTextColor)
            }
        } finally {
            org.robolectric.shadows.ShadowBuild.setFingerprint(original)
            DiLink51ClusterLayout.saveContrast(context, contrast)
        }
    }

    @Test @Config(sdk = [28, 29])
    fun legacySurfaceUsesAnOpaquePlaceholderWithoutChangingSurfaceAlpha() = withPresentation { presentation ->
        val video: android.view.View = org.robolectric.util.ReflectionHelpers.getField(presentation, "videoView")
        val label: android.widget.TextView = org.robolectric.util.ReflectionHelpers.getField(presentation, "waitingLabel")
        assertTrue(video is android.view.SurfaceView)
        val background = label.background as android.graphics.drawable.ColorDrawable
        assertEquals(255, android.graphics.Color.alpha(background.color))
        assertEquals(android.graphics.Color.rgb(233, 238, 246), background.color)
        assertEquals(1f, video.alpha, 0.001f)
        presentation.setStreamActive(true)
        assertEquals(1f, video.alpha, 0.001f)
        label.alpha = 0.4f
        presentation.setStreamActive(false)
        assertEquals(1f, video.alpha, 0.001f)
        assertEquals(0.4f, label.alpha, 0.001f)
        assertEquals(android.view.View.VISIBLE, label.visibility)
    }

    /**
     * B561: 深色模式下等待页必须是**黑底白字**。
     *
     * 实测反馈：夜间模式下仪表上是一块惨白的"等待 CarPlay 地图"。
     *
     * 根因是 `DiLink51ClusterLayout.dark()` 只认比亚迪那套"对比度设置/主题"，
     * 在 B561 上恒为 false —— 而 host 的 `darkMode` 之前根本没传进来。
     * 这条用 [ClusterMapPresentation] 的 `initialNight` 参数守住修复：
     * 调用方传 true 就必须是黑底白字。
     */
    @Test fun aDarkHostGetsABlackWaitingPageWithWhiteText() {
        withPresentation(night = true) { presentation ->
            val label: android.widget.TextView = org.robolectric.util.ReflectionHelpers.getField(presentation, "waitingLabel")
            assertEquals(android.graphics.Color.WHITE, label.currentTextColor)
            val background = label.background as android.graphics.drawable.ColorDrawable
            val color = background.color
            // 黑底：三个通道都低于 32，且完全不透明。
            assertEquals(255, android.graphics.Color.alpha(color))
            assertTrue("red=${android.graphics.Color.red(color)}", android.graphics.Color.red(color) < 32)
            assertTrue("green=${android.graphics.Color.green(color)}", android.graphics.Color.green(color) < 32)
            assertTrue("blue=${android.graphics.Color.blue(color)}", android.graphics.Color.blue(color) < 32)
        }
    }

    /** B561: 夜间切到白天（车机主题跟随环境光变）时等待页要跟着换回浅底深字。 */
    @Test fun switchingToDayRestoresTheLightWaitingPage() {
        withPresentation(night = true) { presentation ->
            val label: android.widget.TextView = org.robolectric.util.ReflectionHelpers.getField(presentation, "waitingLabel")
            presentation.setNightMode(false)
            assertEquals(android.graphics.Color.DKGRAY, label.currentTextColor)
            val background = label.background as android.graphics.drawable.ColorDrawable
            assertEquals(android.graphics.Color.rgb(233, 238, 246), background.color)
            presentation.setNightMode(true)
            assertEquals(android.graphics.Color.WHITE, label.currentTextColor)
        }
    }

    /** B561: 幂等 —— 重复设同一个夜色不该把等待页重画（否则会闪）。 */
    @Test fun settingTheSameNightValueTwiceKeepsTheBackground() {
        withPresentation(night = true) { presentation ->
            val root: android.widget.FrameLayout = org.robolectric.util.ReflectionHelpers.getField(presentation, "rootView")
            val before = root.background
            presentation.setNightMode(true)
            assertSame(before, root.background)
        }
    }

    private fun withMeasuredPresentation(block: (ClusterMapPresentation) -> Unit) {
        val original = android.os.Build.FINGERPRINT
        org.robolectric.shadows.ShadowBuild.setFingerprint(DiLink51ClusterLayout.FINGERPRINT)
        try {
            withPresentation(
                name = DiLink51ClusterLayout.FULL,
                theme = DiLink51ClusterLayout.Theme.MAP,
                block = block,
            )
        } finally { org.robolectric.shadows.ShadowBuild.setFingerprint(original) }
    }

    private fun withPresentation(name: String = DiLink51ClusterLayout.BASE,
        theme: DiLink51ClusterLayout.Theme = DiLink51ClusterLayout.Theme.SCENARIO,
        night: Boolean = DiLink51ClusterLayout.dark(context, theme),
        block: (ClusterMapPresentation) -> Unit) {
        val id = display(name, "w1920dp-h720dp-mdpi")
        val presentation = ClusterMapPresentation(
            context = context,
            display = manager.getDisplay(id),
            theme = theme,
            onSurface = {},
            initialNight = night,
        )
        try { presentation.create(); block(presentation) }
        finally { presentation.dismiss(); ShadowDisplayManager.removeDisplay(id) }
    }

    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(DisplayManager::class.java)

    private fun display(name: String, spec: String = "w960dp-h360dp"): Int {
        // Display.TYPE_VIRTUAL (5) is hidden from the public Android SDK.
        val id = ShadowDisplayManager.addDisplay(spec, 5)
        shadowOf(manager.getDisplay(id)).apply {
            setName(name)
            setFlags(Display.FLAG_PRESENTATION)
        }
        return id
    }

    @Test fun legacyFirmwareKeepsOriginalBaseDisplayPreference() {
        val base = display("fission_bg_XDJAScreenProjection")
        val shared = display("shared_fission_bg_XDJAScreenProjection_0")
        try {
            assertEquals(base, ClusterMapPresentation.findDisplay(context)?.displayId)
        } finally {
            ShadowDisplayManager.removeDisplay(shared)
            ShadowDisplayManager.removeDisplay(base)
        }
    }

    @Test fun baseDisplayStillWorksWhenNoSharedLayerExists() {
        val base = display("fission_bg_XDJAScreenProjection")
        try {
            assertEquals(base, ClusterMapPresentation.findDisplay(context)?.displayId)
        } finally {
            ShadowDisplayManager.removeDisplay(base)
        }
    }

    /**
     * B561：只有一块副屏时，即使它匹配不上任何比亚迪指纹，也**采用**它。
     *
     * 这条与上游"认不出指纹就宁可不画"的取向**故意不同** ——
     * B561 的仪表是 HDMI 外接屏，屏名/尺寸对不上 `DiLink4ClusterDisplay.matches()`，
     * 实车日志里它就是 `display=1 name="HDMI 屏幕"`。若坚持指纹匹配，
     * 实车表现就是"连接成功但仪表无画面"。
     *
     * ★ 无歧义是前提：**只有一块**副屏才自动采用。两块以上仍要求用户显式选择，
     *   否则会把 passenger display 之类误当仪表屏（见下一条）。
     */
    @Test fun aSingleSecondaryDisplayIsUsedOnB561EvenWithoutAFingerprint() {
        val other = display("HDMI 屏幕")
        try {
            assertEquals(other, ClusterMapPresentation.findDisplay(context)?.displayId)
        } finally {
            ShadowDisplayManager.removeDisplay(other)
        }
    }

    /**
     * ★ 但**多块**副屏且用户没选过时，绝不"挑最大的那块"硬上。
     *
     * 认错屏会把导航画到 passenger display 上，比不画更糟 —— 宁缺勿画。
     * 这条守住 B561 判据的另一半：歧义时保持沉默。
     */
    @Test fun ambiguousSecondaryDisplaysAreNotGuessedWithoutAnExplicitChoice() {
        val a = display("Passenger display")
        val b = display("HDMI 屏幕", "w1920dp-h720dp-mdpi")
        try {
            assertNull(ClusterMapPresentation.findDisplay(context))
        } finally {
            ShadowDisplayManager.removeDisplay(a)
            ShadowDisplayManager.removeDisplay(b)
        }
    }
    @Test fun dilink4MeasuredProjectionIsSelected() {
        val id = display(DiLink4ClusterDisplay.NAME, "w1920dp-h720dp-mdpi")
        try {
            assertEquals(id, ClusterMapPresentation.findDisplay(context)?.displayId)
        } finally {
            ShadowDisplayManager.removeDisplay(id)
        }
    }

    @Test fun dilink3SmallerProjectionDisplayIsSelected() {
        val id = display(DiLink4ClusterDisplay.NAME, "w1280dp-h480dp-mdpi")
        try {
            assertEquals(id, ClusterMapPresentation.findDisplay(context)?.displayId)
        } finally {
            ShadowDisplayManager.removeDisplay(id)
        }
    }

    @Test fun dilink4WrongGeometryIsRejected() {
        val id = display(DiLink4ClusterDisplay.NAME, "w1280dp-h720dp-mdpi")
        try {
            assertNull(ClusterMapPresentation.findDisplay(context))
        } finally {
            ShadowDisplayManager.removeDisplay(id)
        }
    }

    @Test fun dilink4RequiresPresentationFlag() {
        val id = display(DiLink4ClusterDisplay.NAME, "w1920dp-h720dp-mdpi")
        shadowOf(manager.getDisplay(id)).setFlags(0)
        try {
            assertNull(ClusterMapPresentation.findDisplay(context))
        } finally {
            ShadowDisplayManager.removeDisplay(id)
        }
    }

    @Test fun existingDilink5DisplayKeepsPriority() {
        val legacy = display(DiLink4ClusterDisplay.NAME, "w1920dp-h720dp-mdpi")
        val current = display(DiLink51ClusterLayout.BASE)
        try {
            assertEquals(current, ClusterMapPresentation.findDisplay(context)?.displayId)
        } finally {
            ShadowDisplayManager.removeDisplay(current)
            ShadowDisplayManager.removeDisplay(legacy)
        }
    }

    @Test fun disabledClusterStillReportsDisplayWithoutNavigationReceiver() {
        val enabled = AirPlayPersistence.loadClusterMapEnabled(context)
        AirPlayPersistence.saveClusterMapEnabled(context, false)
        val id = display(DiLink4ClusterDisplay.NAME, "w1920dp-h720dp-mdpi")
        try {
            assertFalse(com.shilapi.xcertplay.hud.BydOutputSettings.navigationAvailable(context))
            val report = ClusterMapPresentation.diagnosticReport(context)
            assertTrue(report.contains("clusterEnabled=false"))
            assertTrue(report.contains("navigationReceiverAvailable=false"))
            assertTrue(report.contains("1920x720"))
            assertTrue(report.contains("selectedCluster=$id:${DiLink4ClusterDisplay.NAME}"))
        } finally {
            ShadowDisplayManager.removeDisplay(id)
            AirPlayPersistence.saveClusterMapEnabled(context, enabled)
        }
    }

    @Test fun diagnosticsDistinguishVisibleDisplayFromEligiblePresentation() {
        val id = display(DiLink4ClusterDisplay.NAME, "w1920dp-h720dp-mdpi")
        shadowOf(manager.getDisplay(id)).setFlags(0)
        try {
            val report = ClusterMapPresentation.diagnosticReport(context)
            assertTrue(report.contains("$id:${DiLink4ClusterDisplay.NAME}"))
            assertTrue(report.contains("selectedCluster=none"))
        } finally {
            ShadowDisplayManager.removeDisplay(id)
        }
    }

    @Test fun legacyHolderSurfaceIsReportedAsPhysicalOutputWithoutTakingItsOwnership() {
        val id = display("fission_bg_XDJAScreenProjection")
        val reported = mutableListOf<android.view.Surface?>()
        val presentation = ClusterMapPresentation(context, manager.getDisplay(id), onSurface = { reported += it })
        val surface = org.mockito.Mockito.mock(android.view.Surface::class.java)
        val holder = org.mockito.Mockito.mock(android.view.SurfaceHolder::class.java)
        org.mockito.Mockito.`when`(holder.surface).thenReturn(surface)
        fun find(view: android.view.View): android.view.SurfaceView? {
            if (view is android.view.SurfaceView) return view
            if (view is android.view.ViewGroup) for (child in 0 until view.childCount) {
                find(view.getChildAt(child))?.let { return it }
            }
            return null
        }
        try {
            presentation.create()
            val view = find(presentation.window!!.decorView) ?: error("Legacy route must use SurfaceView")
            val callbacks = (view.holder as org.robolectric.shadows.ShadowSurfaceView.FakeSurfaceHolder).callbacks
            callbacks.forEach { it.surfaceCreated(holder) }
            assertSame(surface, presentation.outputSurface)
            assertSame(surface, reported.last())
            presentation.setMapVisible(false)
            assertFalse(presentation.mapVisible)
            presentation.setMapVisible(true)
            assertTrue(presentation.mapVisible)
            callbacks.forEach { it.surfaceDestroyed(holder) }
            assertNull(presentation.outputSurface)
            assertNull(reported.last())
            org.mockito.Mockito.verify(surface, org.mockito.Mockito.never()).release()
        } finally {
            presentation.dismiss()
            ShadowDisplayManager.removeDisplay(id)
        }
    }

}
