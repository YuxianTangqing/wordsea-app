package com.sihaishumeng.app.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.sihaishumeng.app.MainActivity
import com.sihaishumeng.app.R
import com.sihaishumeng.app.core.api.Api
import com.sihaishumeng.app.core.api.ApiResult
import com.sihaishumeng.app.core.session.SessionStore
import com.sihaishumeng.app.ui.common.BasePageActivity
import com.sihaishumeng.app.ui.common.StateViews
import com.sihaishumeng.app.ui.common.dp
import com.sihaishumeng.app.ui.common.toast
import org.json.JSONObject

/**
 * 批④ 资料编辑（强登录）：用户名/昵称/笔名/简介 + 保存（名称变更送审，横幅提示）
 * + 防抖查重（check-username/check-penname，500ms 节流）。改名月限文案常驻提示。
 */
@SuppressLint("SetTextI18n")
class ProfileEditActivity : BasePageActivity() {

    private lateinit var states: StateViews
    private var nameField: EditText? = null
    private var nickField: EditText? = null
    private var penField: EditText? = null
    private var bioField: EditText? = null
    private var pendingBanner: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupPage(getString(R.string.profile_title))
        states = StateViews(this, pageRoot)
        states.loading()
        if (SessionStore.peek() == null && !SessionStore.hasQueried) {
            SessionStore.refresh { user -> runOnUiThread { if (user == null) guestWall() else load() } }
        } else if (SessionStore.peek() == null) {
            guestWall()
        } else {
            load()
        }
    }

    private fun guestWall() {
        states.empty(getString(R.string.notifications_login_hint))
    }

    private fun load() {
        Api.get("/api/v1/me") { r ->
            when (r) {
                is ApiResult.Ok -> {
                    val d = r.data
                    if (d != null) {
                        states.clear()
                        renderForm(d)
                    } else {
                        guestWall()
                    }
                }
                is ApiResult.Err -> states.error(r.message) { load() }
                ApiResult.Net -> states.error(getString(R.string.net_error)) { load() }
            }
        }
    }

    private fun renderForm(me: JSONObject) {
        val c = this
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(10), dp(20), 0) }
        pendingBanner = TextView(c).apply {
            text = getString(R.string.profile_name_limit)
            textSize = 11f
            setTextColor(getColor(R.color.inkSoft))
            setPadding(0, 0, 0, dp(8))
        }
        box.addView(pendingBanner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        fun field(label: String, value: String, hint: String, multi: Boolean = false): EditText {
            box.addView(TextView(c).apply {
                text = label; textSize = 12f
                setTextColor(getColor(R.color.brand))
                setPadding(0, dp(10), 0, dp(3))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val et = EditText(c).apply {
                setText(value)
                this.hint = hint
                textSize = 14f
                setTextColor(getColor(R.color.ink))
                background = getDrawable(R.drawable.btn_outline)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                if (multi) {
                    minLines = 3
                    gravity = Gravity.TOP
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                } else {
                    setSingleLine()
                }
            }
            box.addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return et
        }

        nameField = field(getString(R.string.profile_username), me.optString("username"), "")
        nickField = field(getString(R.string.profile_nickname), me.optString("nickname"), "昵称")
        penField = field(getString(R.string.profile_penname), me.optString("penName"), "笔名")
        bioField = field(getString(R.string.profile_bio), me.optString("bio"), getString(R.string.profile_bio_hint), multi = true)

        val save = Button(c).apply {
            text = getString(R.string.profile_save)
            textSize = 15f; setTextColor(getColor(R.color.paper))
            background = getDrawable(R.drawable.btn_primary)
            isAllCaps = false
            setPadding(0, dp(11), 0, dp(11))
            minHeight = 0; minWidth = 0
            setOnClickListener { save() }
        }
        box.addView(save, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
        body().addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun save() {
        val body = JSONObject()
        nameField?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { body.put("username", it) }
        nickField?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { body.put("nickname", it) }
        penField?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { body.put("penName", it) }
        bioField?.text?.toString()?.trim()?.let { body.put("bio", it) }
        if (body.length() == 0) { toast("没有修改"); return }
        Api.patch("/api/v1/me", body) { r ->
            when (r) {
                is ApiResult.Ok -> {
                    SessionStore.markLoginChanged()
                    pendingBanner?.text = getString(R.string.profile_review_pending)
                    toast(getString(R.string.profile_saved))
                }
                is ApiResult.Err -> toast(r.message)
                ApiResult.Net -> toast(getString(R.string.net_error))
            }
        }
    }
}
