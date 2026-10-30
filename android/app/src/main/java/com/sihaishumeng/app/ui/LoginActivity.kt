package com.sihaishumeng.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.sihaishumeng.app.MainActivity
import com.sihaishumeng.app.R
import com.sihaishumeng.app.core.SiteConfig
import com.sihaishumeng.app.core.api.ApiClient
import com.sihaishumeng.app.social.SocialAuthController
import org.json.JSONObject

/**
 * 批③ 原生登录屏（/auth/login 原生接管，批④ T1 页首件）。
 *
 * - 密码登录：识别符（邮箱/用户名/手机号——服务端 before-hook 归一）→ 手机形走
 *   /sign-in/phone-number、其余 /sign-in/email；M312 回退复刻（手机形失败且不含 @
 *   → 原文重试 email 端点，捞用户名形似手机的存量号）。成功判据=200 且响应带
 *   token（better-auth 错误形态恒 4xx+{error}）；会话 cookie 由 WebViewCookieJar
 *   自动落 CookieManager，长尾 WebView 即刻共享。
 * - 四家社交：SocialAuthController（微信/QQ SDK 拉起、B站授权屏；支付宝待 AAR）。
 * - 邀请码：折叠输入，随社交开号上送（服务端先验码再开号——M340 教训）。
 * - 注册（邮箱验证链）/找回密码/网页版登录：回主 WebView 原址（loadUrl 不经
 *   shouldOverrideUrlLoading=不会被本屏接管逻辑再拦）。
 */
class LoginActivity : AppCompatActivity(), SocialAuthController.Callback {

    private lateinit var controller: SocialAuthController

    private lateinit var etIdentifier: EditText
    private lateinit var etPassword: EditText
    private lateinit var etInvite: EditText
    private lateinit var btnLogin: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvInviteToggle: TextView

    private var inviteVisible = false
    private var submitting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_login)
        controller = SocialAuthController(this)
        bindViews()
    }

    private fun bindViews() {
        val root = findViewById<View>(R.id.root)
        etIdentifier = findViewById(R.id.etIdentifier)
        etPassword = findViewById(R.id.etPassword)
        etInvite = findViewById(R.id.etInvite)
        btnLogin = findViewById(R.id.btnLogin)
        tvStatus = findViewById(R.id.tvStatus)
        tvInviteToggle = findViewById(R.id.tvInviteToggle)

        // targetSdk36 edge-to-edge：系统栏+键盘 inset 自管（adjustResize 下 ime 也走 insets）
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottom = maxOf(sys.bottom, ime.bottom)
            v.setPadding(v.paddingLeft, sys.top, v.paddingRight, bottom)
            WindowInsetsCompat.CONSUMED
        }

        btnLogin.setOnClickListener { submitPassword() }
        tvInviteToggle.setOnClickListener {
            inviteVisible = !inviteVisible
            etInvite.visibility = if (inviteVisible) View.VISIBLE else View.GONE
        }

        findViewById<Button>(R.id.btnWechat).setOnClickListener { startSocial(SocialAuthController.Provider.WECHAT) }
        findViewById<Button>(R.id.btnQq).setOnClickListener { startSocial(SocialAuthController.Provider.QQ) }
        findViewById<Button>(R.id.btnBili).setOnClickListener { startSocial(SocialAuthController.Provider.BILIBILI) }

        findViewById<TextView>(R.id.tvWebLogin).setOnClickListener { openInWebView(SiteConfig.NATIVE_LOGIN_PATH) }
        findViewById<TextView>(R.id.tvRegister).setOnClickListener { openInWebView("/auth/register") }
        findViewById<TextView>(R.id.tvForgot).setOnClickListener { openInWebView("/auth/forgot-password") }
    }

    // ── 密码登录 ──

    private fun submitPassword() {
        if (submitting) return
        val id = etIdentifier.text.toString().trim()
        val pwd = etPassword.text.toString()
        if (id.isEmpty() || pwd.isEmpty()) {
            setStatus("请填写账号与密码")
            return
        }
        setStatus(null)
        submitting = true
        btnLogin.isEnabled = false
        val phoneShaped = Regex("^1\\d{10}$").matches(id)
        postSignIn(id, pwd, phoneShaped, retryAsEmail = !id.contains('@'))
    }

    private fun postSignIn(id: String, pwd: String, asPhone: Boolean, retryAsEmail: Boolean) {
        val body = if (asPhone) {
            JSONObject().put("phoneNumber", id).put("password", pwd)
        } else {
            JSONObject().put("email", id).put("password", pwd)
        }
        val path = if (asPhone) "/api/auth/sign-in/phone-number" else "/api/auth/sign-in/email"
        ApiClient.postJson(path, body) { code, json ->
            submitting = false
            btnLogin.isEnabled = true
            when {
                code == 200 && json != null && !json.has("error") -> {
                    Toast.makeText(this, R.string.login_success, Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK) // MainActivity 收到即 reload 当前页显登录态
                    finish()
                }
                code in 400..499 -> {
                    val ec = json?.optJSONObject("error")?.optString("code").orEmpty()
                    val msg = json?.optJSONObject("error")?.optString("message")
                    // M312 回退：手机形失败且输入不含 @（用户名形似手机号的老号）→ email 端点重试
                    if (retryAsEmail && asPhone && ec == "INVALID_PHONE_NUMBER_OR_PASSWORD") {
                        postSignIn(id, pwd, asPhone = false, retryAsEmail = false)
                    } else {
                        setStatus(msg ?: "登录失败（$code）")
                    }
                }
                else -> setStatus(if (code < 0) "网络异常，请稍后再试" else "登录失败（$code）")
            }
        }
    }

    // ── 社交登录 ──

    private fun startSocial(provider: SocialAuthController.Provider) {
        if (provider == SocialAuthController.Provider.WECHAT && !controller.isAvailable(provider)) {
            Toast.makeText(this, R.string.login_wechat_missing, Toast.LENGTH_SHORT).show()
            return
        }
        setStatus(null)
        val invite = if (inviteVisible) etInvite.text.toString().trim() else null
        controller.start(provider, invite, this)
    }

    override fun onSocialResult(ok: Boolean, message: String?) {
        if (ok) {
            Toast.makeText(this, R.string.login_success, Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK) // MainActivity 收到即 reload 当前页显登录态
            finish()
        } else {
            setStatus(message ?: "登录失败")
        }
    }

    /** QQ SDK 授权结果回灌（SDK 铁律：必须透传给 Tencent.onActivityResultData）。 */
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        controller.onActivityResult(requestCode, resultCode, data)
    }

    // ── 工具 ──

    private fun setStatus(text: String?) {
        if (text == null) {
            tvStatus.visibility = View.GONE
        } else {
            tvStatus.text = text
            tvStatus.visibility = View.VISIBLE
        }
    }

    private fun openInWebView(path: String) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_URL, SiteConfig.SITE_URL + path)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }
}
