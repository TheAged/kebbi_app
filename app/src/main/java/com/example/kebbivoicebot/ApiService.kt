package com.example.kebbivoicebot

import okhttp3.MultipartBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part



interface ApiService {

    // ✅ 上傳音訊檔案（對應 FastAPI 中的 /upload_audio）
    @Multipart
    @POST("/upload_audio")
    fun uploadAudio(
        @Part file: MultipartBody.Part
    ): Call<ChatResponse>

    // ✅ 傳送文字訊息（對應 FastAPI 中的 /api/chat）
    @POST("/api/chat")
    fun sendMessage(
        @Body msg: Message
    ): Call<ChatResponse>
}

