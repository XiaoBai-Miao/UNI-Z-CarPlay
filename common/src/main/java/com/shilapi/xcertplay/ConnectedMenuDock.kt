package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max

/**
 * 连接后双指下滑设置菜单的**左侧竖排目录 + 右侧详情**骨架。
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
 * 在 1920×910 上要吃掉近 300 px 高度，右侧详情被压得只剩半屏。
 * 现在全部改成左侧目录里的 [addAction] 操作项，右侧详情拿到**整列高度**。
 */
internal class ConnectedMenuDock(
    private val context: Context,
    private val dockWidthDp: Int,
    private val bodySizeSp: Float,
    private val captionSizeSp: Float,
    private val selectedColor: Int,
    private val dividerColor: Int,
    private val accentColor: Int,
    /** B561 v69: 操作项里"退出应用"这类危险操作用的红字色。 */
    private val dangerTextColor: Int,
    private val minTouchDp: Int,
    private val textFactory: (String, Float, Int, Boolean) -> TextView,
) {
    private val sections = LinkedHashMap<String, View>()
    private val rows = LinkedHashMap<String, TextView>()
    private var currentKey: String? = null

    /** 操作项的 key —— 参与 [showSection] 的高亮，但不拥有详情容器。 */
    private val actionKeys = mutableSetOf<String>()

    /** 右侧详情滚动区。 */
    private lateinit var contentScroll: ScrollView
    /** 各分区的容器，key 与目录项一致。 */
    private val containers = LinkedHashMap<String, LinearLayout>()

    /** 当前正在往哪个分区 addView —— 由 [section] 设定。 */
    private var buildingKey: String? = null

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    /**
     * 登记一个分区。[build] 里用 [add] 把控件挂进该分区。
     *
     * @param key 稳定 key
     */
    fun addSection(key: String, title: String, build: (LinearLayout) -> Unit) {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
        }
        containers[key] = column
        // 先建好分区头，再让 build往里加控件 —— 分区标题随分区一起隐藏，
        // 否则切页后会看到"标题留着、内容空了"。
        column.addView(
            textFactory(title, bodySizeSp, accentColor, true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(12) },
        )
        sections[key] = column
        buildingKey = key
        try {
            build(column)
        } finally {
            buildingKey = null
        }
        rows[key] = buildRow(key, title)
    }

    /** 往当前分区加控件。分区名与 [addSection] 的 key 对应。 */
    fun add(key: String, view: View, params: LinearLayout.LayoutParams) {
        containers[key]?.addView(view, params)
    }

    /**
     * B561 v69: 登记一个**操作项** —— 左侧目录里的一个可点条目，点一下直接执行。
     *
     * ## 为什么要这个
     *
     * 原来「保存并重连 / 退出应用 / 语言 / 手势 / 打开 app 设置」这5 个按钮
     * 常驻在右侧底部，1920×910 上要吃掉近 300 px。搬进左侧目录后
     * 右侧详情拿到整列高度，而操作本身一个不少。
     *
     * 操作项**不切页**：点击只跑 [onClick]。若它当前正处于高亮分区
     * （比如"保存并重连"点了要重连、会关掉菜单），高亮会随后被
     * [showSection] 复位。
     *
     * @param accent true = 主操作（保存并重连），用强调色 + 底色块，与普通项区分
     * @param danger true = 危险操作（退出应用），用红色字
     */
    fun addAction(
        key: String,
        title: String,
        accent: Boolean = false,
        danger: Boolean = false,
        onClick: () -> Unit,
    ) {
        require(key !in rows) { "duplicate dock row: $key" }
        actionKeys += key
        val row = buildRow(key, title)
        row.setOnClickListener { onClick() }
        when {
            accent -> {
                row.setTextColor(accentColor)
                row.gravity = Gravity.CENTER
            }
            danger -> row.setTextColor(dangerTextColor)
        }
        rows[key] = row
    }

    fun currentSection(): String? = currentKey

    /**
     * B561 v69: 更新某个操作项的标题（手势按钮循环切指头后要改文案）。
     *
     * 之所以需要这个方法而不是把 TextView 抓在手里改：目录项由 dock 自己持有，
     * 调用方不该越过 [addAction] 去动它的内部状态。
     */
    fun refreshActionLabel(key: String, title: String) {
        rows[key]?.text = title
    }

    fun showSection(key: String) {
        // 操作项没有详情容器，误传进来会让右侧全空 —— 直接忽略。
        if (key in actionKeys) return
        currentKey = key
        containers.forEach { (sectionKey, column) ->
            column.visibility = if (sectionKey == key) View.VISIBLE else View.GONE
        }
        rows.forEach { (rowKey, row) ->
            row.isSelected = rowKey == key
            // 操作项保留自己的强调色/危险色，不被选中态刷成普通白字。
            if (rowKey in actionKeys) return@forEach
            row.setTextColor(if (rowKey == key) selectedColor else Color.WHITE)
        }
    }

    private fun buildRow(key: String, title: String): TextView = textFactory(title, bodySizeSp, Color.WHITE, false).apply {
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(minTouchDp)
        setPadding(dp(16), 0, dp(12), 0)
        isClickable = true
        isFocusable = true
        // 分区项由 [showSection] 高亮；操作项覆盖成自己的动作。
        setOnClickListener { showSection(key) }
    }

    /**
     * 组装根视图：`左目录 | 分隔线 | 右详情`。
     *
     * ★ v69: **没有页脚** —— 原来那 5 个常驻按钮已改成左侧 [addAction] 操作项，
     * 详情区因此拿到整列高度（1920×910 上多出近 300 px）。
     */
    fun build(titleView: View, contentPaddingH: Int, contentPaddingV: Int): View {
        val dockColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        rows.forEach { (_, row) ->
            dockColumn.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(6) },
            )
        }

        val root = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(
            ScrollView(context).apply {
                isFillViewport = true
                addView(dockColumn, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            },
            LinearLayout.LayoutParams(dp(dockWidthDp), ViewGroup.LayoutParams.MATCH_PARENT),
        )
        root.addView(
            View(context).apply { setBackgroundColor(dividerColor) },
            LinearLayout.LayoutParams(max(1, dp(1)), ViewGroup.LayoutParams.MATCH_PARENT),
        )

        contentScroll = ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(contentPaddingH), dp(contentPaddingV), dp(contentPaddingH), dp(contentPaddingV))
            // ★ ScrollView 只能有**一个**直接子 View，所以要一个内层容器兜住全部分区
            //   （踩过：`IllegalStateException: ScrollView can host only one direct child`）。
            //   分区之间靠 visibility 切换 —— 它们是**兄弟**关系，一次性 add 进去，
            //   切页不搬View（见类注释的约束）。
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    containers.values.forEach { column ->
                        addView(
                            column,
                            ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ),
                        )
                    }
                },
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        root.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(titleView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                // ★ v69: 原来 weight=1 的详情区上面还压着一个页脚，
                //   现在没有页脚，这一列整列都给内容。
                addView(contentScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
        )
        return root
    }
}
