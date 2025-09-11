package com.example.kebbivoicebot

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class ChatViewModel : ViewModel() {

    private val _reply = MutableStateFlow("")
    val reply = _reply.asStateFlow()

    fun setReply(text: String) {
        _reply.value = text
    }

    /** 文字輸入（非錄音流程），直接丟到 /api/chat/ 取得回覆 */
    fun sendMessage(text: String) {
        if (text.isBlank()) return
        // 後端 /api/chat/ 接純文字 → 用 text/plain
        val body: RequestBody = text.toRequestBody("text/plain; charset=utf-8".toMediaType())

        RetrofitClient.api.chat(body).enqueue(object : Callback<ChatResponse> {
            override fun onResponse(call: Call<ChatResponse>, response: Response<ChatResponse>) {
                _reply.value = if (response.isSuccessful) {
                    response.body()?.reply ?: "（沒有回應）"

                } else {
                    "伺服器錯誤：${response.code()}"
                }
            }

            override fun onFailure(call: Call<ChatResponse>, t: Throwable) {
                _reply.value = "連線失敗：${t.message}"
            }
        })
    }
}
