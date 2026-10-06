package com.shinjo.shonanandroid.langstone

import android.content.Context
import com.shinjo.shonanandroid.gnuradio.GnuRadioEnvironment

/**
 * `langstone_bridge.cpp`のJNIラッパー。iPad版の`LangstoneTRxSession`(GNU Radioフローグラフ
 * `Lang_TRX_Pluto.py`のC++版)と`LangstonePlutoControl`(libiioでのPluto直接制御)を1つにまとめた。
 *
 * [start]・[stop]・[plutoConnect]・[plutoFlush]は終わるまで待つので、メインスレッドから呼ばないこと。
 * それ以外(setterやPlutoへの書き込み)はすぐ戻る。
 */
class LangstoneNative(context: Context) {
    init {
        ensureLoaded(context)
    }

    private var handle: Long = nativeCreate()

    /** Plutoへ接続してフローグラフを開始する。失敗の説明(成功ならnull)を返す。 */
    fun start(host: String, rxHz: Double, txHz: Double): String? =
        nativeStart(handle, host, rxHz, txHz).takeIf { it.isNotEmpty() }

    fun stop() = nativeStop(handle)
    val isRunning: Boolean get() = nativeIsRunning(handle)

    /** 音声(AAudio)を開始する。失敗の説明(無ければnull)を返す。 */
    fun startAudio(withInput: Boolean): String? = nativeStartAudio(handle, withInput).takeIf { it.isNotEmpty() }
    fun stopAudio() = nativeStopAudio(handle)

    /** 最新のFFT1フレーム(512点、dB、0番目がDC)を`out`へ入れる。新しいフレームが無ければfalse。 */
    fun takeFFT(tx: Boolean, out: FloatArray): Boolean = nativeTakeFFT(handle, tx, out)
    fun clearFFT() = nativeClearFFT(handle)
    fun takeAudioStats(): String = nativeTakeAudioStats(handle)

    // --- Lang_TRX_Pluto.pyのsetter(FIFOコマンド)に対応 ---
    fun setRxMute(mute: Boolean) = nativeSetRxMute(handle, mute)                  // U
    fun setRxOffset(hz: Double) = nativeSetRxOffset(handle, hz)                   // O
    fun setAFGain(gain: Int) = nativeSetAFGain(handle, gain)                      // V
    fun setRxFilter(low: Int, high: Int) = nativeSetRxFilter(handle, low, high)   // I/F
    fun setTxFilter(low: Int, high: Int) = nativeSetTxFilter(handle, low, high)   // i/f
    fun setMode(mode: Int) = nativeSetMode(handle, mode)                          // M
    fun setPTT(ptt: Boolean) = nativeSetPTT(handle, ptt)                          // T/R
    fun setKey(key: Boolean) = nativeSetKey(handle, key)                          // K
    fun setToneBurst(on: Boolean) = nativeSetToneBurst(handle, on)                // B
    fun setMicGain(gain: Int) = nativeSetMicGain(handle, gain)                    // G
    fun setFMMic(gain: Int) = nativeSetFMMic(handle, gain)                        // g
    fun setAMMic(gain: Int) = nativeSetAMMic(handle, gain)                        // d
    fun setCTCSS(tenthsHz: Int) = nativeSetCTCSS(handle, tenthsHz)                // C
    fun setFFTSel(sel: Int) = nativeSetFFTSel(handle, sel)                        // W

    // --- Plutoの直接制御(LangstoneGUI_Pluto.cの各関数) ---
    fun plutoConnect(host: String): Boolean = nativePlutoConnect(handle, host)
    fun plutoDisconnect() = nativePlutoDisconnect(handle)
    fun plutoFlush() = nativePlutoFlush(handle)
    fun plutoSetRxFrequency(hz: Long) = nativePlutoSetRxFrequency(handle, hz)
    fun plutoSetTxFrequency(hz: Long) = nativePlutoSetTxFrequency(handle, hz)
    fun plutoSetTxAttenuation(db: Int) = nativePlutoSetTxAttenuation(handle, db)
    fun plutoSetRxGain(gain: Int, maxGain: Int) = nativePlutoSetRxGain(handle, gain, maxGain)
    fun plutoRefreshRxGain() = nativePlutoRefreshRxGain(handle)
    fun plutoCurrentRxGain(): Int = nativePlutoCurrentRxGain(handle)
    fun plutoSetTxEnabled(on: Boolean) = nativePlutoSetTxEnabled(handle, on)
    fun plutoSetRxEnabled(on: Boolean) = nativePlutoSetRxEnabled(handle, on)
    fun plutoSetGpo(value: Int) = nativePlutoSetGpo(handle, value)

    fun destroy() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeStart(handle: Long, host: String, rxHz: Double, txHz: Double): String
    private external fun nativeStop(handle: Long)
    private external fun nativeIsRunning(handle: Long): Boolean
    private external fun nativeStartAudio(handle: Long, withInput: Boolean): String
    private external fun nativeStopAudio(handle: Long)
    private external fun nativeTakeFFT(handle: Long, tx: Boolean, out: FloatArray): Boolean
    private external fun nativeClearFFT(handle: Long)
    private external fun nativeTakeAudioStats(handle: Long): String
    private external fun nativeSetRxMute(handle: Long, mute: Boolean)
    private external fun nativeSetRxOffset(handle: Long, hz: Double)
    private external fun nativeSetAFGain(handle: Long, gain: Int)
    private external fun nativeSetRxFilter(handle: Long, low: Int, high: Int)
    private external fun nativeSetTxFilter(handle: Long, low: Int, high: Int)
    private external fun nativeSetMode(handle: Long, mode: Int)
    private external fun nativeSetPTT(handle: Long, ptt: Boolean)
    private external fun nativeSetKey(handle: Long, key: Boolean)
    private external fun nativeSetToneBurst(handle: Long, on: Boolean)
    private external fun nativeSetMicGain(handle: Long, gain: Int)
    private external fun nativeSetFMMic(handle: Long, gain: Int)
    private external fun nativeSetAMMic(handle: Long, gain: Int)
    private external fun nativeSetCTCSS(handle: Long, tenthsHz: Int)
    private external fun nativeSetFFTSel(handle: Long, sel: Int)
    private external fun nativePlutoConnect(handle: Long, host: String): Boolean
    private external fun nativePlutoDisconnect(handle: Long)
    private external fun nativePlutoFlush(handle: Long)
    private external fun nativePlutoSetRxFrequency(handle: Long, hz: Long)
    private external fun nativePlutoSetTxFrequency(handle: Long, hz: Long)
    private external fun nativePlutoSetTxAttenuation(handle: Long, db: Int)
    private external fun nativePlutoSetRxGain(handle: Long, gain: Int, maxGain: Int)
    private external fun nativePlutoRefreshRxGain(handle: Long)
    private external fun nativePlutoCurrentRxGain(handle: Long): Int
    private external fun nativePlutoSetTxEnabled(handle: Long, on: Boolean)
    private external fun nativePlutoSetRxEnabled(handle: Long, on: Boolean)
    private external fun nativePlutoSetGpo(handle: Long, value: Int)

    companion object {
        const val FFT_SIZE = 512

        @Volatile private var loaded = false

        @Synchronized
        private fun ensureLoaded(context: Context) {
            if (loaded) return
            GnuRadioEnvironment.ensure(context)
            System.loadLibrary("langstone_bridge")
            loaded = true
        }
    }
}
