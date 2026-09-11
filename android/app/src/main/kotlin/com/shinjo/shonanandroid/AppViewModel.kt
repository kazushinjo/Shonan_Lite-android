package com.shinjo.shonanandroid

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.shinjo.shonanandroid.core.AppSettings
import com.shinjo.shonanandroid.diagnostics.FileLogger
import com.shinjo.shonanandroid.dvbs2.Dvbs2TestRunner
import com.shinjo.shonanandroid.dvbs2.AfcNativeSession
import com.shinjo.shonanandroid.dvbs2.PlutoTuner
import com.shinjo.shonanandroid.rx.RxController
import com.shinjo.shonanandroid.tx.TxController
import com.shinjo.shonanandroid.tx.TxNetworkStats
import com.shinjo.shonanandroid.net.NetworkBinder
import com.shinjo.shonanandroid.net.PlutoSettingsWriter
import com.shinjo.shonanandroid.net.PlutoUdpTsController
import androidx.camera.core.Preview
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AfcMeasurement(val frequencyHz: Long, val rssiDb: Double)

/** libiio IIODの既定TCPポート。 */
private const val IIOD_PORT = 30431

/** 相手局検索でRSSI変動をピークと認めるしきい値(dB)。Shonan_Lite-pi5と同じ値。 */
private const val AFC_PEAK_THRESHOLD_DB = 3.0

/**
 * libiioの`iio_create_context`は到達不能なIPに対してネイティブクラッシュ(SIGSEGV)する
 * ことがあるため、ネイティブセッションを開く前にTCP到達性を確認する。
 */
private suspend fun isPlutoReachable(ip: String, timeoutMs: Int = 2000): Boolean =
    withContext(Dispatchers.IO) {
        try {
            Socket().use { it.connect(InetSocketAddress(ip, IIOD_PORT), timeoutMs) }
            true
        } catch (e: IOException) {
            false
        }
    }

/**
 * アプリ全体の設定とTx/Rxコントローラを保持する -- iOS版`AppViewModel`(実質
 * `AppSettings`+`TxSessionController`+`RxSessionController`の保有側)に相当。
 *
 * [txController]/[rxController]はここで保有し[ProcessLifecycleOwner]に束縛する
 * (Compose destinationのライフサイクルではなく)ことで、画面遷移してもカメラ/
 * 送受信セッションが破棄されないようにする(他プロジェクトの`AppViewModel`と同じ理由)。
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    init {
        // インターネット未接続のWi-Fi(Pluto運用時の典型)でもPlutoとの通信が
        // OSにルーティングされるよう、起動時にプロセスをWi-Fiへ明示バインドする。
        // TODO(デバッグ中): SSH(JSch/sshj)だけがTCP接続直後のバナー読み取りで無応答に
        // なる原因切り分けのため、一時的に無効化して検証する。
        // NetworkBinder.bindToWifi(getApplication())
    }

    var settings by mutableStateOf(SettingsStore.load(getApplication()))
        private set

    fun updateSettings(update: (AppSettings) -> AppSettings) {
        settings = update(settings)
        SettingsStore.save(getApplication(), settings)
    }

    val txController = TxController(getApplication(), ProcessLifecycleOwner.get())
    var isTransmitting by mutableStateOf(false)
    // startTX()の準備処理(HTTP/SSH通信で数秒かかる)が完了する前にボタンを連打すると、
    // 前の試行がまだ結果不明のまま次のコルーチンが並行して走ってしまうためのガード。
    var isPreparingTx by mutableStateOf(false)
        private set
    private val _txError = mutableStateOf<String?>(null)
    var txError: String?
        get() = _txError.value
        set(value) {
            _txError.value = value
            value?.let { FileLogger.log("TX_ERROR", it) }
        }

    /** 実運用の送信経路はPluto向けUDP-TS送信に統一する。 */
    val activeTxPreview: Preview get() = txController.preview
    val activeTxStats: TxNetworkStats get() = txController.stats

    /**
     * Pluto起動時リブート([[HomeScreen.runPlutoStartupReboot]])をアプリ起動後に一度だけ
     * 実行済みにするためのフラグ。Home画面のComposableは他画面へ遷移するたびにコンポジションから
     * 外れて`remember`状態が失われるため、このフラグを画面をまたいで生存するViewModel側に持たせないと、
     * Rx/Tx画面からホームへ戻るたびに毎回Plutoへreboot要求が飛び、復旧待ちの全画面表示で
     * 「ホームへ戻るボタンが効かない」ように見えるバグになる。
     */
    var didRunStartupPlutoReboot = false

    val rxController = RxController(getApplication())
    var isReceiving by mutableStateOf(false)
    var isPreparingRx by mutableStateOf(false)
        private set
    private val _rxError = mutableStateOf<String?>(null)
    var rxError: String?
        get() = _rxError.value
        set(value) {
            _rxError.value = value
            value?.let { FileLogger.log("RX_ERROR", it) }
        }

    // --- AFC（自動周波数制御） ---
    var afcIsScanning by mutableStateOf(false)
        private set
    var afcStatus by mutableStateOf("待機中")
        private set
    var afcCurrentFrequencyHz by mutableStateOf<Long?>(null)
        private set
    var afcCurrentRssiDb by mutableStateOf<Double?>(null)
        private set
    var afcBestFrequencyHz by mutableStateOf<Long?>(null)
        private set
    var afcBestRssiDb by mutableStateOf<Double?>(null)
        private set
    var afcMeasurements by mutableStateOf<List<AfcMeasurement>>(emptyList())
        private set
    var afcRestrictionMessage by mutableStateOf<String?>(null)
        private set
    private var afcJob: Job? = null
    /** trueになると、走査ループは現在の1周(その回)を終えた時点で折り返さずに終了する。 */
    private var afcStopRequested = false

    init {
        txController.onError = { txError = it }
        rxController.onError = { rxError = it }
    }

    fun startTX() {
        if (isTransmitting || isPreparingTx) return
        if (isReceiving && !settings.useOnDeviceGRDVBS2Rx) {
            txError = "受信中は送信を開始できません。受信を停止してください。"
            return
        }
        txError = null
        isPreparingTx = true
        viewModelScope.launch {
            try {
                if (!preparePlutoTx()) return@launch
                if (!tunePluto(isTx = true)) return@launch
                txController.start(settings)
                isTransmitting = true
            } finally {
                isPreparingTx = false
            }
        }
    }

    fun stopTX() {
        txController.stop()
        isTransmitting = false
    }

    fun startRX() {
        if (isReceiving || isPreparingRx) return
        if (isTransmitting && !settings.useOnDeviceGRDVBS2Rx) {
            rxError = "送信中は受信を開始できません。送信を停止してください。"
            return
        }
        rxError = null
        isPreparingRx = true
        viewModelScope.launch {
            try {
                if (!tunePluto(isTx = false)) return@launch
                rxController.start(settings)
                isReceiving = true
            } finally {
                isPreparingRx = false
            }
        }
    }

    fun stopRX() {
        rxController.stop()
        isReceiving = false
    }

    fun setRxVolume(volume: Float) {
        updateSettings { it.copy(rxVolume = volume) }
        rxController.setVolume(volume)
    }

    /**
     * TX/RX開始時にPluto(txDestinationIP)へ実際のLOを設定する(TS自体は別経路のUDPで流れる)。
     * 失敗時はtxError/rxErrorを設定してfalseを返す -- 呼び出し側はisTransmitting/isReceivingを
     * 立てず、送受信コントローラも起動しないこと(以前はチューニング成否を待たずに立てていたため、
     * 失敗時にフラグが true のまま固まり機器試験の診断ボタンが再起動までグレーアウトし続けるバグがあった)。
     */
    private suspend fun tunePluto(isTx: Boolean): Boolean {
        val plutoIp = settings.txDestinationIP
        val frequencyHz = settings.effectiveLoHz
        val ok = withContext(Dispatchers.IO) {
            val reachable = isPlutoReachable(plutoIp)
            FileLogger.log("TUNE", "isPlutoReachable(IIOD:$IIOD_PORT) ip=$plutoIp result=$reachable")
            reachable && run {
                val tuner = PlutoTuner(plutoIp)
                val tuned = tuner.isOpen && tuner.tune(isTx, frequencyHz)
                FileLogger.log("TUNE", "PlutoTuner isOpen=${tuner.isOpen} tuned=$tuned freqHz=$frequencyHz")
                tuner.close()
                tuned
            }
        }
        if (!ok) {
            val message = "Plutoの周波数設定に失敗しました($plutoIp)"
            if (isTx) txError = message else rxError = message
        }
        return ok
    }

    private suspend fun preparePlutoTx(): Boolean {
        val host = settings.txDestinationIP
        FileLogger.log("TX_PREP", "start host=$host")
        val applied = PlutoSettingsWriter.applyTxSettings(host, settings)
        if (applied.isFailure) {
            FileLogger.log("TX_PREP", "applyTxSettings(HTTP:80) failed: ${applied.exceptionOrNull()}")
            txError = "Pluto変調設定の反映に失敗しました: ${applied.exceptionOrNull()?.message}"
            return false
        }
        FileLogger.log("TX_PREP", "applyTxSettings(HTTP:80) ok")
        val restarted = PlutoUdpTsController.restart(host, getApplication())
        if (restarted.isFailure) {
            FileLogger.log("TX_PREP", "PlutoUdpTsController.restart(SSH:22) failed: ${restarted.exceptionOrNull()}")
            txError = "Pluto UDP受信経路の起動に失敗しました: ${restarted.exceptionOrNull()?.message}"
            return false
        }
        FileLogger.log("TX_PREP", "PlutoUdpTsController.restart(SSH:22) ok")
        delay(1_000)
        return true
    }

    /**
     * ストップが押されるまで中心周波数の前後を10 kHz刻みで往復走査し続ける。
     * ストップ時点で最もRSSIが高かった周波数を送受信周波数へ反映する
     * (一度もRSSIを取得できなかった場合は、走査前の周波数のまま変更しない)。
     */
    // ★開始〜終了の走査を「検索停止」が押されるまで繰り返す(終了に達したら開始へ
    // 折り返す)。「検索停止」を押しても即座には止めず、afcStopRequestedを立てて
    // 現在の1周(その回)を最後まで走らせてから折り返さずに終了する -- 走査を
    // 中途半端な範囲で打ち切らないため。走査終了時にRSSIの変動幅が
    // AFC_PEAK_THRESHOLD_DB以上あった場合のみ「最良周波数」として確定表示する
    // (明確なピークが無い=雑音のばらつきをピークと誤認しない)。確定した結果を
    // 実際に送受信周波数へ反映するかはユーザーが[applyAfcBestAsCenter]を呼ぶまで
    // 保留する(pi5の「新中心周波数」ボタンと同じ、停止操作と反映操作を分離する設計)。
    fun startAfc(rangeHz: Long, stepHz: Long) {
        if (isReceiving) {
            afcRestrictionMessage = "受信中は相手局検索できません。受信を停止してから実行してください。"
            return
        }
        if (isTransmitting) {
            afcRestrictionMessage = "送信中は相手局検索できません。送信を停止してから実行してください。"
            return
        }
        if (afcIsScanning) return

        val centerHz = settings.effectiveLoHz
        val plutoIp = settings.txDestinationIP
        afcStopRequested = false
        afcIsScanning = true
        afcStatus = "Plutoへ接続中…"
        afcCurrentFrequencyHz = null
        afcCurrentRssiDb = null
        afcBestFrequencyHz = null
        afcBestRssiDb = null
        afcMeasurements = emptyList()

        afcJob = viewModelScope.launch {
            var session: AfcNativeSession? = null
            var connected = false
            var tentativeBest: AfcMeasurement? = null
            try {
                if (!isPlutoReachable(plutoIp)) {
                    afcStatus = "エラー: Plutoへ接続できません。IPアドレスと接続状態を確認してください。"
                    return@launch
                }
                val openedSession = withContext(Dispatchers.IO) { AfcNativeSession(plutoIp) }
                session = openedSession
                if (!openedSession.isOpen) {
                    afcStatus = "エラー: Plutoへ接続できません。IPアドレスと接続状態を確認してください。"
                    return@launch
                }
                connected = true
                val startHz = centerHz - rangeHz
                val endHz = centerHz + rangeHz
                var frequencyHz = startHz
                while (isActive) {
                    afcCurrentFrequencyHz = frequencyHz
                    afcStatus = "測定中: ${"%.3f".format(frequencyHz / 1_000_000.0)} MHz"
                    delay(250)
                    val rssi = withContext(Dispatchers.IO) { openedSession.measure(frequencyHz) }
                    if (rssi.isFinite()) {
                        val measurement = AfcMeasurement(frequencyHz, rssi)
                        afcCurrentRssiDb = rssi
                        afcMeasurements = afcMeasurements + measurement
                        if (tentativeBest == null || rssi > tentativeBest!!.rssiDb) {
                            tentativeBest = measurement
                        }
                    }
                    frequencyHz += stepHz
                    if (frequencyHz > endHz) {
                        if (afcStopRequested) break
                        frequencyHz = startHz
                    }
                }
            } finally {
                // キャンセル後も後片付け(実機を元の中心周波数へ戻す・クローズ)は必ず完了させる。
                // 結果の反映は保留するだけなので、Plutoは常に走査前の中心周波数へ戻す。
                withContext(Dispatchers.IO + NonCancellable) {
                    if (connected) session?.measure(centerHz)
                    session?.close()
                }
                if (connected) {
                    val measurements = afcMeasurements
                    val best = tentativeBest
                    val spread = if (measurements.isNotEmpty()) measurements.maxOf { it.rssiDb } - measurements.minOf { it.rssiDb } else 0.0
                    if (best != null && spread >= AFC_PEAK_THRESHOLD_DB) {
                        afcBestFrequencyHz = best.frequencyHz
                        afcBestRssiDb = best.rssiDb
                        afcStatus = "走査完了: 最良周波数 ${"%.3f".format(best.frequencyHz / 1_000_000.0)} MHz (RSSI ${"%.2f".format(best.rssiDb)})。" +
                            "「新中心周波数」で反映してください。"
                    } else if (best != null) {
                        afcStatus = "走査完了: 明確なピークが見つかりませんでした（変動幅 ${"%.2f".format(spread)} dB）"
                    } else {
                        afcStatus = "走査完了（RSSIを取得できませんでした）"
                    }
                }
                afcIsScanning = false
                afcJob = null
            }
        }
    }

    /** 即座には止めず、走査中の現在の1周(その回)が終わってから停止する。 */
    fun stopAfc() {
        if (afcIsScanning && !afcStopRequested) {
            afcStopRequested = true
            afcStatus = "現在の走査が終わり次第停止します…"
        }
    }

    /** 走査で確定した最良周波数を、実際の送受信中心周波数として反映する(pi5の「新中心周波数」ボタン相当)。 */
    fun applyAfcBestAsCenter() {
        val bestHz = afcBestFrequencyHz ?: return
        updateSettings { it.copy(useCustomLoFrequency = true, customLoFrequencyHz = bestHz) }
        afcStatus = "中心周波数を ${"%.3f".format(bestHz / 1_000_000.0)} MHz に設定しました"
    }

    fun clearAfcRestrictionMessage() { afcRestrictionMessage = null }

    // --- 機器試験(TX/RX単体診断) ---
    // Plutoへlibiio経由で直接IQサンプルを送受信し、aff3ct/dvbs2でDVB-S2変調・復調を
    // オンデバイスで行う -- iOS版`DVBS2TestRunner.swift`のrunDiag()と同じ経路。
    // 本番のTx/Rx画面(UDP TS方式、txController/rxController)とは完全に別経路のため、
    // Pluto側にTS変復調モデムが無い環境でも(TX→アッテネータ→RXの物理RFループバック
    // 等の実機構成があれば)機器自体の健全性を診断できる。
    val dvbs2TestRunner = Dvbs2TestRunner(viewModelScope, getApplication(), ProcessLifecycleOwner.get())

    fun runDvbs2Diagnostics() {
        if (dvbs2TestRunner.diagRunning || isTransmitting || isReceiving) return
        dvbs2TestRunner.runDiag(
            context = getApplication(),
            plutoIp = settings.txDestinationIP,
            loHz = settings.effectiveLoHz,
        )
    }

    fun cancelDvbs2Diagnostics() {
        dvbs2TestRunner.cancelDiag()
    }

    // --- 機器試験(カメラ+音声送出の診断) ---
    // カメラ映像+マイク音声を実際にキャプチャ→H.264/AACエンコード→TS多重化という
    // 実運用と同じ経路でPlutoへ送出できるかを確認する -- iOS版`runCameraAudioDiag()`に対応。
    fun runCameraAudioDiagnostics() {
        if (dvbs2TestRunner.cameraDiagRunning || isTransmitting || isReceiving) return
        dvbs2TestRunner.runCameraAudioDiag(
            context = getApplication(),
            plutoIp = settings.txDestinationIP,
            loHz = settings.effectiveLoHz,
            useFrontCamera = settings.useFrontCamera,
        )
    }

    fun cancelCameraAudioDiagnostics() {
        dvbs2TestRunner.cancelCameraAudioDiag()
    }

    override fun onCleared() {
        stopAfc()
        dvbs2TestRunner.destroy()
        super.onCleared()
    }
}
