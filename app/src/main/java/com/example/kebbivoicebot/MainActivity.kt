package com.example.kebbivoicebot

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nuwarobotics.service.agent.NuwaRobotAPI
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {

    // ─── 錄音 ───
    private var recorder: MediaRecorder? = null
    private var outputFile: String = ""
    private val REQUEST_RECORD_AUDIO_PERMISSION = 200

    // ─── Nuwa / 系統 TTS ───
    private var robotApi: NuwaRobotAPI? = null
    private var sysTts: TextToSpeech? = null
    private var sysTtsReady: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions()

        // ① 初始化 Nuwa（涵蓋多版本）
        robotApi = initNuwaRobotApiSafely()

        // ② 系統 TTS 備援
        sysTts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val candidates = listOf(
                    Locale.TRADITIONAL_CHINESE, Locale("zh", "HK"),
                    Locale.SIMPLIFIED_CHINESE, Locale("zh"), Locale.US
                )
                sysTtsReady = candidates.any { loc ->
                    val r = sysTts?.setLanguage(loc)
                    r == TextToSpeech.LANG_AVAILABLE || r == TextToSpeech.LANG_COUNTRY_AVAILABLE
                }
                sysTts?.setSpeechRate(1.0f)
                sysTts?.setPitch(1.0f)
            }
        }

        setContent {
            val chatViewModel: ChatViewModel = viewModel()
            val reply by chatViewModel.reply.collectAsState()

            // ★ reply 改變就朗讀（集中在這裡避免重複播放）
            LaunchedEffect(reply) {
                if (reply.isNotBlank()) {
                    speakSmartWithRetry(reply) // ← 自動檢查 Nuwa 就緒 / 指定語系 / 重試 / 備援
                }
            }

            VoiceChatUI(
                onStartRecording = { startRecording() },
                onStopRecordingAndSend = {
                    stopRecording()
                    uploadAudioAndGetResponse(chatViewModel)
                },
                chatViewModel = chatViewModel
            )
        }
    }

    // ─── 權限 ───
    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        if (!hasRecordAudioPermission()) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO_PERMISSION
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            Toast.makeText(
                this,
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) "錄音權限已授予" else "錄音權限被拒絕",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ─── 錄音 ───
    private fun startRecording() {
        if (!hasRecordAudioPermission()) {
            Toast.makeText(this, "請先開啟錄音權限", Toast.LENGTH_SHORT).show()
            requestPermissions(); return
        }
        // THREE_GPP/AMR_NB → 檔名與 MIME 使用 3gp
        outputFile = "${externalCacheDir?.absolutePath}/audio.3gp"
        recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            setOutputFile(outputFile)
            prepare()
            start()
        }
        Toast.makeText(this, "開始錄音", Toast.LENGTH_SHORT).show()
    }

    private fun stopRecording() {
        try {
            recorder?.apply {
                try { stop() } catch (_: IllegalStateException) {}
                release()
            }
        } finally {
            recorder = null
            Toast.makeText(this, "錄音結束", Toast.LENGTH_SHORT).show()
        }
    }

    // =============== 語音播放：Nuwa 優先 → 系統 TTS 備援（含就緒檢查 / 語系 / 重試） ===============

    private fun speakSmartWithRetry(text: String, maxRetry: Int = 5, delayMs: Long = 600) {
        if (text.isBlank()) return

        // 先搶音訊焦點 & 拉高媒體音量，避免音量=0
        try {
            val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                val req = android.media.AudioFocusRequest.Builder(
                    android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                ).setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                ).build()
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    null,
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
            val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (max * 0.8).toInt(), 0)
        } catch (_: Throwable) {}

        // 重試邏輯
        var tries = 0
        fun attempt() {
            tries++
            if (tryStartNuwaTTS(text)) {
                Toast.makeText(this, "用 Nuwa TTS", Toast.LENGTH_SHORT).show()
                return
            }
            if (tries < maxRetry) {
                window.decorView.postDelayed({ attempt() }, delayMs)
            } else {
                // 備援：系統 TTS
                if (sysTtsReady) {
                    sysTts?.speak(
                        text,
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "utt-${System.currentTimeMillis()}"
                    )
                    Toast.makeText(this, "用 系統 TTS", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "沒有可用的 TTS 引擎", Toast.LENGTH_SHORT).show()
                }
            }
        }
        attempt()
    }

    /** 嘗試以 Nuwa 播放一次；成功回 true，否則回 false（不拋例外） */
    private fun tryStartNuwaTTS(text: String): Boolean {
        val api = robotApi ?: return false

        // 若裝置提供 isKiWiServiceReady()，先檢查就緒
        val ready: Boolean = try {
            (api.javaClass.getMethod("isKiWiServiceReady").invoke(api) as? Boolean) ?: true
        } catch (_: Throwable) {
            true // 沒這個方法就直接嘗試
        }

        if (!ready) return false

        return try {
            // 先試 (text, locale) 版本
            val m2 = api.javaClass.getMethod("startTTS", String::class.java, String::class.java)
            m2.invoke(api, text, "zh-TW")
            true
        } catch (_: NoSuchMethodException) {
            // 沒有兩參數 → 試單參數
            runCatching {
                api.stopTTS()
                api.startTTS(text)
            }.isSuccess
        } catch (e: Throwable) {
            android.util.Log.e("NuwaTTS", "startTTS 失敗", e)
            false
        }
    }

    // =============== Nuwa 初始化（同時相容不同版本的 IClientId 與建構方式） ===============

    private fun initNuwaRobotApiSafely(): NuwaRobotAPI? {
        // 1) 先試 getInstance(Context)
        runCatching {
            val clazz = NuwaRobotAPI::class.java
            val m1 = clazz.getMethod("getInstance", android.content.Context::class.java)
            return m1.invoke(null, applicationContext) as? NuwaRobotAPI
        }

        // 2) 取到 IClientId（嘗試多個版本/包名）
        val (icClazz, clientId) = obtainIClientId() ?: return null

        // 3) 試 getInstance(Context, IClientId)
        runCatching {
            val clazz = NuwaRobotAPI::class.java
            val m2 = clazz.getMethod("getInstance", android.content.Context::class.java, icClazz)
            return m2.invoke(null, applicationContext, clientId) as? NuwaRobotAPI
        }

        // 4) 試建構式 NuwaRobotAPI(Context, IClientId)
        return runCatching {
            val ctor = NuwaRobotAPI::class.java.getConstructor(
                android.content.Context::class.java, icClazz
            )
            ctor.newInstance(applicationContext, clientId) as? NuwaRobotAPI
        }.getOrNull()
    }

    /** 嘗試從不同包名/不同 API 取得 IClientId 物件 */
    private fun obtainIClientId(): Pair<Class<*>, Any>? {
        // A. com.nuwarobotics.service.agent.IClientId.getInstance(Context)
        runCatching {
            val k = Class.forName("com.nuwarobotics.service.agent.IClientId")
            val m = k.getMethod("getInstance", android.content.Context::class.java)
            val id = m.invoke(null, this)
            return k to id
        }

        // B. com.nuwarobotics.service.IClientId.getInstance(Context)
        runCatching {
            val k = Class.forName("com.nuwarobotics.service.IClientId")
            val m = k.getMethod("getInstance", android.content.Context::class.java)
            val id = m.invoke(null, this)
            return k to id
        }

        // C. com.nuwarobotics.service.IClientId(String) 建構式
        return runCatching {
            val k = Class.forName("com.nuwarobotics.service.IClientId")
            val ctor = k.getConstructor(String::class.java)
            val id = ctor.newInstance(packageName)
            k to id
        }.getOrNull()
    }

    // ─── 上傳音檔（朗讀交由 LaunchedEffect 觸發） ───
    private fun uploadAudioAndGetResponse(viewModel: ChatViewModel) {
        val file = File(outputFile)
        if (!file.exists()) {
            Toast.makeText(this, "錄音檔案不存在", Toast.LENGTH_SHORT).show()
            return
        }
        // 依錄音設定 THREE_GPP/AMR_NB → audio/3gpp
        val requestBody = file.asRequestBody("audio/3gpp".toMediaTypeOrNull())
        val multipartBody = MultipartBody.Part.createFormData("file", file.name, requestBody)

        RetrofitClient.api.uploadAudio(multipartBody).enqueue(object : Callback<ChatResponse> {
            override fun onResponse(call: Call<ChatResponse>, response: Response<ChatResponse>) {
                if (response.isSuccessful) {
                    val result = response.body()?.reply ?: "（沒有回應）"
                    Toast.makeText(this@MainActivity, "上傳成功", Toast.LENGTH_SHORT).show()
                    viewModel.setReply(result) // 交由 LaunchedEffect(reply) 觸發朗讀
                } else {
                    Toast.makeText(this@MainActivity, "上傳錯誤: ${response.code()}", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onFailure(call: Call<ChatResponse>, t: Throwable) {
                Toast.makeText(this@MainActivity, "上傳失敗: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        try { robotApi?.release() } catch (_: Throwable) {}
        sysTts?.stop()
        sysTts?.shutdown()
    }
}

@Composable
fun VoiceChatUI(
    onStartRecording: () -> Unit,
    onStopRecordingAndSend: () -> Unit,
    chatViewModel: ChatViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Button(onClick = onStartRecording, modifier = Modifier.fillMaxWidth()) {
            Text("🎧 開始錄音")
        }
        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = onStopRecordingAndSend, modifier = Modifier.fillMaxWidth()) {
            Text("✅ 結束並上傳")
        }
        Spacer(modifier = Modifier.height(40.dp))
        ChatUI(viewModel = chatViewModel)
    }
}

@Composable
fun ChatUI(viewModel: ChatViewModel = viewModel()) {
    var userInput by remember { mutableStateOf("") }
    val reply by viewModel.reply.collectAsState()

    Column(modifier = Modifier.padding(16.dp)) {
        TextField(
            value = userInput,
            onValueChange = { userInput = it },
            label = { Text("輸入對話內容") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = { viewModel.sendMessage(userInput) }) {
            Text("送出")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text("AI 回覆：$reply")
    }
}

