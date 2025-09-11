package com.example.kebbivoicebot

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface ApiService {

    /** 上傳音檔做 STT → 後端路徑 /api/stt/（注意結尾斜線） */
    @Multipart
    @POST("api/stt/")
    fun stt(@Part file: MultipartBody.Part): Call<STTResponse>

    /** 將「純文字」送到 /api/chat/（後端期望 raw string） */
    @Headers("Content-Type: text/plain; charset=utf-8")
    @POST("api/chat/")
    fun chat(@Body body: RequestBody): Call<ChatResponse>
}





