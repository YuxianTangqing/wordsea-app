package com.sihaishumeng.app.core.api

import com.sihaishumeng.app.core.session.SessionStore
import org.json.JSONObject

/**
 * 批④ 信封归一层：站点 /api/v1 统一响应 {code, message, data}（code=0 成功）。
 * 页面代码不手拆信封——全部经本层拿 ApiResult，401 顺带通知 SessionStore 降游客
 * （登录态单点失效口，防「会话过期后原生页还假装登录态」）。
 */
sealed class ApiResult {
    /** code=0（data 可为 null——ok(null) 形态端点存在） */
    class Ok(val data: JSONObject?) : ApiResult()
    /** 业务/HTTP 错误：code=信封 code（无信封时用 HTTP 状态），message=服务端文案 */
    class Err(val code: Int, val message: String) : ApiResult()
    /** 网络层失败（-1：断网/超时/解析失败） */
    object Net : ApiResult()
}

object Api {

    fun get(path: String, query: Map<String, String?>? = null, cb: (ApiResult) -> Unit) {
        ApiClient.getJson(path, query) { code, json -> cb(settle(code, json)) }
    }

    fun post(path: String, body: JSONObject, cb: (ApiResult) -> Unit) {
        ApiClient.postJson(path, body) { code, json -> cb(settle(code, json)) }
    }

    fun patch(path: String, body: JSONObject, cb: (ApiResult) -> Unit) {
        ApiClient.sendJson("PATCH", path, body) { code, json -> cb(settle(code, json)) }
    }

    fun delete(path: String, cb: (ApiResult) -> Unit) {
        ApiClient.sendJson("DELETE", path, null) { code, json -> cb(settle(code, json)) }
    }

    private fun settle(code: Int, json: JSONObject?): ApiResult {
        if (json == null) return ApiResult.Net
        val envelopeCode = json.optInt("code", code)
        if (code == 401 || envelopeCode == 401) {
            SessionStore.markLoggedOut()
            return ApiResult.Err(401, json.optString("message", "请先登录"))
        }
        return if (envelopeCode == 0) {
            ApiResult.Ok(json.optJSONObject("data"))
        } else {
            ApiResult.Err(envelopeCode, json.optString("message", "请求失败"))
        }
    }
}
