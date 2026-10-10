package com.shilapi.xcertplay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max

/**
 * 连接后下滑设置菜单的**左侧目录 + 右侧卡片详情**骨架。
 *
 * ## 与 [SettingsDock] 的关系
 *
 * 结构一致（目录 / 分隔线 / 右侧详情区），但**不能直接复用**：
 * [SettingsDock] 是通用控件、由 [SettingsDockLayoutTest] 守着；本类是
 * `CarPlayHostActivity` 专用的 —— 它要额外支持：
 * - 目录项既可以是**分区**（点一下切右侧详情），也可以是**操作**
 *   （点一下直接执行，见 [addAction]）
 * - 目录项只在构建期登记一次，切页靠 `visibility`（见类注释的约束）
 *
 * ## 为什么切页不搬 View
 *
 * 菜单里的控件绑定成员状态，且 `cancelSettingsEdits()` /
 * `finishSettingsMenu()` / `captureSettingsBaseline()` 依赖它们留在同一容器里。
 * 若为分页把控件"搬进"当前页，隐藏页的改动会丢、共享引用会拿到 null。
 *
 * ## ★ v69: 底部页脚彻底取消
 *
 * 原来页脚常驻 5 个按钮（保存并重连 / 退出应用 / 语言 / 手势 / 打开设置），
 * 在 1920×910 上要吃掉近300 px 高度，右侧详情被压得只剩半屏。
 * 现在全部改成左侧目录里的 [addAction] 操作项，右侧详情拿到**整列高度**。
 *
 * ## ★ v70: 左侧目录的视觉层级
 *
 * 车机是 1920×910 的**远视距**场景，用户要在开车余光里扫这排目录，所以：
 * - 选中态是**圆角底色块 + 左侧 3dp 强调色条 + 加粗**
 *   （原来只有一个 `setTextColor`，在强反光的车机屏上几乎看不出选中的是哪一项）
 * - 分区项与操作项之间有明确的**分隔线 + 小标题**，避免"保存并重连"被误认成
 *   一个可切页的分类（它不是）
 *
 * ## ★ v70: 为什么用有序条目列表而不是"边登记边 addView"
 *
 * [addSection] / [addAction] 都在 [build] **之前**调用（`build` 才是真正
 * 组装视图树的那一步），所以分组标题 / 分隔线没法插进一个还不存在的目录列。
 * 于是登记期只往 [entries] 里追加描述，[build] 时按顺序渲染 ——
 * 这样"插入位置"由登记顺序决定，与视图何时创建解耦。
 *
 * ## ★ v71: 卡片化 —— 为什么在 [build] 里后处理，而不是让调用方包卡片
 *
 * 抄 DiPlay-CN cn.2/cn.15 的换皮：右侧详情不再是"标题 + 一坨控件"，
 * 而是**大标题 + 若干张卡片**，卡片内行间有细分隔线。
 *
 * 诱惑是让 `buildSettingsMenu()` 里那550 行各自 `addToSection(...)` 的调用
 * 外面包一层卡片容器 —— 但那意味着改 550 行，且以后每加一行都要记得包，
 * 漏一处样式就断。**实测过的坑：DiPlay-CN 的 cn.7 就是这么翻车的**
 * （cn.7 装车即闪退，cn.8 回退到 cn.6 内容才恢复）。
 *
 * 所以改成**后处理**：[add] 仍旧原样收下控件，[build] 时遍历每个分区的子View，
 * 遇到打了 [CATEGORY_HEADER_TAG] 的（由 `settingsCategoryHeader()` 产出）
 * 就开一张新卡片，其余控件依次落进当前卡片，行间插 [palette.hairline] 细线。
 * 调用点一行不用改，样式也不可能漏。
 *
 * ## ★ v71: 单列而不是双列
 *
 * DiPlay-CN cn.15 用过双列，cn.2 又**回退成单列**（原话："解决 cn.15 双列过挤"）。
 * 原因是 5路分段选择器在双列里每列只有 ~200dp，标签必然换行。
 * B561 是 1920×910 宽屏但**只有 910 高**，纵向空间比他们的2560×1440 紧张得多，
 * 所以直接采纳他们的回退结论：单列 + 卡片，宽度上限从 1200 px 放到 1280 px。
 */
internal class ConnectedMenuDock(
    private val context: Context,
    private val dockWidthDp: Int,
    private val bodySizeSp: Float,
    private val captionSizeSp: Float,
    /** v71: 昼夜两套皮肤，取代 v70 那堆散装 `MENU_*` 常量。 */
    private val palette: ConnectedMenuPalette,
    private val minTouchDp: Int,
    private val textFactory: (String, Float, Int, Boolean) -> TextView,
) {
    private val sections = LinkedHashMap<String, View>()
    private val rows = LinkedHashMap<String, View>()
    /** v71: 目录项的文字视图 —— [rows] 存的是带指示条的 FrameLayout，改文案要拿这里。 */
    private val rowLabels = LinkedHashMap<String, TextView>()
    /** v71: 目录项的强调色条，只在选中时可见。 */
    private val rowIndicators = LinkedHashMap<String, View>()

    private var currentKey: String? = null

    /** 操作项的 key —— 参与 [showSection] 的高亮，但不拥有详情容器。 */
    private val actionKeys = mutableSetOf<String>()

    /** 右侧详情滚动区。 */
    private lateinit var contentScroll: ScrollView

    /** 各分区的容器，key 与目录项一致。 */
    private val containers = LinkedHashMap<String, LinearLayout>()

    /** v70: 目录列的有序条目，分区项 / 操作项 / 分隔线 / 分组标题混在一起。 */
    private sealed interface Entry {
        class RowEntry(val key: String) : Entry
        class GroupEntry(val title: String) : Entry
        object DividerEntry : Entry
    }

    private val entries = mutableListOf<Entry>()

    /** v70: 分组标题插入时，跳过与上一个标题重复的那一行。 */
    private var lastGroupTitle: String? = null

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    /**
     * dp→px 的浮点版。给圆角半径用 —— 1.5 dp 这类半像素值写成 Int 会被截成1 dp，
     * 在 2x/2.625x 屏上圆角会明显发硬（指示条、卡片色条都靠它）。
     */
    private fun dpF(value: Number): Float = value.toFloat() * context.resources.displayMetrics.density

    /** v70→v71: 目录项左侧指示条宽度（dp）。v71 改用真实子 View，不再靠 padding 挤。 */
    private val indicatorWidthDp = 3

    /** v71: 目录项圆角。 */
    private val railCornerDp = 10

    /** v71: 卡片圆角，比目录项大一档，让"卡片"这个层级看得出来。 */
    private val cardCornerDp = 16

    /**
     * v71: 分区标题标记。[build] 的卡片化靠它识别"这里要开一张新卡片"。
     *
     * 由 `CarPlayHostActivity.settingsCategoryHeader()` 打上；没打标的就当作
     * 普通行落进当前卡片（第一行没标时会自动开一张卡，避免无处安放）。
     */
    companion object {
        const val CATEGORY_HEADER_TAG = "connected_menu_category_header"

        /**
         * v72: 是否渲染分区页的大标题（hero）。默认 **false** —— 中文界面下
         * 英文大标题 + 英文副标题与卡片里的中文标签抢层级，且左侧目录已有中文分区名，
         * 属于重复信息。改回true 即恢复 v71 的样子。
         */
        const val SHOW_SECTION_HERO = false

        /**
         * ★ v77: 是否渲染**左侧目录顶部**的标题条（[buildDockHeader]）。
         *
         * 与 [SHOW_SECTION_HERO] 是两处不同的标题，v72 只关了右侧分区页的大标题，
         * 左侧目录顶上那条「CarPlay 设置 / 设置」仍留着 —— 中文界面下同样是
         * 英文/多余的一行，且把第一项（连接）往下挤。
         *
         * 改回 true 即恢复 v71 的样子，[buildDockHeader] 与调用侧都不用动。
         */
        const val SHOW_DOCK_HEADER = false

        /**
         * ★ v78: 是否渲染左侧目录里的**分组小标题**（Display / Navigation and
         * vehicle / Audio and calls / Quick settings / Actions）。
         *
         * 默认 **false** —— 这几个key 只在 base `values/strings.xml` 里定义，
         * 所有中文 locale 都没有对应条目，所以中文界面下它们**显示的就是英文原文**。
         * 每个分区名本身（显示 / 导航 / 音频…）已经说明了归属，分组标题纯属重复，
         * 还占掉两三行高度。改成 true 即恢复。
         */
        const val SHOW_RAIL_GROUP_LABELS = false
    }

    /**
     * 登记一个分区。[build] 里用 [add] 把控件挂进该分区。
     *
     * @param key 稳定 key
     * @param group 分组标题 —— 目录会按它插入小标题与分隔线；
     *   传同一个名字两次只插一次（相邻同组不重复插），
     *   第一组传 null 省掉一个多余的小标题。
     * @param subtitle v71: 页大标题下的副标题（一句话说明这一页管什么）。
     *   null 则不渲染副标题。
     */
    fun addSection(
        key: String,
        title: String,
        group: String? = null,
        subtitle: String? = null,
        build: (LinearLayout) -> Unit,
    ) {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        containers[key] = column
        // v71: 分区头升级成 **hero**（大标题 + 可选副标题）——
        //   它标的是"你在哪一页"，比 v70 那个 26sp 浮在内容上的字更有锚点感。
        //   ★ 不再顺手addView 进 column：那550 行 addToSection 会排在它**下面**，
        //   而 hero 必须在所有内容**之上**，所以 hero 由 [build] 统一插到index 0。
        sectionHeroTitles[key] = title
        sectionSubtitles[key] = subtitle
        sections[key] = column
        buildingKey = key
        try {
            build(column)
        } finally {
            buildingKey = null
        }
        if (group != null && group != lastGroupTitle) {
            entries += Entry.GroupEntry(group)
            lastGroupTitle = group
        }
        entries += Entry.RowEntry(key)
        rows[key] = buildRow(key, title)
    }

    private var buildingKey: String? = null
    private val sectionHeroTitles = LinkedHashMap<String, String>()
    private val sectionSubtitles = LinkedHashMap<String, String?>()

    /** 往当前分区加控件。分区名与 [addSection] 的 key 对应。 */
    fun add(key: String, view: View, params: LinearLayout.LayoutParams) {
        containers[key]?.addView(view, params)
    }

    /**
     * B561 v69: 登记一个**操作项** —— 左侧目录里的一个可点条目，点一下直接执行。
     *
     * @param accent true = 主操作（保存并重连），用强调色 + 底色块，与普通项区分
     * @param danger true = 危险操作（退出应用），用红色字
     * @param group 分组标题 —— 在操作项之前插一条分隔线，
     *   让"保存并重连"这类动作不会被误认成可切页的分类。
     */
    fun addAction(
        key: String,
        title: String,
        accent: Boolean = false,
        danger: Boolean = false,
        group: String? = null,
        onClick: () -> Unit,
    ) {
        require(key !in rows) { "duplicate dock row: $key" }
        actionKeys += key
        if (group != null) {
            entries += Entry.DividerEntry
            entries += Entry.GroupEntry(group)
        }
        // ★ v71: 操作项也要先经 [buildRow] 建出目录项 ——
        //   v70 里操作项是在 addAction 内部现建 TextView 的，重写成统一的
        //   FrameLayout(指示条 + 文字) 后这一步漏掉了，
        //   于是下面 rowLabels.getValue(key) 抛 NoSuchElementException
        //   （连带 28 个 CarPlayHostSettingsTest 全挂，栈顶就是这一行）。
        rows[key] = buildRow(key, title)
        entries += Entry.RowEntry(key)
        val label = rowLabels.getValue(key)
        when {
            accent -> {
                label.setTextColor(palette.accent)
                label.gravity = Gravity.CENTER
                label.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                // v71: 主操作给一层**常驻**的淡强调底 —— 它不是当前页，
                // 但长得像按钮（居中 + 描边），用户一眼知道这是"按下去会做事"的。
                label.background = GradientDrawable().apply {
                    cornerRadius = dpF(railCornerDp)
                    setColor(blendOver(palette.rail, palette.accent, 34))
                    setStroke(max(1, dp(1)), blendOver(palette.rail, palette.accent, 150))
                }
            }
            danger -> label.setTextColor(palette.danger)
        }
        // 覆盖 [buildRow] 里装的分区切换 —— 操作项只跑动作，不切页。
        // 文字与外层都要挂：见 [buildRow] 的注释（测试按文案找 TextView 点击）。
        rows[key]?.setOnClickListener { onClick() }
        rowLabels[key]?.setOnClickListener { onClick() }
    }

    fun currentSection(): String? = currentKey

    /**
     * B561 v69: 更新某个操作项的标题（手势按钮循环切指头后要改文案）。
     *
     * 之所以需要这个方法而不是把 TextView 抓在手里改：目录项由dock 自己持有，
     * 调用方不该越过 [addAction] 去动它的内部状态。
     */
    fun refreshActionLabel(key: String, title: String) {
        rowLabels[key]?.text = title
    }

    fun showSection(key: String) {
        // 操作项没有详情容器，误传进来会让右侧全空 —— 直接忽略。
        if (key in actionKeys) return
        currentKey = key
        containers.forEach { (sectionKey, column) ->
            column.visibility = if (sectionKey == key) View.VISIBLE else View.GONE
        }
        rows.forEach { (rowKey, row) ->
            val selected = rowKey == key
            row.isSelected = selected
            rowIndicators[rowKey]?.visibility = if (selected) View.VISIBLE else View.INVISIBLE
            // 操作项保留自己的强调色/危险色/常驻底色，不被选中态刷掉。
            if (rowKey in actionKeys) return@forEach
            rowLabels[rowKey]?.apply {
                setTextColor(if (selected) palette.accent else palette.railIdleText)
                typeface = if (selected) {
                    Typeface.create("sans-serif-medium", Typeface.NORMAL)
                } else {
                    Typeface.create("sans-serif", Typeface.NORMAL)
                }
            }
            row.setBackground(if (selected) selectedRowBackground() else null)
        }
    }

    /** v71: 选中态的圆角底色块 —— 强调色的低透明度版本，深色底上不刺眼。 */
    private fun selectedRowBackground(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dpF(railCornerDp)
        setColor(blendOver(palette.rail, palette.accent, 40))
        setStroke(max(1, dp(1)), blendOver(palette.rail, palette.accent, 110))
    }

    /**
     * v71: 目录项 —— 一个 FrameLayout，内含左侧 3dp 强调色条 + 文字。
     *
     * v70 是靠 `setPadding(16 + 3dp, 0, 12, 0)` 伪造指示条的空间，
     * 代价是**选中前后 padding 不同**，字体渲染宽度也跟着变，切换时会跳。
     * 改成真实子 View 后，指示条只是显隐，文字位置永远不动。
     *
     * ★ 点击监听必须挂在**文字**上，不能只挂外层 FrameLayout。
     *   车机上整行都是热区，挂 FrameLayout 对真机没差别；但测试是通过
     *   `filterIsInstance<TextView>()` 按文案找到目录项再 `performClick()` 的
     *   （CarPlayHostSettingsTest.fullSettingsButton / gestureButton），
     *   监听只在父容器上时那些点击会静默落空 ——
     *   表现为 savingPersistsSettingsAndRestartsOnce 期望 4 指却读到 3 指。
     *   两处都挂，父容器负责热区与波纹，文字负责可编程点击。
     */
    private fun buildRow(key: String, title: String): View {
        val onRowClick = { showSection(key) }
        val label = textFactory(title, bodySizeSp, palette.railIdleText, false).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(10), 0)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            isClickable = true
            isFocusable = true
            setOnClickListener { showSection(key) }
        }
        val indicator = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dpF(1.5f)
                setColor(palette.accent)
            }
            visibility = View.INVISIBLE
        }
        val row = FrameLayout(context).apply {
            minimumHeight = dp(minTouchDp)
            isClickable = true
            isFocusable = true
            // 波纹反馈：v71 加的。此前目录项点下去完全没有触觉以外的反馈，
            // 而它是**唯一**的导航控件，反馈缺失会让人怀疑没点到。
            background = RippleDrawable(
                ColorStateList.valueOf(palette.accentTrack),
                null,
                null,
            )
            setOnClickListener { onRowClick() }
            addView(
                indicator,
                FrameLayout.LayoutParams(dp(indicatorWidthDp), dp(16), Gravity.START or Gravity.CENTER_VERTICAL)
                    .apply { leftMargin = dp(6) },
            )
            addView(label, FrameLayout.LayoutParams(-1, -1))
        }
        rowLabels[key] = label
        rowIndicators[key] = indicator
        return row
    }

    /** v71: 分组小标题。不可点、不进 [rows] —— 它不是目录项。 */
    private fun groupLabelView(title: String): TextView =
        textFactory(title, captionSizeSp, palette.secondaryText, false).apply {
            isClickable = false
            isFocusable = false
            letterSpacing = 0.08f
            setPadding(dp(16), 0, dp(12), 0)
            // 分组标题不吃点按，也不参与无障碍焦点 ——
            //   否则 D-pad / 旋钮会逐个停在这些纯装饰的标签上。
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

    /**
     * v70: 目录顶部的标题条。v71 保留，但改成与顶栏同一套底色。
     *
     * ★ v77: [SHOW_DOCK_HEADER] 默认 false —— 返回 null，调用侧不用改。
     */
    fun buildDockHeader(title: String): View? {
        if (!SHOW_DOCK_HEADER) return null
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(12), dp(10))
            addView(groupLabelView(title), LinearLayout.LayoutParams(-1, -2))
            addView(hairLine(), LinearLayout.LayoutParams(-1, max(1, dp(1))))
        }
    }

    private fun hairLine(): View = View(context).apply {
        setBackgroundColor(palette.hairline)
        layoutParams = LinearLayout.LayoutParams(-1, max(1, dp(1)))
    }

    /**
     * v71: 页大标题（hero）。**v72 起整块去掉。**
     *
     * 为什么砍：双语混排时，右上角一个 22sp 加粗英文大标题 + 下面 13sp 英文副标题，
     * 与卡片内中文标签并列，视觉上像"两套语言抢主导" —— 中文在 1920×910 的
     * 车机上才是主要使用语言，英文大标题反而把层级拉乱。左侧目录已经有中文分区名
     * 可以定位，重复一遍英文没有信息增益。
     *
     * 保留 [heroView] 这个函数而不是整段删掉，是为了 `cardifySection` 里那处
     * `sectionHeroTitles` / `sectionSubtitles` 的读取路径不用动 —— 那些
     * `addSection(subtitle = ...)` 调用点也一并留着，加回时只要翻转一个开关。
     *
     * 语义没变：[addSection] 的 `title` 仍是**左侧目录**那一条的文字，
     * [subtitle] 现在没有渲染方，仅作注释性的文档留存。
     */
    private fun heroView(title: String, subtitle: String?): View? {
        if (!SHOW_SECTION_HERO) return null
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        column.addView(
            textFactory(title, 22f, palette.primaryText, true),
            LinearLayout.LayoutParams(-1, -2),
        )
        if (!subtitle.isNullOrEmpty()) {
            column.addView(
                textFactory(subtitle, 13f, palette.secondaryText, false),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) },
            )
        }
        return column
    }

    /**
     * v71: 卡片 —— 上下微渐变 + 1px 描边 + 16dp 圆角。
     *
     * 渐变幅度刻意很小（两个色差不到 5），只是让大面积卡片在车机强反光下
     * 不至于"死板的一块"。真给足渐变会显得花。
     */
    private fun cardView(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            colors = intArrayOf(palette.surfaceTop, palette.surfaceBottom)
            setStroke(max(1, dp(1)), palette.stroke)
            cornerRadius = dpF(cardCornerDp)
        }
        clipToOutline = true
        setPadding(dp(18), dp(6), dp(18), dp(6))
    }

    /** v71: 卡片标题 —— 文字 + 左侧 4dp 强调色圆角条。 */
    private fun cardTitleView(title: String): View {
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(10))
        }
        head.addView(
            View(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dpF(2)
                    setColor(palette.accent)
                }
            },
            LinearLayout.LayoutParams(dp(4), dp(16)).apply { marginEnd = dp(10) },
        )
        head.addView(
            textFactory(title, 17f, palette.primaryText, true),
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        return head
    }

    /**
     * v71: 把一个分区的子 View **就地重排**成「hero + 若干卡片」。
     *
     * ## 为什么要"就地"而不是重建
     *
     * 菜单里所有控件都绑着成员状态与 lambda 回调，重建就得把控件从旧父节点
     * 摘下来再入列 —— DiPlay-CN 的 cn.10 就死在这儿：
     * `IllegalStateException: The specified child already has a parent`。
     * 这里用 `removeViewAt` + `addView` 成对出现，且**在同一个 ViewGroup 上**
     * 完成移除与插入，因此不存在"仍有父节点"的时刻。
     *
     * ## 分组规则
     *
     * - 打了 [CATEGORY_HEADER_TAG] 的 → 开一张新卡片，它自己当卡片标题
     * - 其余控件 → 落进当前卡片；卡片内每个新控件前插一条 [palette.hairline]
     * - 分区第一个控件若没打标 → 自动开一张卡（否则它无处安放）
     * - 打标的连续两个标题之间没有控件 → 空卡不渲染
     */
    private fun cardifySection(key: String) {
        val column = containers[key] ?: return
        if (column.childCount == 0) return

        // 先整体摘出来：避免边遍历边插入导致下标错乱。
        val original = (0 until column.childCount).map { column.getChildAt(it) }
        original.forEach { column.removeView(it) }

        // v72: hero 大标题默认不渲染（见heroView 注释）。
        //   想恢复只要把 SHOW_SECTION_HERO 改回 true，调用侧一行不用动。
        heroView(
            sectionHeroTitles[key].orEmpty(),
            sectionSubtitles[key],
        )?.let { hero ->
            column.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        }

        var card: LinearLayout? = null
        var cardHasBody = false
        // v71: 建卡过程中攒下的"只有标题没有正文"的空卡，最后统一丢掉。
        //   用列表记而不是边建边判断，是因为空卡可能出现在中间（下一个也是标题），
        //   一次性清理比逐个回溯简单，也不会漏。
        val emptyCards = mutableListOf<LinearLayout>()
        for (child in original) {
            val isHeader = child.tag == CATEGORY_HEADER_TAG
            if (isHeader) {
                // 上一张卡还没等到正文就来了新标题 —— 它是空的，记下来稍后删。
                if (card != null && !cardHasBody) emptyCards += card
                val next = cardView()
                next.addView(
                    cardTitleView((child as? TextView)?.text?.toString().orEmpty()),
                    LinearLayout.LayoutParams(-1, -2),
                )
                column.addView(next, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
                card = next
                cardHasBody = false
                continue
            }
            if (card == null) {
                val next = cardView()
                column.addView(next, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
                card = next
                cardHasBody = false
            }
            if (cardHasBody) card.addView(hairLine())
            val params = child.layoutParams
            val lp = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(10)
                bottomMargin = dp(10)
                // 保留调用方给的左右缩进（有的行自己带 indent），只吃原来的上下 margin ——
                // 卡片内部由 cardView 的 padding 与这里的统一间距负责，原 margin 会打架。
                leftMargin = (params as? LinearLayout.LayoutParams)?.leftMargin ?: 0
                rightMargin = (params as? LinearLayout.LayoutParams)?.rightMargin ?: 0
            }
            card.addView(child, lp)
            cardHasBody = true
        }
        // 收尾：分区末尾若还剩一张没等到正文的空卡，同样丢掉。
        if (card != null && !cardHasBody) emptyCards += card
        emptyCards.forEach { column.removeView(it) }
    }

    /**
     * 组装根视图：`左目录 | 分隔线 | 右详情`。
     *
     * v71 起右侧详情是 **hero + 卡片** 的单列结构（见 [cardifySection]）。
     */
    fun build(titleView: View, contentPaddingH: Int, contentPaddingV: Int, dockHeader: View? = null): View {
        containers.keys.toList().forEach(::cardifySection)

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // ★ 不再 CENTER_VERTICAL —— 加了分组与顶部标题后目录可能比屏幕还高，
            //   居中会让**顶部**那几项跑到屏幕外看不见。改成从顶部开始排。
            gravity = Gravity.TOP
            setBackgroundColor(palette.rail)
        }
        if (dockHeader != null) column.addView(dockHeader, LinearLayout.LayoutParams(-1, -2))
        entries.forEach { entry ->
            when (entry) {
                is Entry.RowEntry -> column.addView(
                    rows.getValue(entry.key),
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) },
                )
                is Entry.GroupEntry -> if (SHOW_RAIL_GROUP_LABELS) {
                    // v78: 默认不渲染（见 SHOW_RAIL_GROUP_LABELS）。
                    // 分组之间原本靠这个标题 +上下 margin 拉开；标题去掉后用
                    // topMargin 单独撑开间距，避免分区之间糊成一片。
                    column.addView(
                        groupLabelView(entry.title),
                        LinearLayout.LayoutParams(-1, -2).apply {
                            topMargin = dp(10)
                            bottomMargin = dp(2)
                        },
                    )
                } else {
                    column.addView(
                        View(context),
                        LinearLayout.LayoutParams(-1, dp(12)),
                    )
                }
                Entry.DividerEntry -> column.addView(
                    hairLine(),
                    LinearLayout.LayoutParams(-1, max(1, dp(1))).apply {
                        topMargin = dp(10)
                        bottomMargin = dp(10)
                    },
                )
            }
        }

        val root = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(
            ScrollView(context).apply {
                isFillViewport = true
                // 目录现在有 11 项 + 4 个分组标题，必然可能超长；
                // 关掉系统滚动条，否则它那条 3dp 高亮竖条会和我们的分隔线叠在一起。
                isVerticalScrollBarEnabled = false
                addView(column, LinearLayout.LayoutParams(-1, -2))
            },
            LinearLayout.LayoutParams(dp(dockWidthDp), -1),
        )
        root.addView(
            View(context).apply { setBackgroundColor(palette.stroke) },
            LinearLayout.LayoutParams(max(1, dp(1)), -1),
        )

        contentScroll = ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
            setBackgroundColor(palette.background)
            setPadding(dp(contentPaddingH), dp(contentPaddingV), dp(contentPaddingH), dp(contentPaddingV))
            // ★ ScrollView 只能有**一个**直接子 View，所以要一个内层容器兜住全部分区
            //   （踩过：`IllegalStateException: ScrollView can host only one direct child`）。
            //   分区之间靠 visibility 切换 —— 它们是**兄弟**关系，一次性 add 进去，
            //   切页不搬View（见类注释的约束）。
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    containers.values.forEach { column ->
                        addView(column, LinearLayout.LayoutParams(-1, -2))
                    }
                },
                ViewGroup.LayoutParams(-1, -2),
            )
        }
        root.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(0, 0, 0, dp(10))
                        addView(titleView, LinearLayout.LayoutParams(-1, -2))
                        addView(
                            View(context).apply { setBackgroundColor(palette.hairline) },
                            LinearLayout.LayoutParams(-1, max(1, dp(1))),
                        )
                    },
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) },
                )
                addView(contentScroll, LinearLayout.LayoutParams(-1, 0, 1f))
            },
            LinearLayout.LayoutParams(0, -1, 1f),
        )
        return root
    }
}