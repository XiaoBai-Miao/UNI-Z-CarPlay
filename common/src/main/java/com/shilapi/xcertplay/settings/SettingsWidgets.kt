package com.shilapi.xcertplay.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.host.R

object SettingsWidgets {

    private fun TextView.applyTextStyle(context: Context, theme: SettingsTheme, bold: Boolean = false) {
        if (theme.isOverlay) {
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            includeFontPadding = false
        } else {
            typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
            setLineSpacing(theme.dp(context, 3).toFloat(), 1f)
        }
    }

    data class SwitchRowResult(
        val rowView: View,
        val switch: Switch,
    )

    data class ChoiceRowResult<T>(
        val container: View,
        val radioGroup: RadioGroup,
    )

    data class ResolutionSliderResult(
        val container: View,
        val valueTextView: TextView,
        val seekBar: SeekBar,
    )

    data class ActionRowResult(
        val rowView: View,
        val button: TextView,
    )

    fun createCategoryHeader(
        context: Context,
        title: String,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
    ): TextView = TextView(context).apply {
        text = title
        textSize = if (theme.isOverlay) 16f else 22f
        setTextColor(if (theme.isOverlay) theme.accent else theme.textPrimary)
        applyTextStyle(context, theme, bold = true)
    }

    fun createSwitchRow(
        context: Context,
        label: String,
        description: String,
        checked: Boolean,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
        contentDescription: String = label,
        enabled: Boolean = true,
        labelSizeSp: Float = 18f,
        onChanged: (Boolean) -> Unit,
    ): SwitchRowResult {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val switch = Switch(context).apply {
            isChecked = checked
            this.contentDescription = contentDescription
            isEnabled = enabled
            if (theme.isOverlay) {
                showText = false
                thumbTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(theme.accent, theme.textSecondary),
                )
                trackTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(theme.accentTrack, theme.trackOff),
                )
            } else {
                minHeight = theme.dp(context, 56)
                buttonTintList = ColorStateList.valueOf(theme.accent)
            }
            setOnCheckedChangeListener { _, isChecked -> onChanged(isChecked) }
        }

        if (theme.isOverlay) {
            val labelView = TextView(context).apply {
                text = label
                textSize = labelSizeSp
                setTextColor(theme.textSecondary)
                applyTextStyle(context, theme)
            }
            row.addView(labelView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(switch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            row.setPadding(0, theme.dp(context, 12), 0, theme.dp(context, 12))
            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            val labelView = TextView(context).apply {
                text = label
                textSize = labelSizeSp
                setTextColor(theme.textPrimary)
                applyTextStyle(context, theme, bold = true)
            }
            val descView = TextView(context).apply {
                text = description
                textSize = 14f
                setTextColor(theme.textSecondary)
                setPadding(0, theme.dp(context, 6), theme.dp(context, 16), 0)
                applyTextStyle(context, theme)
            }
            textCol.addView(labelView)
            textCol.addView(descView)
            row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(switch)
            // The whole row is the target; the switch stays out of the focus order so D-pad stops once.
            switch.isFocusable = false
            row.isFocusable = true
            row.background = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(ROW_RIPPLE), null, android.graphics.drawable.ColorDrawable(Color.WHITE))
            row.foreground = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = theme.dp(context, 12).toFloat()
                    setStroke(theme.dp(context, 3), theme.accent)
                })
            }
            row.setOnClickListener { if (switch.isEnabled) switch.toggle() }
            switch.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            row.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Switch::class.java.name
                    info.isCheckable = true
                    info.isChecked = switch.isChecked
                    info.isEnabled = switch.isEnabled
                }
            }
            clampDescription(descView)
        }

        return SwitchRowResult(row, switch)
    }

        /**
     * 一个「立刻做点什么」的行 —— 标签 + 说明 + 右侧可点按钮。
     *
     * ## 为什么不用 [createSwitchRow] 的开关
     *
     * 开关表达的是**状态**，而这一行表达的是**一次性动作**（"立即断开"）。
     * 把它做成开关会让用户分不清"打开=之后每次都断"和"打开=现在就断"，
     * 而这两者的差别正是现场调这个功能时最容易踩的坑。
     *
     * 开关型语义另有 [createSwitchRow]；一次性动作用本控件。
     *
     * @param onClick 整行与按钮都可点 —— 车机是触摸环境，行内空白多，点整行更省事。
     */
    fun createActionRow(
        context: Context,
        label: String,
        description: String,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
        contentDescription: String = label,
        onClick: () -> Unit,
    ): ActionRowResult {
        val button = TextView(context).apply {
            text = label
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(theme.accent)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            // 加宽一点：1920×910 上"立即断开蓝牙音频"这种长文案按内容宽度会显得挤。
            setPadding(theme.dp(context, 18), theme.dp(context, 10), theme.dp(context, 18), theme.dp(context, 10))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = theme.dp(context, 10).toFloat()
                setColor(withAlpha(theme.accent, 30))
                setStroke(maxOf(1, theme.dp(context, 1)), withAlpha(theme.accent, 150))
            }
            this.contentDescription = contentDescription
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, theme.dp(context, 10), 0, theme.dp(context, 10))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        val textCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val labelView = TextView(context).apply {
            text = label
            textSize = 18f
            setTextColor(if (theme.isOverlay) theme.textSecondary else theme.textPrimary)
            applyTextStyle(context, theme, bold = !theme.isOverlay)
        }
        val descView = TextView(context).apply {
            text = description
            textSize = 14f
            setTextColor(if (theme.isOverlay) theme.textSecondary else theme.textPrimary)
            applyTextStyle(context, theme)
        }
        textCol.addView(labelView)
        textCol.addView(descView)
        row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button)
        return ActionRowResult(row, button)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        android.graphics.Color.argb(alpha, android.graphics.Color.red(color), android.graphics.Color.green(color), android.graphics.Color.blue(color))

    /** Long help stays two lines until tapped; screen readers still get the full text. */    private fun clampDescription(view: TextView) {
        view.maxLines = COLLAPSED_DESCRIPTION_LINES
        view.ellipsize = android.text.TextUtils.TruncateAt.END
        view.post {
            if ((view.layout?.getEllipsisCount(view.lineCount - 1) ?: 0) == 0) return@post
            view.setOnClickListener {
                view.maxLines = Int.MAX_VALUE
                view.ellipsize = null
                view.setOnClickListener(null)
                view.isClickable = false
            }
        }
    }

    private const val COLLAPSED_DESCRIPTION_LINES = 2
    private const val ROW_RIPPLE = 0x336F9FD9

    /** v71: 胶囊的最小宽高，与 DiPlay-CN overlaySegmentPills 对齐（68×38 dp）。 */
    private const val PILL_MIN_WIDTH_DP = 68
    /**
     * ★ v77: 38 → 30 dp。
     *
     * 「Instrument-cluster source」这类选项只有两三个 pill，却占掉近 40dp 高，
     * 在1920×910 上一个quick settings 页面要塞几十项，胶囊一高整页就滚不到底。
     * 30dp 仍高于车机触摸目标常用的 24dp 下限，不会难点中。
     * 想恢复 v71 的观感：改回 38。
     */
    private const val PILL_MIN_HEIGHT_DP = 30
    /** v71: 胶囊上圆角，比外层容器小 2dp，形成"嵌在槽里"的层次。 */
    private const val PILL_CORNER_DP = 8
    private const val PILL_FIELD_CORNER_DP = 10
    /** ★ v77: 胶囊上下内边距，10 → 6 dp（与高度一起缩，避免文字被挤）。 */
    private const val PILL_VERTICAL_PADDING_DP = 6
    /** ★ v77: 槽（外层容器）内边距，3 → 2 dp。 */
    private const val PILL_FIELD_PADDING_DP = 2
    /** ★ v77: 胶囊字号，15 → 14 sp。 */
    private const val PILL_TEXT_SP = 14f

    /**
     * v71: 标签 + 横向胶囊分段控件（连接后菜单专用）。
     *
     * ## 为什么横排而不用竖排 RadioButton
     *
     * 菜单一屏要塞几十项，竖排列表让每个 3 选1 的设置独占 3 行，
     * 六页加起来高度爆炸，用户要滚动才能看完一屏。
     *
     * ## 为什么外层还要一个圆角"槽"
     *
     * 直接把胶囊平铺在卡片底上会显得散 —— 未选中的项没有边界，
     * 用户看不出"这一组是可以点的几个选项"。给一层带描边的槽把选项框住，
     * 选中态再叠一个实心块，层级就清楚了。
     *
     * ## 长选项组怎么办
     *
     * 超过 [PILL_WRAP_LIMIT] 项时整体换行（pill 自身用 WRAP_CONTENT，
     * RadioGroup 横向放不下会溢出）。这是 DiPlay-CN cn.3 的做法 ——
     * 「日夜、小屏模式、Dock、音乐缓冲等长选项组改为标签下方整行铺开」。
     */
    private fun <T> createPillChoiceRow(
        context: Context,
        label: String,
        options: List<Pair<T, String>>,
        selected: T,
        theme: SettingsTheme,
        onSelected: (T) -> Unit,
    ): ChoiceRowResult<T> {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(
            TextView(context).apply {
                text = label
                textSize = 18f
                setTextColor(theme.textSecondary)
                applyTextStyle(context, theme)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        val field = RadioGroup(context).apply {
            orientation = RadioGroup.HORIZONTAL
            // RadioGroup 本身不换行；靠下面的 flexbox 式容器做不到，
            // 所以选项多时改为按 PILL_WRAP_LIMIT 分批竖排（见 forEachIndexed 注释）。
            setPadding(
                theme.dp(context, PILL_FIELD_PADDING_DP),
                theme.dp(context, PILL_FIELD_PADDING_DP),
                theme.dp(context, PILL_FIELD_PADDING_DP),
                theme.dp(context, PILL_FIELD_PADDING_DP),
            )
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(withAlpha(theme.textPrimary, 12))
                setStroke(maxOf(1, theme.dp(context, 1)), withAlpha(theme.textPrimary, 40))
                cornerRadius = theme.dp(context, PILL_FIELD_CORNER_DP).toFloat()
            }
        }
        var selectedId = View.NO_ID
        for ((index, option) in options.withIndex()) {
            val (value, text) = option
            val pill = RadioButton(context).apply {
                id = View.generateViewId()
                this.text = text
                textSize = PILL_TEXT_SP
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                // 去掉系统圆点 —— 胶囊本身已经是选中指示了，留着会显得双份。
                setButtonDrawable(null)
                gravity = Gravity.CENTER
                includeFontPadding = false
                setPadding(
                    theme.dp(context, 14),
                    theme.dp(context, PILL_VERTICAL_PADDING_DP),
                    theme.dp(context, 14),
                    theme.dp(context, PILL_VERTICAL_PADDING_DP),
                )
                minWidth = theme.dp(context, PILL_MIN_WIDTH_DP)
                minHeight = theme.dp(context, PILL_MIN_HEIGHT_DP)
                setTextColor(
                    ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(theme.buttonText, theme.textSecondary),
                    ),
                )
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(
                        intArrayOf(android.R.attr.state_checked),
                        android.graphics.drawable.GradientDrawable().apply {
                            setColor(theme.accent)
                            cornerRadius = theme.dp(context, PILL_CORNER_DP).toFloat()
                        },
                    )
                    addState(
                        intArrayOf(),
                        android.graphics.drawable.RippleDrawable(
                            ColorStateList.valueOf(withAlpha(theme.accent, 90)),
                            android.graphics.drawable.GradientDrawable().apply {
                                setColor(android.graphics.Color.TRANSPARENT)
                                cornerRadius = theme.dp(context, PILL_CORNER_DP).toFloat()
                            },
                            null,
                        ),
                    )
                }
                tag = value
            }
            if (value == selected) selectedId = pill.id
            field.addView(
                pill,
                RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginEnd = theme.dp(context, 3)
                    bottomMargin = theme.dp(context, 3)
                },
            )
            // 选不中就跳过 check —— 一行放不下的选项后面再补一批。
            if (index == PILL_WRAP_LIMIT - 1 && options.size > PILL_WRAP_LIMIT) {
                appendOverflowPillBatch(container, field, options, index + 1, selected, theme, onSelected)
                break
            }
        }
        if (selectedId != View.NO_ID) field.check(selectedId)
        // ★ v71: 去重。RadioGroup 在 check 已选中项、check 目标项、
        //   clearCheck 再 check 同一项时会多次派发同一个 checkedId ——
        //   竖排分支靠 lastSelectedId 挡掉了，这里也必须有同样的护栏，
        //   否则"保存并重连"这类动作会被触发多次。
        //   （SettingsWidgetsTest.choicesEmitOnlyNewSelectionsAndIgnoreClearingTheSelection 守着这条。）
        var lastSelectedId = field.checkedRadioButtonId
        field.setOnCheckedChangeListener { radioGroup, checkedId ->
            if (checkedId == View.NO_ID || checkedId == lastSelectedId) return@setOnCheckedChangeListener
            val pill = radioGroup.findViewById<RadioButton>(checkedId) ?: return@setOnCheckedChangeListener
            if (!pill.isChecked) return@setOnCheckedChangeListener
            @Suppress("UNCHECKED_CAST")
            val value = pill.tag as? T ?: return@setOnCheckedChangeListener
            lastSelectedId = checkedId
            onSelected(value)
        }
        container.addView(
            field,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = theme.dp(context, 8) },
        )
        return ChoiceRowResult(container, field)
    }

    /**
     * v71: 一行放不下的选项，另起一个槽铺开。
     *
     * RadioGroup 横向不换行，且它只认自己那一个 group 的 checkedRadioButtonId ——
     * 拆成第二个 RadioGroup 之后两边的 check 状态会互相覆盖，点第一个槽里的
     * 已选项会把第二个槽的选中态清掉。所以这里给第二个槽单独装回调，
     * 并且**每次点击都强制重打勾**（见下），让两个槽的视觉状态始终一致。
     */
    private fun <T> appendOverflowPillBatch(
        parent: LinearLayout,
        primary: RadioGroup,
        options: List<Pair<T, String>>,
        fromIndex: Int,
        selected: T,
        theme: SettingsTheme,
        onSelected: (T) -> Unit,
    ) {
        val overflow = RadioGroup(parent.context).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(
                theme.dp(parent.context, 3),
                theme.dp(parent.context, 3),
                theme.dp(parent.context, 3),
                theme.dp(parent.context, 3),
            )
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(withAlpha(theme.textPrimary, 12))
                setStroke(maxOf(1, theme.dp(parent.context, 1)), withAlpha(theme.textPrimary, 40))
                cornerRadius = theme.dp(parent.context, PILL_FIELD_CORNER_DP).toFloat()
            }
        }
        var selectedId = View.NO_ID
        for ((value, text) in options.drop(fromIndex)) {
            val pill = RadioButton(parent.context).apply {
                id = View.generateViewId()
                this.text = text
                textSize = PILL_TEXT_SP
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setButtonDrawable(null)
                gravity = Gravity.CENTER
                includeFontPadding = false
                setPadding(
                    theme.dp(parent.context, 14),
                    theme.dp(parent.context, PILL_VERTICAL_PADDING_DP),
                    theme.dp(parent.context, 14),
                    theme.dp(parent.context, PILL_VERTICAL_PADDING_DP),
                )
                minWidth = theme.dp(parent.context, PILL_MIN_WIDTH_DP)
                minHeight = theme.dp(parent.context, PILL_MIN_HEIGHT_DP)
                setTextColor(
                    ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(theme.buttonText, theme.textSecondary),
                    ),
                )
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(
                        intArrayOf(android.R.attr.state_checked),
                        android.graphics.drawable.GradientDrawable().apply {
                            setColor(theme.accent)
                            cornerRadius = theme.dp(parent.context, PILL_CORNER_DP).toFloat()
                        },
                    )
                    addState(
                        intArrayOf(),
                        android.graphics.drawable.RippleDrawable(
                            ColorStateList.valueOf(withAlpha(theme.accent, 90)),
                            android.graphics.drawable.GradientDrawable().apply {
                                setColor(android.graphics.Color.TRANSPARENT)
                                cornerRadius = theme.dp(parent.context, PILL_CORNER_DP).toFloat()
                            },
                            null,
                        ),
                    )
                }
                tag = value
            }
            if (value == selected) selectedId = pill.id
            overflow.addView(
                pill,
                RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginEnd = theme.dp(parent.context, 3)
                    bottomMargin = theme.dp(parent.context, 3)
                },
            )
        }
        if (selectedId != View.NO_ID) overflow.check(selectedId)
        overflow.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == View.NO_ID) return@setOnCheckedChangeListener
            val pill = overflow.findViewById<RadioButton>(checkedId) ?: return@setOnCheckedChangeListener
            if (!pill.isChecked) return@setOnCheckedChangeListener
            @Suppress("UNCHECKED_CAST")
            val value = pill.tag as? T ?: return@setOnCheckedChangeListener
            // 另一批里的选中项要清掉 —— 否则屏幕上会同时亮着两个"当前值"。
            clearPillSelection(primary)
            onSelected(value)
        }
        parent.addView(
            overflow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = theme.dp(parent.context, 8) },
        )
    }

    /**
     * v71: 把某个 RadioGroup 里已勾中的胶囊取消勾选。
     *
     * 先摘掉监听再 [RadioGroup.clearCheck] —— 否则清勾选会被当成一次用户选择，
     * 反手回调 [onSelected] 把刚选中的值又改成旧值。这是"两个槽共享一个选中态"
     * 必然踩的坑。
     */
    private fun clearPillSelection(group: RadioGroup) {
        if (group.checkedRadioButtonId == View.NO_ID) return
        group.setOnCheckedChangeListener(null)
        group.clearCheck()
    }

    /**
     * v71: 一行最多放几个胶囊。
     *
     * 6 是折中：菜单右侧卡片可用宽度约 900 px（1280 上限 - 200 目录 - 两侧 padding），
     * 一个胶囊最小 68 dp + 3dp 间距，6 个约 430 dp，留足了余量给"5 GHz"这类长标签；
     * 再多就会挤到换行，而RadioGroup 换行要另起一批（见 [appendOverflowPillBatch]），
     * 批次越多状态越容易乱。
     */
    private const val PILL_WRAP_LIMIT = 6

    fun <T> createChoiceRow(
        context: Context,
        label: String,
        options: List<Pair<T, String>>,
        selected: T,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
        onSelected: (T) -> Unit,
    ): ChoiceRowResult<T> {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        // ★ v71: overlay（连接后菜单）走**胶囊分段控件**，其他场景保持竖排列表。
        //
        // 为什么只在 overlay 换：启动页设置页那套皮肤跟着 CarPlay 外观走，
        // 竖排 RadioButton 带单选圆点，是"设置列表"的通用语言；
        // 而菜单里一行挤着标签 + 竖排列表会非常高 —— 菜单要在一屏里塞下
        // 几十项，竖排列表让每项多占 3–4 行。
        //
        // 抄 DiPlay-CN cn.15 的 overlaySegmentPills（选中实心 + 波纹 + 中黑字重）。
        // cn.15→cn.2 他们把双列回退成单列，正是因为分段控件在双列里放不下，
        // 我们直接采纳单列结论。
        if (theme.isOverlay) {
            return createPillChoiceRow(context, label, options, selected, theme, onSelected)
        }
        val labelView = TextView(context).apply {
            text = label
            textSize = 18f
            setTextColor(if (theme.isOverlay) theme.textSecondary else theme.textPrimary)
            applyTextStyle(context, theme, bold = !theme.isOverlay)
        }
        container.addView(
            labelView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val group = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, theme.dp(context, 4), 0, 0)
        }
        var selectedId = View.NO_ID
        for ((value, text) in options) {
            val button = RadioButton(context).apply {
                id = View.generateViewId()
                this.text = text
                textSize = 17f
                setTextColor(theme.textSecondary)
                buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(theme.accent, theme.textSecondary),
                )
                tag = value
                isChecked = value == selected
            }
            if (value == selected) selectedId = button.id
            group.addView(
                button,
                RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        if (selectedId != View.NO_ID) group.check(selectedId)
        var lastSelectedId = group.checkedRadioButtonId
        group.setOnCheckedChangeListener { radioGroup, checkedId ->
            if (checkedId == View.NO_ID) {
                lastSelectedId = View.NO_ID
                return@setOnCheckedChangeListener
            }
            val button = radioGroup.findViewById<RadioButton>(checkedId) ?: return@setOnCheckedChangeListener
            // RadioGroup may notify while unchecking the old button, then repeat the new ID.
            if (!button.isChecked || checkedId == lastSelectedId) return@setOnCheckedChangeListener
            @Suppress("UNCHECKED_CAST")
            val value = button.tag as? T ?: return@setOnCheckedChangeListener
            lastSelectedId = checkedId
            onSelected(value)
        }
        container.addView(
            group,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        return ChoiceRowResult(container, group)
    }

    fun createResolutionSlider(
        context: Context,
        initialPercent: Int,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
        onPercentChanged: (Int) -> Unit,
    ): ResolutionSliderResult {
        val initial = initialPercent.coerceIn(CarPlayDisplayScale.MIN_PERCENT, CarPlayDisplayScale.MAX_PERCENT)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = TextView(context).apply {
            text = context.getString(R.string.resolution)
            textSize = 20f
            setTextColor(if (theme.isOverlay) theme.textSecondary else theme.textPrimary)
            applyTextStyle(context, theme, bold = !theme.isOverlay)
        }
        header.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val valueTextView = TextView(context).apply {
            text = "$initial%"
            textSize = 28f
            setTextColor(theme.accent)
            applyTextStyle(context, theme, bold = true)
        }
        header.addView(
            valueTextView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        container.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = theme.dp(context, 14) },
        )

        val seekBar = SeekBar(context).apply {
            max = CarPlayDisplayScale.MAX_PERCENT - CarPlayDisplayScale.MIN_PERCENT
            progress = initial - CarPlayDisplayScale.MIN_PERCENT
            splitTrack = false
            progressTintList = ColorStateList.valueOf(theme.accent)
            thumbTintList = ColorStateList.valueOf(theme.accent)
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                        val percent = (CarPlayDisplayScale.MIN_PERCENT + progress)
                            .coerceIn(CarPlayDisplayScale.MIN_PERCENT, CarPlayDisplayScale.MAX_PERCENT)
                        valueTextView.text = "$percent%"
                        onPercentChanged(percent)
                    }

                    override fun onStartTrackingTouch(sb: SeekBar) = Unit
                    override fun onStopTrackingTouch(sb: SeekBar) = Unit
                },
            )
        }
        container.addView(
            seekBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = theme.dp(context, 8) },
        )

        val range = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val minLabel = TextView(context).apply {
            text = context.getString(R.string.custom_resolution_summary, CarPlayDisplayScale.MIN_PERCENT)
            textSize = 15f
            setTextColor(theme.textSecondary)
            applyTextStyle(context, theme)
        }
        range.addView(minLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val maxLabel = TextView(context).apply {
            text = context.getString(R.string.custom_resolution_summary, CarPlayDisplayScale.MAX_PERCENT)
            textSize = 15f
            setTextColor(theme.textSecondary)
            applyTextStyle(context, theme)
        }
        range.addView(
            maxLabel,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        container.addView(
            range,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        return ResolutionSliderResult(container, valueTextView, seekBar)
    }
}
