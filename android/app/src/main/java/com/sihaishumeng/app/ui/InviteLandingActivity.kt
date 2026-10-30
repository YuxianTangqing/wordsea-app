package com.sihaishumeng.app.ui

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.sihaishumeng.app.MainActivity
import com.sihaishumeng.app.R
import com.sihaishumeng.app.core.api.Api
import com.sihaishumeng.app.core.api.ApiResult
import com.sihaishumeng.app.ui.common.BasePageActivity
import com.sihaishumeng.app.ui.common.StateViews
import com.sihaishumeng.app.ui.common.dp

/**
 * 批④ 邀请落地页（公开）：GET invite/check 校验 → 有效=邀请人+福利+「去注册」
 * （带 invite 参数跳 WebView 注册页——注册恒 WebView）；无效=降级普通注册。
 */
@SuppressLint("SetTextI18n")
class InviteLandingActivity : BasePageActivity() {

    companion object {
        const val EXTRA_CODE = "code"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupPage(getString(R.string.invite_title))
        val states = StateViews(this, pageRoot)
        states.loading()
        val code = intent.getStringExtra(EXTRA_CODE).orEmpty()
        Api.get("/api/v1/invite/check", mapOf("code" to code)) { r ->
            when (r) {
                is ApiResult.Ok -> {
                    val d = r.data
                    if (d != null && d.optBoolean("valid", false)) {
                        states.clear()
                        render(d.optString("name", "书友"), code)
                    } else {
                        states.empty(getString(R.string.invite_invalid))
                        renderPlain()
                    }
                }
                is ApiResult.Err -> states.error(r.message) { recreate() }
                ApiResult.Net -> states.error(getString(R.string.net_error)) { recreate() }
            }
        }
    }

    private fun render(inviter: String, code: String) {
        val c = this
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), 0)
        }
        fun line(text: String, size: Float, color: Int, bold: Boolean = false, top: Int = 0) {
            box.addView(TextView(c).apply {
                this.text = text; textSize = size; setTextColor(color)
                if (bold) typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) })
        }
        line(getString(R.string.invite_valid_fmt, inviter), 17f, getColor(R.color.ink), bold = true)
        line("🎁", 40f, getColor(R.color.brand), top = 16)
        line(getString(R.string.invite_benefit_1), 13f, getColor(R.color.inkSoft), top = 14)
        line(getString(R.string.invite_benefit_2), 13f, getColor(R.color.inkSoft), top = 6)
        line(getString(R.string.invite_benefit_3), 12f, getColor(R.color.inkSoft), top = 6)
        val btn = Button(c).apply {
            text = getString(R.string.invite_register)
            textSize = 15f; setTextColor(getColor(R.color.paper))
            background = getDrawable(R.drawable.btn_primary)
            isAllCaps = false
            setPadding(0, dp(12), 0, dp(12))
            minHeight = 0; minWidth = 0
            setOnClickListener { MainActivity.openInWebView(this@InviteLandingActivity, "/auth/register?invite=$code") }
        }
        box.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(22) })
        body().addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun renderPlain() {
        val c = this
        val btn = Button(c).apply {
            text = getString(R.string.invite_register_plain)
            textSize = 14f; setTextColor(getColor(R.color.brand))
            background = getDrawable(R.drawable.btn_outline)
            isAllCaps = false
            setPadding(dp(20), dp(9), dp(20), dp(9))
            minHeight = 0; minWidth = 0
            setOnClickListener { MainActivity.openInWebView(this@InviteLandingActivity, "/auth/register") }
        }
        val wrap = LinearLayout(c).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        wrap.addView(btn)
        body().addView(wrap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}
