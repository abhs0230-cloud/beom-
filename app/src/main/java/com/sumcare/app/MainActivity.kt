package com.sumcare.app

import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener
import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 숨케어 — 차 안 공기 안심 비서
 *
 * 1) 출발(기어 D) 시 바깥 미세먼지를 음성으로 브리핑
 * 2) 미세먼지가 나쁜데 창문이 열리기 시작하면 즉시 음성 경고 + 원터치 보호(창문 닫기·내기순환·공기청정)
 * 3) 공기가 좋으면 환기를 권장
 * 4) 음성 명령: "내기모드 켜줘", "공기청정 켜줘", "창문 닫아줘", "외기로 바꿔줘"
 * 5) 차 안팎 공기 흐름 애니메이션 (CabinView)
 */
class MainActivity : Activity() {

    private val tag = "SUMCARE"
    private val main = Handler(Looper.getMainLooper())

    // ── SDK ──
    private lateinit var vehicle: Vehicle
    private lateinit var navi: NaviHelper
    private lateinit var tts: TextToSpeech
    private lateinit var stt: SpeechToText
    private var ttsReady = false
    private var sttReady = false

    // ── 상태 ──
    private val sideWindows = listOf(
        WindowValue.ROW_1_LEFT to "운전석", WindowValue.ROW_1_RIGHT to "조수석",
        WindowValue.ROW_2_LEFT to "뒷좌석 왼쪽", WindowValue.ROW_2_RIGHT to "뒷좌석 오른쪽",
    )
    private val windowPos = mutableMapOf<WindowValue, Int>()
    private var gear: VehicleGear? = null
    private var recirc: Boolean? = null
    private var airCleaner: Boolean? = null
    private val hvacArea = SeatArea(
        SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_CENTER, SeatValue.ROW_2_RIGHT
    )
    private var place = "경기 성남시 분당구 판교동"
    private val demo = DemoAirSource()
    private val airSource: AirSource =
        if (BuildConfig.AIRKOREA_KEY.isNotBlank()) AirKoreaSource(BuildConfig.AIRKOREA_KEY) else demo
    private var reading: AirReading = demo.fetch(place)
    private var lastWindowAlert = 0L
    private val startedAt = System.currentTimeMillis()

    // ── 화면 ──
    private lateinit var captionText: TextView
    private lateinit var placeText: TextView
    private lateinit var gradeText: TextView
    private lateinit var pmText: TextView
    private lateinit var sourceText: TextView
    private lateinit var cabin: CabinView
    private lateinit var carText: TextView
    private lateinit var voiceCard: LinearLayout
    private lateinit var heardText: TextView
    private lateinit var voiceText: TextView
    private lateinit var actions: LinearLayout
    private lateinit var primaryBtn: Button
    private lateinit var secondaryBtn: Button
    private lateinit var micBtn: Button
    private var onPrimary: () -> Unit = {}
    private var onSecondary: () -> Unit = {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        initTts()
        initStt()
        initVehicle()
        initNavi()
        registerReceiver(demoReceiver, IntentFilter("com.sumcare.app.DEMO"), RECEIVER_EXPORTED)
        refreshAir()
        say("안녕하세요, 숨케어예요. 바깥 공기를 살피고 있을게요.", speak = false)
        main.postDelayed(poll, 1500)
    }

    // ───────────────────────── 시나리오 ─────────────────────────

    /** 출발 브리핑: 기어가 D로 바뀌면 바깥 공기를 알려 준다 */
    private fun onDepart() {
        val r = reading
        if (r.grade.isBad) {
            if (recirc == true) {
                say("출발할게요. 바깥 미세먼지가 '${r.grade.label}'이지만, 이미 내기순환 중이라 안심하셔도 돼요.")
            } else {
                say(
                    "출발 전에 알려 드려요. 지금 바깥 미세먼지가 '${r.grade.label}'이에요. 내기순환으로 바꿔 드릴까요?",
                    primary = "내기순환 켜기" to { protect(closeWindows = false) },
                    secondary = "괜찮아요" to { say("알겠어요. 창문을 열 땐 다시 알려 드릴게요.") },
                )
            }
        } else {
            say("출발할게요. 바깥 공기가 '${r.grade.label}'이에요. 오늘은 창문을 열고 달려도 좋아요.")
        }
    }

    /** 창문이 열리기 시작했을 때 */
    private fun onWindowOpening(name: String) {
        val now = System.currentTimeMillis()
        if (now - lastWindowAlert < 15_000) return
        lastWindowAlert = now
        val r = reading
        if (r.grade.isBad) {
            say(
                "잠깐만요! 오늘 미세먼지가 높아 내기순환을 권장드립니다. 창문을 닫고 내기순환으로 바꿀까요?",
                primary = "창문 닫고 내기순환" to { protect(closeWindows = true) },
                secondary = "이번만 열기" to { say("알겠어요. 환기가 끝나면 창문을 닫아 주세요.") },
            )
            Log.d(tag, "ALERT window opening ($name) with grade ${r.grade}")
        } else {
            say("바깥 공기가 '${r.grade.label}'이에요. 환기하기 좋은 날이에요.")
        }
    }

    /** 원터치 보호: 창문 닫기 + 내기순환 + 공기청정 */
    private fun protect(closeWindows: Boolean) {
        if (closeWindows) closeAllWindows()
        setRecirc(true)
        setCleaner(true)
        say(
            if (closeWindows) "창문을 닫고 내기순환과 공기청정을 켰어요. 쾌적하게 모실게요."
            else "내기순환과 공기청정을 켰어요. 쾌적하게 모실게요."
        )
    }

    private fun closeAllWindows() {
        val w = vehicle.getWindow()
        sideWindows.forEach { (v, _) ->
            w.setWindowPosition(WindowArea(v), 0, { _, _ -> }, { e -> Log.w(tag, "window close failed", e) })
        }
    }

    private fun setRecirc(on: Boolean) =
        vehicle.getHVAC().setHvacRecirculation(hvacArea, on, { _, v -> recirc = v; main.post { render() } }, { e -> Log.w(tag, "recirc failed", e) })

    private fun setCleaner(on: Boolean) =
        vehicle.getHVAC().setHvacAirCleaner(hvacArea, on, { _, v -> airCleaner = v; main.post { render() } }, { e -> Log.w(tag, "air cleaner failed", e) })

    // ───────────────────────── 음성 명령 ─────────────────────────

    /** 인식된 문장을 차량 동작으로 바꾼다 */
    private fun handleVoice(text: String, demoInput: Boolean = false) {
        heardText.text = "🗣  “$text”" + if (demoInput) "   · 시연 입력" else ""
        heardText.visibility = View.VISIBLE
        Log.d(tag, "HEARD: $text")
        val t = text.replace(" ", "")
        when {
            ("내기" in t || "실내순환" in t) && ("꺼" in t) -> { setRecirc(false); say("내기모드를 껐어요. 바깥 공기가 들어와요.") }
            "외기" in t -> { setRecirc(false); say("외기모드로 바꿨어요. 바깥 공기가 들어와요.") }
            "내기" in t || "실내순환" in t -> {
                setRecirc(true)
                if (reading.grade.isBad) {
                    setCleaner(true)
                    say("내기순환 모드로 바꿨어요. 바깥 미세먼지가 '${reading.grade.label}'이라 공기청정도 함께 켤게요.")
                } else say("내기순환 모드로 바꿨어요.")
            }
            "공기청정" in t -> { setCleaner(true); say("공기청정을 켰어요.") }
            "창문" in t && "닫" in t -> { closeAllWindows(); say("창문을 모두 닫을게요.") }
            "미세먼지" in t || "공기" in t -> say("지금 바깥 미세먼지는 '${reading.grade.label}', 초미세먼지 ${reading.pm25}마이크로그램이에요.")
            else -> say("죄송해요, 다시 한 번 말씀해 주세요. 예를 들어 '내기모드 켜줘'라고 말해 보세요.")
        }
    }

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (!sttReady) { say("음성 인식을 준비하고 있어요. 잠시 후 다시 눌러 주세요."); return }
        if (ttsReady) tts.stop()
        showListening()
        stt.request()
    }

    private fun showListening() {
        heardText.text = "🎤  듣고 있어요…"
        heardText.visibility = View.VISIBLE
        hideActions()
    }

    // ───────────────────────── SDK 연결 ─────────────────────────

    private fun initVehicle() {
        vehicle = Vehicle(this)
        vehicle.initialize()

        vehicle.getGear().registerGearSelection(object : GearSelectionListener {
            override fun onSelected(value: VehicleGear?) {
                val before = gear
                gear = value
                main.post { render() }
                // 앱 시작 직후 들어오는 현재 값은 건너뛰고, 이후 D로 바뀌는 순간을 출발로 본다
                val settled = System.currentTimeMillis() - startedAt > 3000
                if (settled && before != VehicleGear.GEAR_DRIVE && value == VehicleGear.GEAR_DRIVE) main.post { onDepart() }
            }
            override fun onFailed(e: Exception) { Log.w(tag, "gear listener failed", e) }
        })

        sideWindows.forEach { (v, name) ->
            vehicle.getWindow().registerWindowPosition(WindowArea(v), object : WindowPositionListener {
                override fun onWindowPositionUpdated(value: Int?) {
                    val pos = value ?: return
                    val before = windowPos[v]
                    windowPos[v] = pos
                    main.post { render() }
                    // 0 = 닫힘, 10 = 완전 열림. 값이 커지면 열리는 중
                    val settled = System.currentTimeMillis() - startedAt > 3000
                    if (settled && before != null && pos > before) main.post { onWindowOpening(name) }
                }
                override fun onFailed(e: Exception) { Log.w(tag, "window listener failed", e) }
            })
        }
    }

    /** 공조 상태는 짧은 주기로 다시 읽는다 (Virtual Console에서 바꾼 값도 반영) */
    private val poll = object : Runnable {
        override fun run() {
            val h = vehicle.getHVAC()
            h.getHvacRecirculationState(hvacArea, { on -> recirc = on; main.post { render() } }, { })
            h.getHvacAirCleanerState(hvacArea, { on -> airCleaner = on; main.post { render() } }, { })
            main.postDelayed(this, 1500)
        }
    }

    private fun initNavi() {
        navi = NaviHelper(applicationContext)
        navi.initialize(
            onConnected = {
                navi.addListener(object : NaviHelperEventListener {
                    override fun onCurrentLocationInfo(info: CurrentLocationInfo) {
                        info.address?.takeIf { it.isNotBlank() }?.let { place = it; refreshAir() }
                    }
                })
                navi.getCurrentLocationInfo()
            },
            onFailed = { t -> Log.w(tag, "navi connect failed", t) }
        )
    }

    private fun initTts() {
        tts = TextToSpeech(this, Mode.ON_DEVICE)
        tts.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                tts.setLocale(Locale.KOREA)
                ttsReady = true
            }
            override fun onFailure(errorType: ErrorType) { Log.w(tag, "tts init failed: $errorType") }
        })
        tts.addEventListener(object : EventListener {
            override fun onReady() = Unit
            override fun onStart() { main.post { setSpeaking(true) } }
            override fun onUpdatedRms(rms: Double) = Unit
            override fun onDone() { main.post { setSpeaking(false) } }
            override fun onStop() { main.post { setSpeaking(false) } }
            override fun onError(errorType: ErrorType) { Log.w(tag, "tts error: $errorType") }
        })
    }

    private fun initStt() {
        stt = SpeechToText(this, ai.pleos.playground.stt.constant.Mode.ON_DEVICE)
        stt.initialize(object : ai.pleos.playground.stt.listener.OnInitializeListener {
            override fun onSuccess() {
                stt.setLocale(Locale.KOREA)
                sttReady = true
            }
            override fun onFailure(errorType: ai.pleos.playground.stt.constant.ErrorType) { Log.w(tag, "stt init failed: $errorType") }
        })
        stt.addListener(object : ResultListener {
            override fun onReady() = Unit
            override fun onStartedRecognition() = Unit
            override fun onEndedRecognition() = Unit
            override fun onUpdatedRms(rms: Double) = Unit
            override fun onUpdatedEpdData(on: Long, off: Long) = Unit
            override fun onUpdated(text: String, isFinal: Boolean) {
                val s = text.trim()
                main.post {
                    if (isFinal && s.isNotEmpty()) handleVoice(s)
                    else if (s.isNotEmpty()) heardText.text = "🎤  $s…"
                }
            }
            override fun onError(errorType: ai.pleos.playground.stt.constant.ErrorType) {
                Log.w(tag, "stt error: $errorType")
                main.post { heardText.text = "🎤  음성을 듣지 못했어요" }
            }
        })
    }

    private fun refreshAir() {
        thread {
            val r = runCatching { airSource.fetch(place) }.getOrElse {
                Log.w(tag, "air fetch failed, using demo", it); demo.fetch(place)
            }
            main.post { reading = r; render() }
        }
    }

    // ───────────────────────── 음성 + 안내 카드 ─────────────────────────

    private fun say(
        text: String,
        primary: Pair<String, () -> Unit>? = null,
        secondary: Pair<String, () -> Unit>? = null,
        speak: Boolean = true,
    ) {
        Log.d(tag, "SAY: $text")
        voiceText.text = text
        if (primary != null) {
            primaryBtn.text = primary.first; onPrimary = { hideActions(); primary.second() }
            secondaryBtn.text = secondary?.first ?: "닫기"; onSecondary = { hideActions(); secondary?.second?.invoke() }
            actions.visibility = View.VISIBLE
        } else {
            hideActions()
        }
        if (speak && ttsReady) {
            tts.stop()
            tts.speak(text, true)
        }
    }

    private fun hideActions() { actions.visibility = View.GONE }

    private fun setSpeaking(on: Boolean) {
        (voiceCard.background as GradientDrawable).setStroke(dp(3), if (on) ACCENT else LINE)
    }

    // ───────────────────────── 시연 보조 (데모 모드) ─────────────────────────

    /**
     * adb shell am broadcast -a com.sumcare.app.DEMO
     *   --es grade good|normal|bad|verybad   바깥 공기 데모 값
     *   --es caption s1|s2|s3|s4|end|none    장면 자막
     *   --es tap primary|secondary           안내 버튼 누르기
     *   --es voice recirc_on|cleaner_on|close_windows  음성 명령 시연 입력
     *   --ez intro true / --ez mark true
     */
    private val demoReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.getBooleanExtra("mark", false)) Log.d(tag, "MARK")
            i.getStringExtra("grade")?.let { setDemoGrade(it) }
            i.getStringExtra("caption")?.let { key ->
                val it = when (key) {
                    "s1" -> "장면 1 · 미세먼지 '좋음'인 날"
                    "s2" -> "장면 2 · 미세먼지 '나쁨'인 날 출발"
                    "s3" -> "장면 3 · 창문을 열려고 할 때"
                    "s4" -> "장면 4 · 말로 내기순환 모드 켜기"
                    "end" -> "창문 닫힘 · 내기순환 · 공기청정 ON"
                    "none" -> ""
                    else -> key
                }
                captionText.text = it
                captionText.visibility = if (it.isBlank()) View.GONE else View.VISIBLE
            }
            if (i.getBooleanExtra("intro", false)) say("안녕하세요, 숨케어예요. 바깥 공기를 살피고, 필요할 때 알려 드릴게요.")
            i.getStringExtra("voice")?.let { key ->
                val phrase = when (key) {
                    "recirc_on" -> "내기 순환모드로 해줘"
                    "cleaner_on" -> "공기청정 켜줘"
                    "close_windows" -> "창문 닫아줘"
                    else -> key
                }
                showListening()
                main.postDelayed({ handleVoice(phrase, demoInput = true) }, 1800)
            }
            when (i.getStringExtra("tap")) {
                "primary" -> onPrimary()
                "secondary" -> onSecondary()
            }
        }
    }

    private fun setDemoGrade(g: String) {
        demo.pm25 = when (g) { "good" -> 12; "normal" -> 28; "bad" -> 58; else -> 92 }
        reading = demo.fetch(place)
        lastWindowAlert = 0L
        render()
    }

    // ───────────────────────── 화면 구성 ─────────────────────────

    private fun render() {
        val r = reading
        placeText.text = "📍 $place"
        gradeText.text = r.grade.label
        gradeText.setTextColor(r.grade.color)
        pmText.text = "초미세먼지 PM2.5\n${r.pm25} µg/m³\n\n미세먼지 PM10\n${r.pm10} µg/m³"
        sourceText.text = r.source

        cabin.gradeLevel = r.grade.ordinal
        cabin.gradeColor = r.grade.color
        cabin.recirc = recirc == true
        cabin.cleaner = airCleaner == true
        sideWindows.forEachIndexed { idx, (v, _) -> cabin.windows[idx] = (windowPos[v] ?: 0) / 10f }

        val gearLabel = when (gear) {
            VehicleGear.GEAR_PARK -> "P"; VehicleGear.GEAR_DRIVE -> "D"
            VehicleGear.GEAR_REVERSE -> "R"; VehicleGear.GEAR_NEUTRAL -> "N"; null -> "-"; else -> gear!!.name
        }
        val open = sideWindows.filter { (windowPos[it.first] ?: 0) > 0 }
            .joinToString(", ") { "${it.second} ${(windowPos[it.first] ?: 0) * 10}%" }
        carText.text = "기어 $gearLabel   ·   창문 " + (if (open.isEmpty()) "모두 닫힘" else "열림: $open") +
            "\n공기 " + when (recirc) { true -> "내기순환"; false -> "외기 유입"; null -> "-" } +
            "   ·   공기청정 " + when (airCleaner) { true -> "켜짐"; false -> "꺼짐"; null -> "-" }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(40), dp(24), dp(40), dp(24))
        }

        // 헤더
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text("숨케어", 52f, Color.WHITE, bold = true))
            addView(text("   차 안 공기 안심 비서", 28f, MUTED))
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            captionText = text("", 26f, Color.BLACK, bold = true).apply {
                setPadding(dp(24), dp(10), dp(24), dp(10))
                background = GradientDrawable().apply { setColor(ACCENT); cornerRadius = dp(40).toFloat() }
                visibility = View.GONE
            }
            addView(captionText)
        })
        root.addView(space(18))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val airCard = card().apply {
            addView(text("바깥 공기", 26f, MUTED))
            placeText = text("", 24f, Color.WHITE).also { addView(it) }
            addView(space(8))
            gradeText = text("", 96f, Color.WHITE, bold = true).also { addView(it) }
            pmText = text("", 28f, Color.WHITE).also { addView(it) }
            addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
            sourceText = text("", 20f, MUTED).also { addView(it) }
        }
        val cabinCard = card().apply {
            addView(text("차 안팎 공기 흐름", 26f, MUTED))
            cabin = CabinView(context).also { addView(it, LinearLayout.LayoutParams(-1, 0, 1f)) }
            carText = text("", 24f, Color.WHITE).also { addView(it) }
        }
        voiceCard = card().apply {
            addView(text("🔊 숨케어 음성 안내", 28f, ACCENT, bold = true))
            addView(space(14))
            heardText = text("", 30f, Color.WHITE).apply {
                setPadding(dp(20), dp(12), dp(20), dp(12))
                background = GradientDrawable().apply { setColor(CARD2); cornerRadius = dp(18).toFloat() }
                visibility = View.GONE
            }.also { addView(it) }
            addView(space(14))
            voiceText = text("", 38f, Color.WHITE).also { addView(it) }
            addView(space(20))
            actions = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                visibility = View.GONE
                primaryBtn = button(ACCENT, Color.BLACK) { onPrimary() }.also { addView(it, LinearLayout.LayoutParams(0, dp(110), 1.3f)) }
                addView(View(context), LinearLayout.LayoutParams(dp(16), 1))
                secondaryBtn = button(CARD2, Color.WHITE) { onSecondary() }.also { addView(it, LinearLayout.LayoutParams(0, dp(110), 1f)) }
            }
            addView(actions)
            addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
            micBtn = button(CARD2, ACCENT) { startListening() }.apply { text = "🎤  말로 하기  ·  “내기모드 켜줘”" }
            addView(micBtn, LinearLayout.LayoutParams(-1, dp(96)))
        }
        row.addView(airCard, LinearLayout.LayoutParams(0, -1, 0.8f).apply { marginEnd = dp(20) })
        row.addView(cabinCard, LinearLayout.LayoutParams(0, -1, 1.25f).apply { marginEnd = dp(20) })
        row.addView(voiceCard, LinearLayout.LayoutParams(0, -1, 1.35f))
        root.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
        render()
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(28), dp(24), dp(28), dp(24))
        background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(24).toFloat(); setStroke(dp(3), LINE) }
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.15f)
    }

    private fun button(bg: Int, fg: Int, onClick: () -> Unit) = Button(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f); setTextColor(fg); typeface = Typeface.DEFAULT_BOLD
        isAllCaps = false
        background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(18).toFloat() }
        setOnClickListener { onClick() }
    }

    private fun space(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(demoReceiver) }
        runCatching { tts.release() }
        runCatching { stt.release() }
        runCatching { navi.release() }
        runCatching { vehicle.release() }
        super.onDestroy()
    }

    companion object {
        private val BG = 0xFF0B1220.toInt()
        private val CARD = 0xFF141C2E.toInt()
        private val CARD2 = 0xFF26324A.toInt()
        private val LINE = 0xFF26324A.toInt()
        private val MUTED = 0xFF8A97B0.toInt()
        private val ACCENT = 0xFF5EEAD4.toInt()
    }
}
