package com.shinjo.shonanandroid.langstone

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.shinjo.shonanandroid.net.Esp32PttClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * スペクトラム・ウォーターフォール・メーターの表示用データ。15回/秒程度で更新されるため、
 * 画面全体を描き直さないようコントローラ本体の状態とは分けている(iPad版LangstoneDisplay)。
 */
class LangstoneDisplay {
    /** 中央が表示周波数になるよう並べ替えた512点(dB、表示範囲に制限済み)。 */
    var spectrum by mutableStateOf(FloatArray(0))
    var waterfall by mutableStateOf<ImageBitmap?>(null)
    var fftRef by mutableIntStateOf(-10)
    /** スペクトラムを描いたときの基準レベル(送信中は10、受信中はfftRef)。 */
    var spectrumRef by mutableIntStateOf(-10)
    var hzPerBin by mutableIntStateOf(94)
    var fftBW by mutableIntStateOf(0)
    var bwBarStart by mutableIntStateOf(3)
    var bwBarEnd by mutableIntStateOf(34)
    var bwBarOffset by mutableIntStateOf(0)
    var centreShift by mutableIntStateOf(0)
    /** Sメーター(0〜。55以上がS9超)、または送信中の出力レベル(0〜100)。 */
    var meter by mutableStateOf(0.0)
    var meterIsTx by mutableStateOf(false)
    var meterText by mutableStateOf("S0")
}

/**
 * Langstone V3のGUI(LangstoneGUI_Pluto.c)の動作をAndroidへ移植したもの。iPad版
 * (LangstoneController.swift)を元にし、関数名・処理の順序はできるだけ元のCに合わせている。
 *
 * Pi5版との違い(iPad版と同じ):
 * - ロータリーエンコーダ/マウスの代わりに画面上のダイヤル([turnDial])、左右ボタン([moveDigit])、
 *   LOCKボタン([toggleDialLock])を使う。
 * - GPIO(ハードウェアPTT・CWキー・バンドビット出力)は無い。CWキーは画面のKEYボタン
 *   ([setKeyButton])、バンドビットはPluto GPOへの出力だけ。
 * - 送受信の切り替えはESP32 PTTコントローラ(使う設定のとき)へ通知する。ビーコン・
 *   CWブレークインを含めて送信の開始/終了すべてで通知する。
 *
 * メインスレッドから使うこと。
 */
class LangstoneController(private val context: Context) {
    enum class InputMode { FREQ, SETTINGS, VOLUME, SQUELCH, RIT }
    enum class Popup { NONE, MODE, BAND, BEACON }

    sealed class Status {
        data object Idle : Status()
        data object Connecting : Status()
        data object Running : Status()
        data class Failed(val message: String) : Status()
    }

    val display = LangstoneDisplay()
    val config: LangstoneConfig = LangstoneConfig.load(context)

    var status by mutableStateOf<Status>(Status.Idle); private set
    var freq by mutableStateOf(0.0); private set
    /** 送信中にレピータシフト分ずらして表示する周波数(FMのDUP)。 */
    var displayFreqOverride by mutableStateOf<Double?>(null); private set
    var mode by mutableStateOf(LangstoneMode.USB); private set
    var inputMode by mutableStateOf(InputMode.FREQ); private set
    var popup by mutableStateOf(Popup.NONE); private set
    var popupFirstBand by mutableIntStateOf(0); private set
    var settingNo by mutableStateOf(LangstoneSetting.RX_GAIN); private set
    var setIndex by mutableIntStateOf(0); private set
    var squelch by mutableIntStateOf(20); private set
    var rit by mutableIntStateOf(0); private set
    var dialLock by mutableStateOf(false); private set
    var moni by mutableStateOf(false); private set
    var ptts by mutableStateOf(false); private set
    var transmitting by mutableStateOf(false); private set
    var sendBeacon by mutableIntStateOf(0); private set
    var toneBurstActive by mutableStateOf(false); private set
    var keyButtonDown by mutableStateOf(false); private set
    var sMeterType by mutableIntStateOf(0); private set
    var errorText by mutableStateOf<String?>(null); private set
    /** [config]の中身(配列)が変わったことを画面へ知らせるための番号。 */
    var revision by mutableIntStateOf(0); private set

    val band: Int get() = config.currentBand
    val tuneDigit: Int get() { revision; return config.tuneDigit }
    val volume: Int get() { revision; return config.volume }
    val rxOnly: Boolean get() = config.bandRxOnly[band] != 0

    private val trx = LangstoneNative(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var timerJob: Job? = null
    private var plutoHost = ""
    private var connectStep = ""
    private var connectGeneration = 0
    private var statsCounter = 0
    private var pttControllerHost: String? = null
    private var recordGranted = false
    private var released = false

    private var freqInc = 0.001
    private var lastLOhz = 0L
    private var plutoGpo = 0
    private var firstpass = true
    private var configCounter = 0
    private var fftTimeoutCounter = 0
    private var lastMode = LangstoneMode.USB
    private var keyDownTimer = 0
    private var dotCount = 0
    private val morse = MorseKeyer()
    private var breakInTimer = 0
    private var lastKeyButton = false
    private var keySent = false
    private var squelchGate = 0
    private var lastSquelchGate = 0
    private var sMeter = 0.0
    private var rxFilterLow = 300
    private var rxFilterHigh = 3000

    // FFT(ネイティブ側が保持する最新の1フレームを100回/秒の処理で取り出す)
    private val fftFrame = FloatArray(POINTS)
    private val rowsData = Array(ROWS) { FloatArray(POINTS) { -100f } }
    private var rowHead = 0
    private val palette = makePalette()
    private val waterfallPixels = IntArray(POINTS * ROWS)

    init {
        morse.ident = config.cwIdent.map { it.code }.toMutableList()
    }

    private fun touch() {
        revision++
    }

    // MARK: - 開始・終了

    /** LangstoneGUI_Pluto.cのmain()の初期化部分(startGNURadio/initPluto/initGUI/initSDR)。 */
    fun start(plutoHost: String, pttControllerHost: String?, recordGranted: Boolean) {
        if (status == Status.Connecting || status == Status.Running || released) return
        this.plutoHost = plutoHost
        this.pttControllerHost = pttControllerHost
        this.recordGranted = recordGranted
        status = Status.Connecting
        errorText = null
        firstpass = true
        lastLOhz = 0
        mode = LangstoneMode.of(config.bandMode[band])
        freq = config.bandFreq[band]
        setFreqInc()
        connectStep = "Plutoへの到達確認"
        Log.i(TAG, "start host=$plutoHost")
        startConnectWatchdog()
        scope.launch {
            val reachable = withContext(Dispatchers.IO) { isReachable(plutoHost) }
            if (status != Status.Connecting) return@launch
            if (!reachable) {
                Log.i(TAG, "iiod port unreachable")
                val shown = plutoHost.ifEmpty { "IP未設定" }
                status = Status.Failed(
                    "Pluto($shown)のiiod(TCP 30431)に接続できません。PlutoのIPアドレスと電源・LAN接続を確認してください。",
                )
                return@launch
            }
            connectStep = "libiioでの接続"
            val ok = withContext(Dispatchers.IO) { trx.plutoConnect(plutoHost) }
            if (status != Status.Connecting) return@launch
            if (!ok) displayError("Pluto not responding")
            connectStep = "GNU Radio(gr-iio)での接続"
            startFlowgraph()
        }
    }

    /** 接続処理がどこかで戻ってこない場合に、止まっている段階を表示する。 */
    private fun startConnectWatchdog() {
        connectGeneration += 1
        val generation = connectGeneration
        scope.launch {
            delay(30_000)
            if (generation == connectGeneration && status == Status.Connecting) {
                Log.i(TAG, "connect watchdog fired at step: $connectStep")
                status = Status.Failed("Plutoへの接続が30秒たっても終わりません(${connectStep}の途中)")
            }
        }
    }

    private suspend fun startFlowgraph() {
        val rx = loForCurrentFrequency().toDouble()
        val error = withContext(Dispatchers.IO) { trx.start(plutoHost, rx, rx + 10_000_000) }
        if (released) return
        if (error != null) {
            status = Status.Failed(error)
            return
        }
        Log.i(TAG, "flowgraph running")
        status = Status.Running
        errorText = null
        startAudio()
        initSDR()
        startTimer()
    }

    private fun startAudio() {
        trx.startAudio(recordGranted)?.let { displayError(it) }
        if (!recordGranted) displayError("マイクの使用が許可されていないため、送信音声は無音になります")
    }

    /**
     * 「GOTO SHONAN_LITE」に相当。送信を止め、Pi5版と同じく送信LOを元に戻してから終了する
     * (Langstoneは受信中に送信LOをpowerdownしたまま終わるため、そのままだとDATV送信の
     * 電波が出ない。LangstoneGUI_Pluto.cのボタン6の処理参照)。
     */
    fun shutdown(completion: () -> Unit) {
        stopTimer()
        if (sendBeacon > 0) sendBeacon = 0
        if (transmitting) setTx(false)
        ptts = false
        setBandBits(0)
        config.bandFreq[band] = freq
        config.save(context)
        trx.stopAudio()
        scope.launch {
            withContext(Dispatchers.IO) {
                trx.stop()
                trx.plutoSetTxEnabled(true)
                trx.plutoSetRxEnabled(true)
                trx.plutoFlush()
                trx.plutoDisconnect()
                trx.plutoFlush()
            }
            status = Status.Idle
            completion()
        }
    }

    /** 画面を閉じるときに必ず呼ぶ(shutdownの後)。ネイティブ側を解放する。 */
    fun release() {
        if (released) return
        released = true
        stopTimer()
        scope.cancel()
        val native = trx
        Thread {
            native.stopAudio()
            native.stop()
            native.destroy()
        }.start()
    }

    /** restartGNURadio()。設定画面の「RESTART」と、FFTが2秒届かないときに使う。 */
    fun restartGNURadio() {
        if (status == Status.Connecting) return
        displayError("Restarting GNURadio")
        stopTimer()
        if (transmitting) setTx(false)
        ptts = false
        sendBeacon = 0
        trx.stopAudio()
        status = Status.Connecting
        scope.launch {
            withContext(Dispatchers.IO) { trx.stop() }
            lastLOhz = 0
            startFlowgraph()
        }
    }

    private fun initSDR() {
        firstpass = true
        setBand(band)
        applyMode(mode)
        setVolume(volume)
        applySquelch(squelch)
        applyRit(0)
        setSSBMic(config.ssbMic)
        setFMMic(config.fmMic)
        setAMMic(config.amMic)
        setFreqInc()
        lastLOhz = 0
        applyFreq(freq)
        // main()のループ初回と同じ(「Plutoの初期化に必要らしい」とのPi5版の注記)。
        setTx(true)
        setTx(false)
        firstpass = false
        fftTimeoutCounter = FFT_TIMEOUT * 5
        clearWaterfall()
        touch()
    }

    private fun startTimer() {
        stopTimer()
        timerJob = scope.launch {
            var next = System.nanoTime()
            while (isActive) {
                tick()
                next += 10_000_000L
                val wait = (next - System.nanoTime()) / 1_000_000L
                if (wait > 0) delay(wait) else next = System.nanoTime()
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    /** LangstoneGUI_Pluto.cのmain()のループ(100回/秒)。 */
    private fun tick() {
        processKey()

        statsCounter += 1
        if (statsCounter >= 500) {
            statsCounter = 0
            Log.i(TAG, trx.takeAudioStats() + " tx=$transmitting")
        }

        if (sendBeacon == 2) {
            dotCount += 1
            if (dotCount == 1) setKey(true)
            if (dotCount == 12) setKey(false)
            if (dotCount == 25) dotCount = 0
        }

        if (sendBeacon == 1) {
            if (keyDownTimer > 0) {
                setKey(keyDownTimer > 100 && keyDownTimer < config.cwidKeyDownTime - 100)
                keyDownTimer -= 1
            } else {
                val ret = morse.key()
                if (ret == -1) {
                    keyDownTimer = config.cwidKeyDownTime + 100
                } else {
                    setKey(ret == 1)
                }
            }
        }

        waterfall()

        if (configCounter > 0) {
            configCounter -= 1
            if (configCounter == 0) writeConfig()
        }

        if (fftTimeoutCounter > 0) {
            fftTimeoutCounter -= 1
        } else {
            restartGNURadio()
        }
    }

    private fun writeConfig() {
        config.bandFreq[band] = freq
        config.cwIdent = String(morse.ident.map { it.toChar() }.toCharArray())
        config.save(context)
    }

    private fun displayError(message: String) {
        errorText = message
    }

    // MARK: - 画面操作(processTouch/processMouseに相当)

    fun volButton() {
        changeInputMode(if (inputMode == InputMode.VOLUME) InputMode.FREQ else InputMode.VOLUME)
    }

    fun sqlButton() {
        changeInputMode(if (inputMode == InputMode.SQUELCH) InputMode.FREQ else InputMode.SQUELCH)
    }

    fun ritButton() {
        if (mode == LangstoneMode.FM) return
        changeInputMode(if (inputMode == InputMode.RIT) InputMode.FREQ else InputMode.RIT)
    }

    fun sMeterTapped() {
        sMeterType = if (sMeterType == 0) 1 else 0
    }

    /** 下段のボタン(左から0〜6)。 */
    fun functionButton(index: Int) {
        when (index) {
            0 -> { // BAND / MENU
                if (inputMode == InputMode.FREQ && popup != Popup.BAND) {
                    writeConfig()
                    popup = Popup.BAND
                } else {
                    changeInputMode(InputMode.FREQ)
                    popup = Popup.NONE
                }
            }
            1 -> { // MODE
                if (inputMode == InputMode.FREQ && popup != Popup.MODE) {
                    popup = Popup.MODE
                } else {
                    changeInputMode(InputMode.FREQ)
                    popup = Popup.NONE
                }
            }
            2 -> { // DUP / 1750 / NEXT
                when (inputMode) {
                    InputMode.FREQ -> {
                        if (mode == LangstoneMode.FM && config.bandRepShift[band] != 0.0) {
                            if (ptts && config.bandDuplex[band] > 0) {
                                send1750()
                            } else {
                                config.bandDuplex[band] = if (config.bandDuplex[band] == 0) 1 else 0
                                applyMode(mode)
                            }
                        }
                    }
                    InputMode.SETTINGS -> {
                        val all = LangstoneSetting.entries
                        settingNo = all[(settingNo.ordinal + 1) % all.size]
                        setIndex = 0
                    }
                    else -> changeInputMode(InputMode.FREQ)
                }
            }
            3 -> { // SET / PREV
                when (inputMode) {
                    InputMode.FREQ -> changeInputMode(InputMode.SETTINGS)
                    InputMode.SETTINGS -> {
                        val all = LangstoneSetting.entries
                        settingNo = all[(settingNo.ordinal + all.size - 1) % all.size]
                        setIndex = 0
                    }
                    else -> changeInputMode(InputMode.FREQ)
                }
            }
            4 -> { // MONI / RESTART
                when (inputMode) {
                    InputMode.FREQ -> if (satMode()) applyMoni(!moni)
                    InputMode.SETTINGS -> restartGNURadio()
                    else -> changeInputMode(InputMode.FREQ)
                }
            }
            5 -> { // BEACON
                if (inputMode == InputMode.FREQ) {
                    if (sendBeacon == 0) {
                        popup = if (popup == Popup.BEACON) Popup.NONE else Popup.BEACON
                    } else {
                        setBeacon(0)
                    }
                } else if (inputMode != InputMode.SETTINGS) {
                    changeInputMode(InputMode.FREQ)
                }
            }
            6 -> { // PTT
                if (inputMode == InputMode.FREQ) {
                    applyPtts(!ptts)
                } else if (inputMode != InputMode.SETTINGS) {
                    changeInputMode(InputMode.FREQ)
                }
            }
        }
        touch()
    }

    fun selectMode(m: LangstoneMode) {
        mode = m
        applyMode(m)
        popup = Popup.NONE
        touch()
    }

    fun bandPopupMore() {
        popupFirstBand += 6
        val limit = if (config.bands24 != 0) 23 else 11
        if (popupFirstBand > limit) popupFirstBand = 0
    }

    fun selectBand(b: Int) {
        config.bandFreq[band] = freq
        config.currentBand = b
        setBand(b)
        popup = Popup.NONE
        touch()
    }

    fun selectBeacon(b: Int) {
        setBeacon(b)
        popup = Popup.NONE
        touch()
    }

    /** 周波数表示の桁をタップ(タップした桁をチューニング桁にし、ダイヤルロックを解除)。 */
    fun selectDigit(slot: Int) {
        if (slot !in 0..11) return
        if (inputMode == InputMode.FREQ) applyDialLock(false)
        config.tuneDigit = slot
        setFreqInc()
        applyFreq(freq)
        touch()
    }

    /** スペクトラムをタップするとFFTの表示幅を切り替える。 */
    fun spectrumTapped() {
        config.bandFFTBW[band] += 1
        if (config.bandFFTBW[band] > 3) config.bandFFTBW[band] = 0
        setFFTBW(config.bandFFTBW[band])
        configCounter = CONFIG_DELAY
    }

    /** ダイヤル(ロータリーエンコーダ)を回したとき。processMouse(mbut==128)。 */
    fun turnDial(steps: Int) {
        if (steps == 0) return
        if (inputMode == InputMode.FREQ) {
            if (dialLock) return
            freq += steps * freqInc
            clampFreqToHardware()
            applyFreq(freq)
            return
        }
        val delta = if (steps > 0) 1 else -1
        when (inputMode) {
            InputMode.SETTINGS -> changeSetting(delta)
            InputMode.VOLUME -> setVolume((volume + delta).coerceIn(0, 100))
            InputMode.SQUELCH -> {
                squelch = (squelch + delta).coerceIn(0, 100)
                config.bandSquelch[band][mode.value] = squelch
                applySquelch(squelch)
            }
            InputMode.RIT -> applyRit((rit + delta * 10).coerceIn(-MAX_RIT, MAX_RIT))
            InputMode.FREQ -> Unit
        }
        touch()
    }

    /** 左右ボタン(マウスの左/右ボタン)。チューニング桁、またはCW ID/バンドビットの編集位置を動かす。 */
    fun moveDigit(direction: Int) {
        if (inputMode == InputMode.SETTINGS &&
            (settingNo == LangstoneSetting.CWID || settingNo == LangstoneSetting.BAND_BITS_RX ||
                settingNo == LangstoneSetting.BAND_BITS_TX)
        ) {
            val maxIndex = if (settingNo == LangstoneSetting.CWID) 39 else 7
            setIndex = (setIndex + direction).coerceIn(0, maxIndex)
            if (settingNo == LangstoneSetting.CWID) padIdentForEditing()
            touch()
            return
        }
        var d = config.tuneDigit + direction
        if (direction < 0) {
            if (d < 0) d = 0
            if (d == 5) d = 4
            if (d == 9) d = 8
        } else {
            if (d > 11) d = 11
            if (d == 5) d = 6
            if (d == 9) d = 10
        }
        config.tuneDigit = d
        setFreqInc()
        applyFreq(freq)
        touch()
    }

    /** 中ボタン(両ボタン同時押し)。 */
    fun toggleDialLock() {
        applyDialLock(!dialLock)
    }

    /** 画面のCWキー(Pi5版のGPIO18のキー入力に相当)。 */
    fun setKeyButton(down: Boolean) {
        keyButtonDown = down
    }

    /** CW IDを文字列で直接入力する(ダイヤルで1文字ずつ選ぶ代わりに使える)。 */
    fun setCWIdent(text: String) {
        val allowed = text.uppercase().mapNotNull { ch ->
            val v = ch.code
            when {
                v == 32 -> 95
                v in 65..90 || v in 48..57 || v == 47 || v == 95 -> v
                else -> null
            }
        }
        morse.ident = allowed.take(40).toMutableList()
        configCounter = CONFIG_DELAY
        touch()
    }

    val cwIdentText: String
        get() = String(morse.ident.map { it.toChar() }.toCharArray()).replace('_', ' ')

    // MARK: - LangstoneGUI_Pluto.cの各関数

    private fun changeInputMode(m: InputMode) {
        if (inputMode == InputMode.SETTINGS) writeConfig()
        inputMode = m
        if (m == InputMode.FREQ) applyFreq(freq)
        if (m == InputMode.SETTINGS) {
            popup = Popup.NONE
            setIndex = 0
        }
    }

    private fun setFreqInc() {
        freqInc = when (config.tuneDigit) {
            0 -> 10000.0
            1 -> 1000.0
            2 -> 100.0
            3 -> 10.0
            4 -> 1.0
            5, 6 -> { config.tuneDigit = 6; 0.1 }
            7 -> 0.01
            8 -> 0.001
            9, 10 -> { config.tuneDigit = 10; 0.0001 }
            else -> 0.00001
        }
    }

    private fun clampFreqToHardware() {
        val off = config.bandRxOffset[band]
        val harm = config.bandRxHarmonic[band].toDouble()
        if ((freq + off) / harm < MIN_HW_FREQ) freq = (MIN_HW_FREQ - off) / harm
        if ((freq + off) / harm > MAX_HW_FREQ) freq = (MAX_HW_FREQ - off) / harm
    }

    private fun setBand(b: Int) {
        freq = config.bandFreq[b]
        applyFreq(freq)
        mode = LangstoneMode.of(config.bandMode[b])
        applyMode(mode)
        setFFTBW(config.bandFFTBW[b])
        setBandBits(config.bandBitsRx[b])
        squelch = config.bandSquelch[b][mode.value]
        applySquelch(squelch)
        setCTCSS(config.bandCTCSS[b])
        display.fftRef = config.bandFFTRef[b]
        trx.plutoSetTxAttenuation(config.bandTxAtt[b])
        trx.plutoSetRxGain(config.bandRxGain[b], maxGain(freq))
        configCounter = CONFIG_DELAY
    }

    private fun applyPtts(on: Boolean) {
        if (on && rxOnly) return
        if (on) {
            ptts = true
            setTx(true)
        } else {
            ptts = false
            if (sendBeacon > 0) {
                setBeacon(0)
                applyMode(mode)
            }
            setTx(false)
        }
    }

    private fun setBeacon(b: Int) {
        if (b > 0 && rxOnly) return
        if (b > 0) {
            sendBeacon = b
            morse.reset()
            keyDownTimer = 300
            lastMode = mode
            mode = LangstoneMode.CW
            applyMode(mode)
            if (lastMode == LangstoneMode.USB) freq += 0.0008
            if (lastMode == LangstoneMode.LSB) freq -= 0.0008
            applyFreq(freq)
            if (!ptts) setTx(true)
            ptts = true
        } else {
            sendBeacon = 0
            ptts = false
            setTx(false)
            setKey(false)
            mode = lastMode
            applyMode(mode)
            if (mode == LangstoneMode.USB) freq -= 0.0008
            if (mode == LangstoneMode.LSB) freq += 0.0008
            applyFreq(freq)
        }
    }

    private fun setVolume(vol: Int) {
        config.volume = vol
        trx.setAFGain(vol)
        configCounter = CONFIG_DELAY
    }

    private fun applySquelch(sql: Int) {
        squelch = sql
        configCounter = CONFIG_DELAY
    }

    private fun applyRit(ri: Int) {
        if (mode == LangstoneMode.FM || mode == LangstoneMode.AM) return
        rit = ri
        applyFreq(freq)
    }

    private fun setSSBMic(mic: Int) = trx.setMicGain(mic)
    private fun setFMMic(mic: Int) = trx.setFMMic(mic)
    private fun setAMMic(mic: Int) = trx.setAMMic(mic)

    private fun setCTCSS(index: Int) {
        trx.setCTCSS(LangstoneConfig.ctcssTones[index])
    }

    private fun setKey(on: Boolean) {
        if (on == keySent) return
        keySent = on
        trx.setKey(on)
    }

    private fun setMute(m: Boolean) {
        trx.setRxMute(m || squelchGate == 0)
    }

    private fun setRxFilter(low: Int, high: Int) {
        trx.setRxFilter(low, high)
        rxFilterLow = low
        rxFilterHigh = high
    }

    private fun setTxFilter(low: Int, high: Int) {
        trx.setTxFilter(low, high)
    }

    private fun setFFTBW(bw: Int) {
        trx.setFFTSel(bw)
        display.fftBW = bw
        display.hzPerBin = intArrayOf(94, 47, 23, 12)[bw.coerceIn(0, 3)]
    }

    private fun applyMode(md: LangstoneMode) {
        config.bandMode[band] = md.value
        mode = md
        trx.setMode(md.value)
        val low = config.bandSSBFiltLow[band]
        val high = config.bandSSBFiltHigh[band]
        when (md) {
            LangstoneMode.USB -> { setTxFilter(300, 3000); setRxFilter(low, high) }
            LangstoneMode.LSB -> { setTxFilter(-3000, -300); setRxFilter(-high, -low) }
            LangstoneMode.CW -> { setRxFilter(low, high); setTxFilter(-100, 100) }
            LangstoneMode.CWN -> { setRxFilter(600, 1000); setTxFilter(-100, 100) }
            LangstoneMode.FM -> { setRxFilter(-7500, 7500); setTxFilter(-7500, 7500) }
            LangstoneMode.AM -> { setRxFilter(-3000, 3000); setTxFilter(-3000, 3000) }
        }
        applyFreq(freq)
        if (inputMode == InputMode.RIT && (md == LangstoneMode.FM || md == LangstoneMode.AM)) inputMode = InputMode.FREQ
        rit = 0
        applyFreq(freq)
        squelch = config.bandSquelch[band][mode.value]
        applySquelch(squelch)
        configCounter = CONFIG_DELAY
    }

    private fun setTx(on: Boolean) {
        if (on && rxOnly) {
            trx.plutoSetTxEnabled(false)
            return
        }
        if (on && !transmitting) {
            if (!firstpass) {
                setBandBits(config.bandBitsTx[band])
                plutoGpo = plutoGpo or 0x10
                trx.plutoSetGpo(plutoGpo)
                notifyPTTController(true)
            }
            setHwTxFreq(freq)
            if (mode == LangstoneMode.FM && config.bandDuplex[band] == 1) {
                displayFreqOverride = freq + config.bandRepShift[band]
            }
            trx.plutoSetTxEnabled(true)
            if (!moni) setMute(true)
            if (!satMode()) {
                sMeter = 0.0
                setHwRxFreq(freq + 10.0)
                trx.plutoSetRxEnabled(false)
                clearWaterfall()
            }
            trx.setPTT(true)
            transmitting = true
        } else if (!on && transmitting) {
            if (!satMode()) {
                sMeter = 0.0
                clearWaterfall()
            }
            trx.setPTT(false)
            setMute(false)
            setHwTxFreq(freq + 10.0)
            trx.plutoSetTxEnabled(false)
            trx.plutoSetRxEnabled(true)
            setHwRxFreq(freq)
            displayFreqOverride = null
            transmitting = false
            setBandBits(config.bandBitsRx[band])
            plutoGpo = plutoGpo and 0xEF
            trx.plutoSetGpo(plutoGpo)
            if (!firstpass) notifyPTTController(false)
        }
    }

    private fun notifyPTTController(on: Boolean) {
        val host = pttControllerHost ?: return
        if (on && rxOnly) return
        scope.launch(Dispatchers.IO) {
            Esp32PttClient.notifyTx(host, on).onFailure {
                Log.w(TAG, "ESP32 PTT通知(${if (on) "on" else "off"})に失敗しました: ${it.message}")
            }
        }
    }

    /** setHwRxFreq()で使うPlutoの受信LO周波数(Hz)。起動時のフローグラフの初期値に使う。 */
    private fun loForCurrentFrequency(): Long {
        var frRx = freq + config.bandRxOffset[band]
        if (config.bandRxHarmonic[band] < 2) frRx = frRx.coerceIn(MIN_HW_FREQ, MAX_HW_FREQ)
        val rxHz = maxOf((frRx * 1_000_000).roundToLong(), 69_900_000L)
        var lo = if (rxHz < 70_100_000L) 70_000_000L else rxHz - ((rxHz % 100_000L) + 50_000L)
        if (config.bandRxHarmonic[band] > 1) lo /= config.bandRxHarmonic[band]
        return lo
    }

    private fun setHwRxFreq(fr: Double) {
        var frRx = fr + config.bandRxOffset[band]
        if (config.bandRxHarmonic[band] < 2) frRx = frRx.coerceIn(MIN_HW_FREQ, MAX_HW_FREQ)
        var rxHz = (frRx * 1_000_000).roundToLong()
        if (rxHz < 69_900_000L) rxHz = 69_900_000L
        var offset: Long
        var lo: Long
        if (rxHz < 70_100_000L) {
            // 70.1MHz未満は±100kHzの範囲で受ける特例(元のコードと同じ)。
            offset = rxHz - 70_000_000L
            lo = 70_000_000L
        } else {
            // サンプリング帯域の+50〜+150kHz側だけを使い、DC付近の盛り上がりを避ける。
            offset = (rxHz % 100_000L) + 50_000L
            lo = rxHz - offset
        }
        if (config.bandRxHarmonic[band] > 1) lo /= config.bandRxHarmonic[band]
        offset += rit
        if (mode.isCW) offset -= 800 // CWの800Hzトーン分
        if (lo != lastLOhz) {
            trx.plutoSetRxFrequency(lo)
            lastLOhz = lo
        }
        trx.setRxOffset(offset.toDouble())
    }

    private fun setHwTxFreq(fr: Double) {
        var frTx = fr + config.bandTxOffset[band]
        if (config.bandTxHarmonic[band] < 2) frTx = frTx.coerceIn(MIN_HW_FREQ, MAX_HW_FREQ)
        if (mode == LangstoneMode.FM && config.bandDuplex[band] == 1) frTx += config.bandRepShift[band]
        var txHz = (frTx * 1_000_000).roundToLong()
        if (config.bandTxHarmonic[band] > 1) txHz /= config.bandTxHarmonic[band]
        trx.plutoSetTxFrequency(txHz)
    }

    private fun applyFreq(fr: Double) {
        if (ptts) setHwTxFreq(fr) else setHwRxFreq(fr)
        if (!satMode() && moni) applyMoni(false)
        configCounter = CONFIG_DELAY
    }

    /** C言語のabs()(引数をintへ切り捨ててから絶対値)と同じ判定にする。 */
    private fun cAbs(x: Double): Int = abs(x.toInt())

    fun satMode(): Boolean {
        val tx = config.bandTxOffset[band]
        val rx = config.bandRxOffset[band]
        return cAbs(tx - rx) > 1 && rx != 0.0 && config.bandRxHarmonic[band] < 2 && config.bandTxHarmonic[band] < 2
    }

    fun txvtrMode(): Boolean {
        val tx = config.bandTxOffset[band]
        val rx = config.bandRxOffset[band]
        return cAbs(tx - rx) < 1 && cAbs(tx) > 1
    }

    fun splitMode(): Boolean = cAbs(config.bandTxOffset[band]) > 0 && config.bandRxOffset[band] == 0.0

    fun multMode(): Boolean = config.bandRxHarmonic[band] > 1 || config.bandTxHarmonic[band] > 1

    private fun applyDialLock(on: Boolean) {
        dialLock = on
    }

    private fun applyMoni(on: Boolean) {
        if (on) {
            setMute(false)
            moni = true
        } else {
            if (ptts) setMute(true)
            moni = false
        }
    }

    private fun send1750() {
        trx.setToneBurst(true)
        toneBurstActive = true
        scope.launch {
            delay(500)
            trx.setToneBurst(false)
            toneBurstActive = false
        }
    }

    /** GPIOが無いため、Pluto GPOへのコピー部分だけ。 */
    private fun setBandBits(b: Int) {
        if (config.bandBitsToPluto == 1) {
            plutoGpo = if (b and 0x01 != 0) plutoGpo or 0x20 else plutoGpo and 0xDF
            plutoGpo = if (b and 0x02 != 0) plutoGpo or 0x40 else plutoGpo and 0xBF
            plutoGpo = if (b and 0x04 != 0) plutoGpo or 0x80 else plutoGpo and 0x7F
        } else {
            plutoGpo = plutoGpo and 0x1F
        }
        trx.plutoSetGpo(plutoGpo)
    }

    fun minGain(f: Double): Int {
        val rxfreq = (f + config.bandRxOffset[band]) / config.bandRxHarmonic[band]
        if (rxfreq < 1300) return -1
        if (rxfreq < 4000) return -3
        return -10
    }

    fun maxGain(f: Double): Int {
        val rxfreq = (f + config.bandRxOffset[band]) / config.bandRxHarmonic[band]
        if (rxfreq < 1300) return 73
        if (rxfreq < 4000) return 71
        return 62
    }

    /**
     * processGPIO()のキー入力部分(画面のKEYボタン用)。CWではキーを押すと自動で送信になり、
     * 離してからbreakInTime(×10ms)経つと受信へ戻る。
     */
    private fun processKey() {
        if (keyButtonDown != lastKeyButton) {
            setKey(keyButtonDown)
            lastKeyButton = keyButtonDown
        }
        if (!mode.isCW || sendBeacon != 0) return
        if (keyButtonDown) {
            if (!ptts) setTx(true)
            breakInTimer = config.breakInTime
        } else if (breakInTimer > 0 && !ptts) {
            breakInTimer -= 1
            if (breakInTimer == 0) setTx(false)
        }
    }

    // MARK: - 設定(changeSetting/displaySetting)

    private fun changeSetting(d: Int) {
        val b = band
        when (settingNo) {
            LangstoneSetting.SSB_MIC -> {
                config.ssbMic = (config.ssbMic + d).coerceIn(0, 100)
                setSSBMic(config.ssbMic)
            }
            LangstoneSetting.FM_MIC -> {
                config.fmMic = (config.fmMic + d).coerceIn(0, 100)
                setFMMic(config.fmMic)
            }
            LangstoneSetting.AM_MIC -> {
                config.amMic = (config.amMic + d).coerceIn(0, 100)
                setAMMic(config.amMic)
            }
            LangstoneSetting.REP_SHIFT -> {
                config.bandRepShift[b] += d * freqInc
                applyFreq(freq)
            }
            LangstoneSetting.CTCSS -> {
                config.bandCTCSS[b] = (config.bandCTCSS[b] + d).coerceIn(0, LangstoneConfig.ctcssTones.size - 1)
                setCTCSS(config.bandCTCSS[b])
            }
            LangstoneSetting.RX_OFFSET -> {
                config.bandRxOffset[b] = (config.bandRxOffset[b] - d * freqInc).coerceIn(-99999.9, 99999.9)
                freq += d * freqInc
                if (freq + config.bandRxOffset[b] > MAX_HW_FREQ) freq -= (freq + config.bandRxOffset[b]) - MAX_HW_FREQ
                if (freq + config.bandRxOffset[b] < MIN_HW_FREQ) freq += MIN_HW_FREQ - (freq + config.bandRxOffset[b])
                applyFreq(freq)
            }
            LangstoneSetting.RX_HARMONIC -> {
                config.bandRxHarmonic[b] = if (d > 0) 5 else 1
                applyFreq(freq)
            }
            LangstoneSetting.TX_OFFSET -> {
                config.bandTxOffset[b] = (config.bandTxOffset[b] - d * freqInc).coerceIn(-99999.9, 99999.9)
                freq += d * freqInc
                if (freq + config.bandTxOffset[b] > MAX_HW_FREQ) freq -= (freq + config.bandTxOffset[b]) - MAX_HW_FREQ
                if (freq + config.bandTxOffset[b] < MIN_HW_FREQ) freq += MIN_HW_FREQ - (freq + config.bandTxOffset[b])
                applyFreq(freq)
            }
            LangstoneSetting.TX_HARMONIC -> {
                var h = (config.bandTxHarmonic[b] + d).coerceIn(1, 5)
                if (h == 3 || h == 4) h = if (d > 0) 5 else 2
                config.bandTxHarmonic[b] = h
                applyFreq(freq)
            }
            LangstoneSetting.BAND_BITS_RX -> {
                config.bandBitsRx[b] = config.bandBitsRx[b] xor (0x80 shr setIndex)
                setBandBits(config.bandBitsRx[b])
            }
            LangstoneSetting.BAND_BITS_TX -> {
                config.bandBitsTx[b] = config.bandBitsTx[b] xor (0x80 shr setIndex)
            }
            LangstoneSetting.BAND_BITS_TO_PLUTO -> config.bandBitsToPluto = if (d > 0) 1 else 0
            LangstoneSetting.FFT_REF -> {
                config.bandFFTRef[b] = (config.bandFFTRef[b] + d).coerceIn(-80, 30)
                display.fftRef = config.bandFFTRef[b]
            }
            LangstoneSetting.TX_ATT -> {
                config.bandTxAtt[b] = (config.bandTxAtt[b] + d).coerceIn(-89, 0)
                trx.plutoSetTxAttenuation(config.bandTxAtt[b])
            }
            LangstoneSetting.RX_GAIN -> {
                var g = if (config.bandRxGain[b] == 100) maxGain(freq) + 1 + d else config.bandRxGain[b] + d
                if (g < minGain(freq)) g = minGain(freq)
                if (g > maxGain(freq)) g = 100
                config.bandRxGain[b] = g
                trx.plutoSetRxGain(g, maxGain(freq))
            }
            LangstoneSetting.S_ZERO -> config.bandSmeterZero[b] = (config.bandSmeterZero[b] + d).coerceIn(-140.0, -30.0)
            LangstoneSetting.SSB_FILT_LOW -> {
                config.bandSSBFiltLow[b] = (config.bandSSBFiltLow[b] + d * 10).coerceIn(0, 1000)
                applyMode(mode)
            }
            LangstoneSetting.SSB_FILT_HIGH -> {
                config.bandSSBFiltHigh[b] = (config.bandSSBFiltHigh[b] + d * 10).coerceIn(1000, 5000)
                applyMode(mode)
            }
            LangstoneSetting.CW_CARRIER -> config.cwidKeyDownTime = (config.cwidKeyDownTime + d * 100).coerceIn(0, 12000)
            LangstoneSetting.CWID -> {
                padIdentForEditing()
                var c = morse.ident[setIndex] + d
                if (d > 0) {
                    if (c > 95) c = 47
                    if (c in 58..64) c = 65
                    if (c > 90) c = 95
                } else {
                    if (c < 47) c = 95
                    if (c in 91..94) c = 90
                    if (c in 58..64) c = 57
                }
                morse.ident[setIndex] = c
                trimIdent()
            }
            LangstoneSetting.BREAK_IN_TIME -> config.breakInTime = (config.breakInTime + d).coerceIn(50, 200)
            LangstoneSetting.BANDS24 -> config.bands24 = if (d > 0) 1 else 0
        }
        configCounter = CONFIG_DELAY
    }

    /** 編集位置がID文字列の末尾を越えたら空白(「_」)で埋める(displaySettingのCWID部分)。 */
    private fun padIdentForEditing() {
        while (morse.ident.size <= setIndex) morse.ident.add(95)
    }

    /** 末尾の空白を取り除く(writeConfigの「二重の空白で打ち切る」処理の代わり)。 */
    private fun trimIdent() {
        while (morse.ident.size > 1 && morse.ident.last() == 95 && morse.ident.size - 1 > setIndex) {
            morse.ident.removeAt(morse.ident.size - 1)
        }
    }

    fun settingValueText(se: LangstoneSetting): String {
        revision
        val b = band
        return when (se) {
            LangstoneSetting.SSB_MIC -> "${config.ssbMic}"
            LangstoneSetting.FM_MIC -> "${config.fmMic}"
            LangstoneSetting.AM_MIC -> "${config.amMic}"
            LangstoneSetting.REP_SHIFT -> String.format("%.5f", config.bandRepShift[b])
            LangstoneSetting.CTCSS -> String.format("%.1f Hz", LangstoneConfig.ctcssTones[config.bandCTCSS[b]] / 10.0)
            LangstoneSetting.RX_OFFSET ->
                String.format("%.5f  Rx Freq= %.5f", config.bandRxOffset[b], freq + config.bandRxOffset[b])
            LangstoneSetting.RX_HARMONIC -> "X${config.bandRxHarmonic[b]}"
            LangstoneSetting.TX_OFFSET ->
                String.format("%.5f  Tx Freq= %.5f", config.bandTxOffset[b], freq + config.bandTxOffset[b])
            LangstoneSetting.TX_HARMONIC -> "X${config.bandTxHarmonic[b]}"
            LangstoneSetting.BAND_BITS_RX -> bitsText(config.bandBitsRx[b])
            LangstoneSetting.BAND_BITS_TX -> bitsText(config.bandBitsTx[b])
            LangstoneSetting.BAND_BITS_TO_PLUTO -> if (config.bandBitsToPluto == 1) "Yes" else "No"
            LangstoneSetting.FFT_REF -> "${config.bandFFTRef[b]}"
            LangstoneSetting.TX_ATT -> "${config.bandTxAtt[b]} dB"
            LangstoneSetting.RX_GAIN -> if (config.bandRxGain[b] > maxGain(freq)) "Auto" else "${config.bandRxGain[b]} dB"
            LangstoneSetting.S_ZERO -> String.format("%.0f dB", config.bandSmeterZero[b])
            LangstoneSetting.SSB_FILT_LOW -> "${config.bandSSBFiltLow[b]} Hz"
            LangstoneSetting.SSB_FILT_HIGH -> "${config.bandSSBFiltHigh[b]} Hz"
            LangstoneSetting.CW_CARRIER -> "${config.cwidKeyDownTime / 100} Secs"
            LangstoneSetting.CWID -> cwIdentText
            LangstoneSetting.BREAK_IN_TIME -> "${config.breakInTime * 10} ms"
            LangstoneSetting.BANDS24 -> if (config.bands24 == 0) "No" else "Yes"
        }
    }

    private fun bitsText(v: Int): String = (0 until 8).joinToString("") { if (v and (0x80 shr it) != 0) "1" else "0" }

    // MARK: - 周波数表示

    /** 12文字の周波数表示(例「  432.200.00」)。各文字の位置がチューニング桁(tuneDigit)に対応する。 */
    val frequencyText: String
        get() {
            val fr = (displayFreqOverride ?: freq) + 0.0000001
            val hz = (fr * 1_000_000).toLong() + 100_000_000_000L
            val d = hz.toString()
            fun digit(i: Int): Char = if (i < d.length) d[i] else '0'
            val sb = StringBuilder()
            sb.append(if (digit(1) > '0') digit(1) else ' ')
            sb.append(if (digit(1) > '0' || digit(2) > '0') digit(2) else ' ')
            sb.append(if (digit(1) > '0' || digit(2) > '0' || digit(3) > '0') digit(3) else ' ')
            sb.append(digit(4)).append(digit(5)).append('.')
            sb.append(digit(6)).append(digit(7)).append(digit(8)).append('.')
            sb.append(digit(9)).append(digit(10))
            return sb.toString()
        }

    val modeLabel: String
        get() {
            revision
            return mode.label + if (mode == LangstoneMode.FM && config.bandDuplex[band] == 1) " DUP" else ""
        }

    val rigModeLabel: String
        get() {
            revision
            return when {
                multMode() -> "MULT"
                txvtrMode() -> "XVTR"
                satMode() -> "SAT"
                splitMode() -> "SPLIT"
                else -> ""
            }
        }

    val ritText: String get() = if (rit == 0) "0.00" else String.format("%+3.2f", rit / 1000.0)

    val showDupButton: Boolean get() { revision; return mode == LangstoneMode.FM && config.bandRepShift[band] != 0.0 }

    val dupActive: Boolean get() { revision; return config.bandDuplex[band] > 0 }

    fun bandButtonLabel(b: Int): String = "${config.bandFreq[b].toInt()}"

    // MARK: - スペクトラム・ウォーターフォール(waterfall/S_Meter/P_Meter)

    private fun clearWaterfall() {
        for (r in 0 until ROWS) rowsData[r].fill(-100f)
        trx.clearFFT()
    }

    private fun waterfall() {
        val useTx = transmitting && !satMode()
        if (!trx.takeFFT(useTx, fftFrame)) return
        fftTimeoutCounter = FFT_TIMEOUT
        val fftref = if (useTx) 10 else display.fftRef

        // 中央が表示周波数になるよう並べ替えて先頭行に入れる
        rowHead = (rowHead + ROWS - 1) % ROWS
        val half = POINTS / 2
        val row0 = rowsData[rowHead]
        for (p in 0 until POINTS) row0[p] = if (p < half) fftFrame[p + half] else fftFrame[p - half]

        val hzPerBin = display.hzPerBin
        val bwbaroffset = if (mode.isCW && transmitting && !satMode()) 800 / hzPerBin else 0
        val bwBarStart = maxOf(rxFilterLow / hzPerBin, -255)
        val bwBarEnd = minOf(rxFilterHigh / hzPerBin, 255)

        // 受信帯域内の最大値からSメーター値を求める
        var peak = -200f
        val from = maxOf(half + bwBarStart - bwbaroffset, 0)
        val to = minOf(half + bwBarEnd - bwbaroffset, POINTS)
        for (p in from until to) if (row0[p] > peak) peak = row0[p]

        val baselevel = fftref - 80
        display.spectrum = FloatArray(POINTS) { row0[it].coerceIn(baselevel.toFloat(), fftref.toFloat()) }
        display.spectrumRef = fftref
        display.waterfall = renderWaterfall(fftref, baselevel)
        display.bwBarStart = bwBarStart
        display.bwBarEnd = bwBarEnd
        display.bwBarOffset = bwbaroffset
        display.centreShift = if (mode.isCW && !transmitting && !satMode()) 800 / hzPerBin else 0

        if (!transmitting || satMode()) sMeterUpdate(peak.toDouble()) else pMeterUpdate(peak.toDouble())
    }

    private fun sMeterUpdate(rawPeak: Double) {
        var peak = rawPeak - config.bandSmeterZero[band]
        if (config.bandRxGain[band] == 100) {
            // RF AGCで下がったゲインの分を補正する
            peak += (maxGain(freq) - trx.plutoCurrentRxGain()).toDouble()
            trx.plutoRefreshRxGain()
        }
        if (peak < 0) peak = 0.0
        if (peak >= sMeter) {
            sMeter = peak
        } else if (sMeter > 0) {
            sMeter -= 2
        }
        var sValue: Int
        var dbOver = 0
        if (sMeter < 55) {
            sValue = (sMeter / 6).toInt()
        } else {
            sValue = 9
            dbOver = (sMeter - 54).toInt()
        }
        display.meter = sMeter
        display.meterIsTx = false
        display.meterText = if (sMeterType == 0) {
            if (dbOver > 0) "S$sValue+${dbOver}dB" else "S$sValue"
        } else {
            String.format("%.0f dB", sMeter)
        }

        if (sMeter < squelch && squelch > 0) {
            squelchGate = 0
            if (squelchGate != lastSquelchGate) {
                setMute(true)
                lastSquelchGate = squelchGate
            }
        } else {
            squelchGate = 1
            if (squelchGate != lastSquelchGate) {
                setMute(false)
                lastSquelchGate = squelchGate
            }
        }
    }

    private fun pMeterUpdate(rawPeak: Double) {
        val peak = ((rawPeak + 50) * 2.1).coerceIn(0.0, 100.0)
        if (peak >= sMeter) {
            sMeter = peak
        } else if (sMeter > 0) {
            sMeter -= when {
                mode.isCW -> 10.0
                mode == LangstoneMode.USB || mode == LangstoneMode.LSB -> 3.0
                else -> 0.5
            }
        }
        if (sMeter < 0) sMeter = 0.0
        display.meter = sMeter
        display.meterIsTx = true
        display.meterText = "Tx Level"
    }

    private fun renderWaterfall(fftref: Int, baselevel: Int): ImageBitmap {
        val scaling = 255f / (fftref - baselevel)
        val base = baselevel.toFloat()
        val top = fftref.toFloat()
        for (r in 0 until ROWS) {
            val row = rowsData[(rowHead + r) % ROWS]
            val rowOffset = r * POINTS
            for (p in 0 until POINTS) {
                val v = row[p].coerceIn(base, top)
                val level = ((v - base) * scaling).toInt().coerceIn(0, 255)
                waterfallPixels[rowOffset + p] = palette[level]
            }
        }
        val bmp = Bitmap.createBitmap(waterfallPixels, POINTS, ROWS, Bitmap.Config.ARGB_8888)
        return bmp.asImageBitmap()
    }

    companion object {
        private const val TAG = "Langstone"
        const val POINTS = 512
        const val ROWS = 90
        private const val MIN_HW_FREQ = 69.9
        private const val MAX_HW_FREQ = 5999.99999
        private const val CONFIG_DELAY = 500
        private const val FFT_TIMEOUT = 200
        private const val MAX_RIT = 9990

        /** PlutoのiiodのTCPポートへ届くかを確かめる(libiioは届かない相手に長く待たされることがある)。 */
        private fun isReachable(host: String, timeoutMs: Int = 3000): Boolean {
            if (host.isEmpty()) return false
            return runCatching {
                Socket().use { it.connect(InetSocketAddress(host, 30431), timeoutMs) }
                true
            }.getOrDefault(false)
        }

        /** gen_palette(黒→青→緑→黄→赤の4段階、256色)。ARGBで返す。 */
        private fun makePalette(): IntArray {
            val colours = arrayOf(
                intArrayOf(0, 0, 0), intArrayOf(0, 0, 255), intArrayOf(0, 255, 0),
                intArrayOf(255, 255, 0), intArrayOf(255, 0, 0),
            )
            val grads = 4
            val step = 256 / grads
            val palette = IntArray(256)
            for (g in 0 until grads) {
                for (i in 0 until step) {
                    val t = i.toDouble() / step
                    val rgb = IntArray(3) { c ->
                        (colours[g][c] + (colours[g + 1][c] - colours[g][c]) * t).toInt().coerceIn(0, 255)
                    }
                    palette[g * step + i] = (0xFF shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
                }
            }
            return palette
        }
    }
}
