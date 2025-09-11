package com.example.kebbivoicebot

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    // 建議：塞你目前 ngrok 的 HTTPS 轉址（要以 / 結尾）
    // 例： "https://22f103c463cc.ngrok-free.app/"
    @Volatile
    private var baseUrl: String = "https://3fdd855bd740.ngrok-free.app/"

    fun setBaseUrl(url: String) { baseUrl = if (url.endsWith("/")) url else "$url/" }

    private val logging = HttpLoggingInterceptor().apply {
        // 開發時可開 BODY，看得出請求/回應內文
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(logging)
            .retryOnConnectionFailure(true)
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    val api: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
