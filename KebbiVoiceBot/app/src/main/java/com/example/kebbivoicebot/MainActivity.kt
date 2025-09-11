 package com.example.kebbivoicebot

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nuwarobotics.service.agent.NuwaRobotAPI
import com.nuwarobotics.service.agent.RobotEventListener
import com.nuwarobotics.service.agent.VoiceEventListener
import com.nuwarobotics.service.facecontrol.UnityFaceCallback
import com.nuwarobotics.service.facecontrol.utils.ServiceConnectListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {

    /* ===================== 錄音 ===================== */
    private var recorder: MediaRecorder? = null
    private var outputFile: String = ""
    private val REQ_AUDIO = 200

    /* ================ Nuwa / 系統 TTS ================ */
    private var robotApi: NuwaRobotAPI? = null
    private var sysTts: TextToSpeech? = null
    private var sysTtsReady = false
    private var nuwaReady = false

    /* ================== FaceControl ================== */
    private var faceReady = false
    private val faceConnectListener = ServiceConnectListener { _: ComponentName?, connected: Boolean ->
        if (connected) runCatching { robotApi?.UnityFaceManager()?.registerCallback(unityFaceCb) }
    }
    // 保持空的 Callback（避免 SDK 差異造成 override 編譯錯誤）
    private val unityFaceCb = object : UnityFaceCallback() {}

    /* ================== Compose 狀態 ================== */
    private enum class UiState { IdlePrompt, Listening, Uploading, Speaking }
    private var uiState by mutableStateOf(UiState.IdlePrompt)
    private var overlayHint by mutableStateOf(HINT_TEXT)
    private lateinit var chatVM: ChatViewModel

    /* ===== 提示循環（30s 念一次）& 2 分鐘等待逾時 ===== */
    private val uiHandler = Handler(Looper.getMainLooper())
    private var hintRunnable: Runnable? = null
    private var idleAnnounceOn = false

    /* ===================== VAD 參數 ===================== */
    private var vadHandler: Handler? = null
    private var speakingStarted = false
    private var loudStart = 0L
    private var lastLoud = 0L
    private var listenBegin = 0L

    private val VAD_THRESHOLD = 1200
    private val VAD_MIN_START_MS = 300L
    private val VAD_SILENCE_MS = 1200L 
    private val LISTEN_TIMEOUT_MS = 120_000L
    private val HINT_PERIOD_MS = 30_000L

    /* ===================== onCreate ===================== */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestAudioPerm()

        // Nuwa 初始化
        robotApi = initNuwaRobotApiSafely()
        runCatching { robotApi?.registerRobotEventListener(robotListener) }

        // 系統 TTS 備援
        sysTts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val langs = listOf(
                    Locale.TRADITIONAL_CHINESE, Locale("zh", "HK"),
                    Locale.SIMPLIFIED_CHINESE, Locale("zh"), Locale.US
                )
                sysTtsReady = langs.any { loc ->
                    val r = sysTts?.setLanguage(loc)
                    r == TextToSpeech.LANG_AVAILABLE || r == TextToSpeech.LANG_COUNTRY_AVAILABLE
                }
                sysTts?.setSpeechRate(1.0f)
                sysTts?.setPitch(1.0f)
            }
        }

        setContent {
            val vm: ChatViewModel = viewModel()
            chatVM = vm
            val reply by vm.reply.collectAsState()

            LaunchedEffect(reply) {
                if (reply.isNotBlank()) speakSmartWithRetry(reply)
            }

            MaterialTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(if (uiState == UiState.IdlePrompt) Color.Black else Color.White)
                        .clickable(enabled = uiState == UiState.IdlePrompt) {
                            onScreenTapped()   // Compose 層可點；若被 Unity 蓋住，下面的 RobotEvent 也會接到
                        },
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Text(
                        text = overlayHint,
                        color = if (uiState == UiState.IdlePrompt) Color.White else Color.Black,
                        fontSize = 22.sp,
                        modifier = Modifier.padding(bottom = 36.dp)
                    )
                }
            }
        }

        // 進入待機提示
        enterIdlePrompt()
    }

    /* ===================== 權限 ===================== */
    private fun hasAudioPerm() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestAudioPerm() {
        if (!hasAudioPerm()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
        }
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        if (code == REQ_AUDIO) {
            Toast.makeText(
                this,
                if (r.firstOrNull() == PackageManager.PERMISSION_GRANTED) "錄音權限已授予" else "錄音權限被拒絕",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /* ========== 待機提示（黑底 + 每 30 秒念一次） ========== */
    private fun enterIdlePrompt() {
        uiState = UiState.IdlePrompt
        overlayHint = HINT_TEXT
        showRestFace()
        startIdleAnnouncer()
        runCatching { robotApi?.stopListen() }
    }

    private fun startIdleAnnouncer() {
        if (idleAnnounceOn) return
        idleAnnounceOn = true
        val r = object : Runnable {
            override fun run() {
                if (uiState == UiState.IdlePrompt) {
                    speakSmartWithRetry(HINT_TEXT)
                    uiHandler.postDelayed(this, HINT_PERIOD_MS)
                }
            }
        }
        hintRunnable = r
        uiHandler.post(r)
    }

    private fun stopIdleAnnouncer() {
        idleAnnounceOn = false
        hintRunnable?.let { uiHandler.removeCallbacks(it) }
        hintRunnable = null
    }

    /* =============== 點到螢幕（或 Robot 事件）後執行 =============== */
    private fun onScreenTapped() {
        if (uiState != UiState.IdlePrompt) return
        startListeningCycle()
    }

    /** 開始錄音 + 啟動 VAD；靜音自動結束後上傳 */
    private fun startListeningCycle() {
        stopIdleAnnouncer()
        uiState = UiState.Listening
        overlayHint = "我在聽，請說…"
        showFace()
        runCatching { robotApi?.stopListen() }
        startRecording()
        startVAD()
    }

    /* ===================== 錄音 ===================== */
    private fun startRecording() {
        if (!hasAudioPerm()) { requestAudioPerm(); return }
        outputFile = "${externalCacheDir?.absolutePath}/audio.3gp"
        recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            setOutputFile(outputFile)
            prepare()
            start()
        }
    }

    private fun stopRecording() {
        runCatching { recorder?.apply { try { stop() } catch (_: Throwable) {}; release() } }
        recorder = null
        stopVAD()
    }

    /* ===================== VAD（自動收尾） ===================== */
    private fun startVAD() {
        speakingStarted = false
        loudStart = 0L
        lastLoud = 0L
        listenBegin = System.currentTimeMillis()

        val handler = Handler(Looper.getMainLooper())
        vadHandler = handler

        val runnable = object : Runnable {
            override fun run() {
                val rec = recorder ?: return
                val now = System.currentTimeMillis()
                val amp = runCatching { rec.maxAmplitude }.getOrDefault(0)

                if (amp > VAD_THRESHOLD) {
                    if (loudStart == 0L) loudStart = now
                    if (!speakingStarted && now - loudStart >= VAD_MIN_START_MS) {
                        speakingStarted = true
                        lastLoud = now
                    }
                    if (speakingStarted) lastLoud = now
                } else {
                    loudStart = 0L
                }

                if (!speakingStarted) {
                    if (now - listenBegin >= LISTEN_TIMEOUT_MS) {
                        stopRecording()
                        enterIdlePrompt()
                        return
                    }
                    handler.postDelayed(this, 120L); return
                }

                val idle = now - lastLoud
                if (idle >= VAD_SILENCE_MS) {
                    stopRecording()
                    onUploading()
                    uploadAudioAndGetResponse(chatVM)
                    return
                }
                handler.postDelayed(this, 120L)
            }
        }
        handler.post(runnable)
    }

    private fun stopVAD() {
        vadHandler?.removeCallbacksAndMessages(null)
        vadHandler = null
    }

    /* ===================== UI 狀態轉換 ===================== */
    private fun onUploading() {
        uiState = UiState.Uploading
        overlayHint = "我想一下…"
        showFace(); mouthOff()
    }

    private fun onSpeakingStart() {
        uiState = UiState.Speaking
        overlayHint = "我來說給你聽…"
        showFace(); mouthOn(200L)
    }

    private fun onSpeakingDone() {
        mouthOff()
        stopMotionSafe()  // 說完把動作停掉（或改成收尾動作）
        startListeningCycle() // 接續對話
    }

    /* ======== 語音播放（Nuwa 優先 → Android TTS） ======== */
    private fun speakSmartWithRetry(text: String, maxRetry: Int = 4, delayMs: Long = 500) {
        if (text.isBlank()) return
        var tried = 0
        fun attempt() {
            tried++
            if (tryStartNuwaTTS(text)) return
            if (tried < maxRetry) {
                uiHandler.postDelayed({ attempt() }, delayMs)
            } else if (sysTtsReady) {
                onSpeakingStart()
                val utt = "utt-${System.currentTimeMillis()}"
                sysTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utt)
                val ms = (text.length * 120L).coerceAtLeast(800L)
                uiHandler.postDelayed({ onSpeakingDone() }, ms)
            }
        }
        attempt()
    }

    private fun tryStartNuwaTTS(text: String): Boolean {
        val api = robotApi ?: return false
        val ready: Boolean = try {
            (api.javaClass.getMethod("isKiWiServiceReady").invoke(api) as? Boolean) ?: nuwaReady
        } catch (_: Throwable) { nuwaReady }
        if (!ready) return false
        return try {
            val m2 = api.javaClass.getMethod("startTTS", String::class.java, String::class.java)
            onSpeakingStart()
            m2.invoke(api, text, "zh-TW")
            true
        } catch (_: NoSuchMethodException) {
            runCatching { onSpeakingStart(); api.stopTTS(); api.startTTS(text) }.isSuccess
        } catch (_: Throwable) { false }
    }

    /* ============ 上傳：/api/stt/ → /api/chat/ ============ */
    private fun uploadAudioAndGetResponse(vm: ChatViewModel) {
        val f = File(outputFile)
        if (!f.exists()) { Toast.makeText(this, "錄音檔案不存在", Toast.LENGTH_SHORT).show(); enterIdlePrompt(); return }

        val part = MultipartBody.Part.createFormData(
            "file", f.name, f.asRequestBody("audio/3gpp".toMediaTypeOrNull())
        )

        // ① STT
        RetrofitClient.api.stt(part).enqueue(object : Callback<STTResponse> {
            override fun onResponse(call: Call<STTResponse>, resp: Response<STTResponse>) {
                if (!resp.isSuccessful) {
                    Toast.makeText(this@MainActivity, "語音辨識失敗: ${resp.code()}", Toast.LENGTH_SHORT).show()
                    enterIdlePrompt(); return
                }
                val text = resp.body()?.text?.trim().orEmpty()
                if (text.isBlank()) { enterIdlePrompt(); return }

                // ② Chat（text/plain）
                val body: RequestBody = text.toRequestBody("text/plain; charset=utf-8".toMediaType())
                RetrofitClient.api.chat(body).enqueue(object : Callback<ChatResponse> {
                    override fun onResponse(call: Call<ChatResponse>, r: Response<ChatResponse>) {
                        if (r.isSuccessful) {
                            val reply = r.body()?.reply ?: "（沒有回應）"
                            val emo = r.body()?.final_emotion  // "快樂" / "悲傷" / "生氣" / "中性"
                            startMotionForEmotion(emo)         // ★ 先啟動對應動作
                            vm.setReply(reply)                 // 再讓 TTS 說話
                        } else {
                            Toast.makeText(this@MainActivity, "聊天失敗: ${r.code()}", Toast.LENGTH_SHORT).show()
                            enterIdlePrompt()
                        }
                    }
                    override fun onFailure(call: Call<ChatResponse>, t: Throwable) {
                        Toast.makeText(this@MainActivity, "聊天錯誤: ${t.message}", Toast.LENGTH_SHORT).show()
                        enterIdlePrompt()
                    }
                })
            }
            override fun onFailure(call: Call<STTResponse>, t: Throwable) {
                Toast.makeText(this@MainActivity, "上傳/辨識失敗: ${t.message}", Toast.LENGTH_SHORT).show()
                enterIdlePrompt()
            }
        })
    }

    /* ============= 情緒 → 動作：對照與播放工具 ============= */

    // 先放你截圖看到的名稱；若有播不出來，下面候選會自動補位，成功後會記住。
    private val EMOTION_TO_MOTION = mutableMapOf(
        "快樂" to "666_PE_PlayGuitar",
        "悲傷" to "666_RE_Bye",
        "生氣" to "666_DA_Scratching",
        "中性" to "666_TA_LookLR"
    )

    // 候選清單（依序嘗試）
    private val EMOTION_CANDIDATES = mapOf(
        "快樂" to listOf("666_PE_PlayGuitar", "666_IM_Rooster"),
        "悲傷" to listOf("666_RE_Bye", "666_PE_Killed"),
        "生氣" to listOf("666_DA_Scratching", "666_TA_LookRL"),
        "中性" to listOf("666_TA_LookLR", "666_TA_LookRL")
    )

    private fun startMotionForEmotion(emotionChinese: String?) {
        val key = when (emotionChinese) {
            "快樂", "開心" -> "快樂"
            "悲傷", "難過" -> "悲傷"
            "生氣", "憤怒" -> "生氣"
            else -> "中性"
        }
        val primary = EMOTION_TO_MOTION[key]
        if (!primary.isNullOrBlank() && playMotionSafe(primary)) return
        for (name in EMOTION_CANDIDATES[key].orEmpty()) {
            if (playMotionSafe(name)) {
                EMOTION_TO_MOTION[key] = name // 記住成功的
                return
            }
        }
    }

    private fun playMotionSafe(name: String, loop: Boolean = false): Boolean {
        val api = robotApi ?: return false
        // 試 playMotion(String)
        val ok = runCatching {
            val m = api.javaClass.getMethod("playMotion", String::class.java)
            m.invoke(api, name); true
        }.getOrElse {
            // 試 startMotion(String, boolean)
            runCatching {
                val m = api.javaClass.getMethod(
                    "startMotion", String::class.java, Boolean::class.javaPrimitiveType
                )
                m.invoke(api, name, loop); true
            }.getOrElse {
                // 試 playMotionByName(String)（少數舊版）
                runCatching {
                    val m = api.javaClass.getMethod("playMotionByName", String::class.java)
                    m.invoke(api, name); true
                }.getOrDefault(false)
            }
        }
        return ok
    }

    private fun stopMotionSafe() {
        val api = robotApi ?: return
        runCatching {
            val m = api.javaClass.getMethod("stopMotion")
            m.invoke(api)
        }
    }

    /* ================= Nuwa：Robot/Voice 事件 ================= */
    private val robotListener = object : RobotEventListener {
        override fun onWikiServiceStart() {
            nuwaReady = true
            runCatching {
                robotApi?.initFaceControl(
                    this@MainActivity,
                    this@MainActivity::class.java.name,
                    faceConnectListener
                )
            }
            runCatching { robotApi?.registerVoiceEventListener(voiceListener) }
        }

        override fun onWindowSurfaceReady()  { faceReady = true; showRestFace() }
        override fun onWindowSurfaceDestroy() { faceReady = false }
        override fun onWikiServiceStop()     { nuwaReady = false }
        override fun onWikiServiceCrash()    { nuwaReady = false }
        override fun onWikiServiceRecovery() { nuwaReady = true }

        // 這些是 SDK 的觸控事件，藉此觸發錄音（避免 Unity 蓋住時 Compose 收不到點擊）
        override fun onTap(p0: Int) { onScreenTapped() }
        override fun onRawTouch(p0: Int, p1: Int, p2: Int) { if (p2 == 0) onScreenTapped() } // ACTION_DOWN
        override fun onLongPress(p0: Int) { onScreenTapped() }
        override fun onTouchEyes(p0: Int, p1: Int) { /* required by SDK; no-op */ }

        // 其餘空實作
        override fun onStartOfMotionPlay(p0: String) {}
        override fun onPauseOfMotionPlay(p0: String) {}
        override fun onStopOfMotionPlay(p0: String) {}
        override fun onCompleteOfMotionPlay(p0: String) {}
        override fun onPlayBackOfMotionPlay(p0: String) {}
        override fun onErrorOfMotionPlay(p0: Int) {}
        override fun onPrepareMotion(p0: Boolean, p1: String, p2: Float) {}
        override fun onCameraOfMotionPlay(p0: String) {}
        override fun onGetCameraPose(
            p0: Float, p1: Float, p2: Float, p3: Float, p4: Float, p5: Float,
            p6: Float, p7: Float, p8: Float, p9: Float, p10: Float, p11: Float
        ) {}
        override fun onTouchEvent(p0: Int, p1: Int) {}
        override fun onPIREvent(p0: Int) {}
        override fun onFaceSpeaker(p0: Float) {}
        override fun onActionEvent(p0: Int, p1: Int) {}
        override fun onDropSensorEvent(p0: Int) {}
        override fun onMotorErrorEvent(p0: Int, p1: Int) {}
    }

    private val voiceListener = object : VoiceEventListener {
        override fun onTTSComplete(isError: Boolean) {
            runOnUiThread { onSpeakingDone() }
        }
        // 其它空實作
        override fun onWakeup(isError: Boolean, score: String, direction: Float) {}
        override fun onSpeechRecognizeComplete(isError: Boolean, iFlyResult: VoiceEventListener.ResultType, json: String) {}
        override fun onSpeech2TextComplete(isError: Boolean, json: String) {}
        override fun onMixUnderstandComplete(isError: Boolean, resultType: VoiceEventListener.ResultType, s: String) {}
        override fun onSpeechState(listenType: VoiceEventListener.ListenType, speechState: VoiceEventListener.SpeechState) {}
        override fun onSpeakState(speakType: VoiceEventListener.SpeakType, speakState: VoiceEventListener.SpeakState) {}
        override fun onGrammarState(isError: Boolean, s: String) {}
        override fun onListenVolumeChanged(listenType: VoiceEventListener.ListenType, i: Int) {}
        override fun onHotwordChange(hotwordState: VoiceEventListener.HotwordState, hotwordType: VoiceEventListener.HotwordType, s: String) {}
    }

    /* ================= Face 快捷 ================= */
    private fun showFace()      { runCatching { robotApi?.UnityFaceManager()?.showUnity() } }
    private fun showRestFace()  { runCatching { robotApi?.UnityFaceManager()?.showUnity(); robotApi?.UnityFaceManager()?.mouthOff() } }
    private fun mouthOn(speed: Long = 200L) { runCatching { robotApi?.UnityFaceManager()?.mouthOn(speed) } }
    private fun mouthOff()      { runCatching { robotApi?.UnityFaceManager()?.mouthOff() } }

    /* ===== Nuwa 取得實例（多 SDK 版本相容） ===== */
    private fun initNuwaRobotApiSafely(): NuwaRobotAPI? {
        runCatching {
            val c = NuwaRobotAPI::class.java
            val m = c.getMethod("getInstance", android.content.Context::class.java)
            return m.invoke(null, applicationContext) as? NuwaRobotAPI
        }
        val pair = obtainIClientId() ?: return null
        val icClazz = pair.first
        val clientId = pair.second
        runCatching {
            val c = NuwaRobotAPI::class.java
            val m = c.getMethod("getInstance", android.content.Context::class.java, icClazz)
            return m.invoke(null, applicationContext, clientId) as? NuwaRobotAPI
        }
        return runCatching {
            val ctor = NuwaRobotAPI::class.java.getConstructor(
                android.content.Context::class.java, icClazz
            )
            ctor.newInstance(applicationContext, clientId) as? NuwaRobotAPI
        }.getOrNull()
    }

    private fun obtainIClientId(): Pair<Class<*>, Any>? {
        runCatching {
            val k = Class.forName("com.nuwarobotics.service.agent.IClientId")
            val m = k.getMethod("getInstance", android.content.Context::class.java)
            return k to (m.invoke(null, this) as Any)
        }
        runCatching {
            val k = Class.forName("com.nuwarobotics.service.IClientId")
            val m = k.getMethod("getInstance", android.content.Context::class.java)
            return k to (m.invoke(null, this) as Any)
        }
        return runCatching {
            val k = Class.forName("com.nuwarobotics.service.IClientId")
            val ctor = k.getConstructor(String::class.java)
            k to (ctor.newInstance(packageName) as Any)
        }.getOrNull()
    }

    /* ================= 生命週期 ================= */
    override fun onResume() {
        super.onResume()
        if (nuwaReady) {
            runCatching {
                robotApi?.initFaceControl(
                    this@MainActivity,
                    this@MainActivity::class.java.name,
                    faceConnectListener
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        runCatching { robotApi?.UnityFaceManager()?.release() }
        faceReady = false
    }

    override fun onDestroy() {
        super.onDestroy()
        stopIdleAnnouncer()
        stopVAD()
        runCatching { robotApi?.release() }
        sysTts?.stop(); sysTts?.shutdown()
    }

    companion object {
        private const val HINT_TEXT = "你好啊有什麼事都可以跟我說喔"
    }
}
