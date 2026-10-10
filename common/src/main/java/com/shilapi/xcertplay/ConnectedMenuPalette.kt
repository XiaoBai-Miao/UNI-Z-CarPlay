package com.shilapi.xcertplay

import android.graphics.Color

/**
 * 双指下滑菜单的**昼夜两套皮肤**。
 *
 * ## 为什么单独一个类
 *
 * 原来菜单的所有颜色都是 [CarPlayHostActivity] 里的 `val MENU_ACCENT = Color.rgb(...)`
 * 这类散装常量，共二十几个，且**只有一套**——夜间和白天长得一模一样。
 * 夜间车机本来就是暗环境，菜单却还铺着纯黑底+ 亮绿强调色，
 * 整个面板比CarPlay 画面本身还"亮"，反而抢了出图的注意力。
 *
 * 抄 [DiPlayPalette] 的 overlay 半边（那是 DiPlay-CN 换皮时定的，
 * 见 docs/CN_OPTIMIZATIONS.md 0.2.15-cn.15「下滑菜单按设计稿整套换皮」）。
 *
 * ## 与 [com.shilapi.xcertplay.settings.SettingsTheme] 的关系
 *
 * 两者刻意**并存**：
 * - [SettingsTheme] 是 enum，给通用控件（[SettingsWidgets]）用，
 *   它同时服务启动页设置页（isOverlay=false）和菜单（isOverlay=true）；
 * - 本类是 data class，只服务菜单，且多了卡片渐变/描边/顶栏/底栏这些
 *   只有菜单才有的语义位。
 *
 * 不合并的原因：把菜单专属的十几项塞进 SettingsTheme 会污染启动页设置页，
 * 而启动页那套皮肤是跟着 CarPlay 外观走的，不该被菜单的换皮带着变。
 *
 * ## 颜色取值
 *
 * 深色沿用 DiPlay-CN 的夜间蓝（不是纯黑 —— 纯黑面板在车机强反光下边界不清）；
 * 浅色是配套的反相版本。两套的 `overlaySegmentOn` 故意不同：
 * 深色用饱和蓝 `#1C75E5` 压在深蓝底上，浅色用 `#2469C8` 压在白底上，
 * 同一个蓝不能通吃 —— 否则白天那档会淡得看不出选中。
 */
internal data class ConnectedMenuPalette(
    /** 菜单整体底色。 */
    val background: Int,
    /** 左侧目录面板底色，比 [background] 略深/略浅，让目录与详情分层。 */
    val rail: Int,
    /** 顶栏底色。 */
    val topbar: Int,
    /** 卡片渐变上端。 */
    val surfaceTop: Int,
    /** 卡片渐变下端。 */
    val surfaceBottom: Int,
    /** 卡片描边。 */
    val stroke: Int,
    /** 行间细分隔线，比 [stroke] 更弱。 */
    val hairline: Int,
    /** 输入框/胶囊容器的底色。 */
    val field: Int,
    /** 输入框/胶囊容器的描边。 */
    val fieldStroke: Int,
    /** 胶囊选中态的实心底色。 */
    val segmentOn: Int,
    /** 主文字。 */
    val primaryText: Int,
    /** 次级文字（说明、提示）。 */
    val secondaryText: Int,
    /** 强调色：卡片标题色条、选中目录项、滑条、数值。 */
    val accent: Int,
    /** 强调色上的文字（渐变主按钮）。 */
    val onAccent: Int,
    /** 波纹色。 */
    val accentTrack: Int,
    /** 开关关闭态的轨道色。 */
    val trackOff: Int,
    /** 危险操作（退出应用）。 */
    val danger: Int,
    /** 目录项未选中时的文字色 —— 与 [primaryText] 分开，深色下压暗一档做视觉降权。 */
    val railIdleText: Int,
) {
    companion object {
        fun of(night: Boolean): ConnectedMenuPalette = if (night) DARK else LIGHT

        /**
         * 夜间。注意这**不是纯黑**：DiPlay-CN 试过 #000，在车机强反光屏上
         * 面板边界会消失，用户看不出菜单是一层浮在画面上的东西。
         * #0B1625 保留了足够蓝调，边界清楚又不刺眼。
         */
        val DARK = ConnectedMenuPalette(
            background = Color.rgb(11, 22, 37),
            rail = Color.rgb(14, 26, 41),
            topbar = Color.rgb(17, 29, 43),
            surfaceTop = Color.rgb(23, 38, 58),
            surfaceBottom = Color.rgb(19, 31, 46),
            stroke = Color.rgb(41, 59, 81),
            hairline = Color.rgb(43, 58, 76),
            field = Color.rgb(16, 27, 43),
            fieldStroke = Color.rgb(58, 77, 101),
            segmentOn = Color.rgb(28, 117, 229),
            primaryText = Color.rgb(240, 246, 255),
            secondaryText = Color.rgb(144, 163, 188),
            accent = Color.rgb(39, 136, 251),
            onAccent = Color.WHITE,
            accentTrack = Color.rgb(23, 74, 135),
            trackOff = Color.rgb(71, 90, 113),
            danger = Color.rgb(240, 115, 115),
            railIdleText = Color.rgb(126, 144, 166),
        )

        /** 白天。强调色偏绿，与 B561 原厂车机的绿色体系一致，避免换皮后风格断裂。 */
        val LIGHT = ConnectedMenuPalette(
            background = Color.rgb(244, 247, 250),
            rail = Color.rgb(238, 242, 247),
            topbar = Color.rgb(234, 240, 248),
            surfaceTop = Color.WHITE,
            surfaceBottom = Color.rgb(244, 247, 250),
            stroke = Color.rgb(199, 211, 226),
            hairline = Color.rgb(217, 226, 236),
            field = Color.rgb(242, 246, 251),
            fieldStroke = Color.rgb(185, 200, 218),
            segmentOn = Color.rgb(36, 105, 200),
            primaryText = Color.rgb(28, 28, 30),
            secondaryText = Color.rgb(75, 86, 101),
            accent = Color.rgb(35, 100, 58),
            onAccent = Color.WHITE,
            accentTrack = Color.rgb(35, 100, 58),
            trackOff = Color.rgb(168, 180, 190),
            danger = Color.rgb(155, 48, 48),
            railIdleText = Color.rgb(74, 86, 102),
        )
    }
}

/** 把 [color] 的 alpha 覆盖成 [alpha]（0–255），保留 RGB。 */
internal fun withAlphaOf(color: Int, alpha: Int): Int =
    Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

/**
 * 在 [base]（通常是底色）上压一层 [overlay] 的 [alpha]，得到一个半透明合成色。
 *
 * 与 [withAlphaOf] 的区别：后者输出的是**带 alpha 的色**，直接 `setColor` 给
 * GradientDrawable 时，若底图本身有颜色会叠出脏边；本函数先把两层合成成
 * 一个**不透明**色，落在任何底上都干净。
 */
internal fun blendOver(base: Int, overlay: Int, alpha: Int): Int {
    val a = alpha.coerceIn(0, 255) / 255f
    return Color.rgb(
        (Color.red(base) + (Color.red(overlay) - Color.red(base)) * a).toInt(),
        (Color.green(base) + (Color.green(overlay) - Color.green(base)) * a).toInt(),
        (Color.blue(base) + (Color.blue(overlay) - Color.blue(base)) * a).toInt(),
    )
}