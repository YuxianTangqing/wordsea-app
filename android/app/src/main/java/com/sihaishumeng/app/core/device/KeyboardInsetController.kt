package com.sihaishumeng.app.core.device

import android.view.View
import android.webkit.WebView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * edge-to-edge insets + 键盘「盖页」第三态（M-APP14e 定案；M249 键盘高度通道；
 * 批②自壳迁出）。
 *
 * 键盘前两态皆被真机否决：resize（垫高底 padding）=视口骤缩 40%+，桌面布局站点
 * 〔M188f 恒桌面 UA〕全页 reflow「挤到一块」（M-APP14c）；pan（整页
 * translationY 上移净键盘高）=视口虽恒定但「整个页面被顶到上面」顶栏出屏
 * （M-APP14d/e 两轮复报）。本态=translationY 恒 0，键盘直接盖住 WebView 下部
 * （与原生 APP 一致），页面纹丝不动，只把焦点输入框沿网页滚动流滚到键盘上方。
 * 滚动锚用视口占比而非 CSS px：净键盘高/WebView 高在物理/CSS 两坐标系等比，
 * 绕开 devicePixelRatio 与 wide-viewport 缩放的一切坑。逐层滚所有可滚祖先
 * （站点滚动在 inner div 也有效）；insets 动画逐帧回调=幂等注入；键盘收起
 * 不回滚（用户自行滚回，与浏览器一致）。不用 isVisible(ime)（API<30 的
 * compat 推断有差异），纯数值：ime.bottom 恒含底栏区，净高法三键/手势皆准。
 *
 * M-APP16 铁律：setPadding 实参顺序 (left, top, right, bottom)——壳 1.1.6 曾把
 * 后两参写错位（三键机右侧白条+底被系统键盖，手势机全隐形），逐参对位复核。
 */
class KeyboardInsetController(
    /** 宿主须先 WindowCompat.setDecorFitsSystemWindows(window, false) 再 install */
    private val webProvider: () -> WebView?,
    private val evalJs: (String) -> Unit,
) {
    // ── M249 键盘高度通道状态：kb 变化（含归 0）时推给 web。@Volatile：insets 主
    //    线程写、桥 binder 线程读（keyboardHeight()/keyboardViewportPx()） ──
    @Volatile
    var lastKbPx = 0
        private set

    @Volatile
    var lastViewPx = 0
        private set

    fun install(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            val kb = (ime.bottom - bars.bottom).coerceAtLeast(0)
            // M249 键盘高度推送的换算分母（insets 到达时已 layout，kb>0 时 height 必>0）
            val web = webProvider()
            val webViewHeight = web?.height ?: 0
            if (kb > 0 && web != null && web.height > 0) {
                // 键盘净高占视口比例（封顶 0.6 防畸形值）；limit 下方 24px 余量
                val ratio = (kb.toDouble() / web.height).coerceIn(0.0, 0.6)
                evalJs(
                    "(function(){var e=document.activeElement;" +
                        "if(e&&(e.tagName=='INPUT'||e.tagName=='TEXTAREA'||e.isContentEditable)){" +
                        "var limit=window.innerHeight*(1-" + ratio + ")-24;" +
                        "var r=e.getBoundingClientRect();" +
                        "if(r.bottom>limit){var d=r.bottom-limit;var n=e;" +
                        "while(n&&d>0){if(n.scrollHeight>n.clientHeight+1){" +
                        "var s=Math.min(n.scrollTop,d);if(s>0){n.scrollTop-=s;d-=s;}}" +
                        "n=n.parentElement;}}}})();"
                )
            }
            // M249 键盘高度推送：变化才推（insets 动画逐帧回调，幂等）；kb 归 0 也推=键盘收起信号。
            // web 侧 keyboard-inset.ts 按 kb*innerHeight/viewPx 换算 CSS px；旧网页无
            // window.__wsKeyboard 处理器=无害 no-op（新壳+旧缓存页兼容）。
            if (kb != lastKbPx || webViewHeight != lastViewPx) {
                lastKbPx = kb
                lastViewPx = webViewHeight
                if (web != null) {
                    evalJs("window.__wsKeyboard && window.__wsKeyboard($kb, $webViewHeight);")
                }
            }
            WindowInsetsCompat.CONSUMED
        }
    }
}
