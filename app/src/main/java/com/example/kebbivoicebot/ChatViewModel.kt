package com.example.kebbivoicebot

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class ChatViewModel : ViewModel() {
    private val _reply = MutableStateFlow("")
    val reply: StateFlow<String> = _reply

    fun sendMessage(text: String) {
        val call = RetrofitClient.api.sendMessage(Message(text))
        call.enqueue(object : Callback<ChatResponse> {
            override fun onResponse(call: Call<ChatResponse>, response: Response<ChatResponse>) {
                if (response.isSuccessful) {
                    val result = response.body()?.reply ?: "（沒有回應）"
                    _reply.value = result
                } else {
                    _reply.value = "伺服器錯誤: ${response.code()}"
                }
            }

            override fun onFailure(call: Call<ChatResponse>, t: Throwable) {
                _reply.value = "錯誤: ${t.localizedMessage}"
            }
        })
    }

    fun setReply(value: String) {
        _reply.value = value
    }
}


