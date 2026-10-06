package com.shinjo.shonanandroid.langstone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Langstone V3のモード(LangstoneGUI_Pluto.cの`enum {USB,LSB,CW,CWN,FM,AM}`)。
 * [value]はGNU Radio側のRx_Mode/Tx_Modeの値と同じ。
 */
enum class LangstoneMode(val value: Int, val label: String) {
    USB(0, "USB"), LSB(1, "LSB"), CW(2, "CW"), CWN(3, "CWN"), FM(4, "FM"), AM(5, "AM");

    val isCW: Boolean get() = this == CW || this == CWN

    companion object {
        fun of(value: Int): LangstoneMode = entries.firstOrNull { it.value == value } ?: USB
    }
}

/**
 * 設定画面の項目(LangstoneGUI_Pluto.cの`settingText`)。Pi5版の「Rotate Screen」は
 * タブレットでは不要なので除いた(iPad版と同じ)。Band Bitsは、GPIOが無いため
 * 「Copy Band Bits to Pluto」を有効にしたときのPluto GPOへの出力だけに使われる。
 */
enum class LangstoneSetting(val label: String, val japaneseLabel: String) {
    RX_GAIN("Rx Gain", "受信ゲイン"),
    SSB_MIC("SSB Mic Gain", "SSBマイクゲイン"),
    FM_MIC("FM Mic Gain", "FMマイクゲイン"),
    AM_MIC("AM Mic Gain", "AMマイクゲイン"),
    REP_SHIFT("Repeater Shift", "レピータシフト"),
    CTCSS("CTCSS", "トーンスケルチ"),
    RX_OFFSET("Rx Offset", "受信オフセット"),
    RX_HARMONIC("Rx Harmonic Mixing", "受信高調波ミキシング"),
    TX_OFFSET("Tx Offset", "送信オフセット"),
    TX_HARMONIC("Tx Harmonic Mixing", "送信高調波ミキシング"),
    BAND_BITS_RX("Band Bits (Rx)", "バンドビット(受信)"),
    BAND_BITS_TX("Band Bits (Tx)", "バンドビット(送信)"),
    BAND_BITS_TO_PLUTO("Copy Band Bits to Pluto", "バンドビットをPlutoへ出力"),
    FFT_REF("FFT Ref", "FFT基準レベル"),
    TX_ATT("Tx Att", "送信減衰"),
    S_ZERO("S-Meter Zero", "Sメーター零点"),
    SSB_FILT_LOW("SSB Rx Filter Low", "SSB受信フィルタ下限"),
    SSB_FILT_HIGH("SSB Rx Filter High", "SSB受信フィルタ上限"),
    CWID("CW Ident", "CW ID"),
    CW_CARRIER("CWID Carrier", "CWIDキャリア時間"),
    BREAK_IN_TIME("CW Break-In Hang Time", "CWブレークイン保持時間"),
    BANDS24("24 Bands", "24バンド"),
}

/**
 * Langstone_Pluto.conf(LangstoneGUI_Pluto.cのreadConfig/writeConfig)と同じ内容。
 * 初期値もLangstoneGUI_Pluto.cの配列初期値と同じ(iPad版LangstoneConfig.swiftと同じ)。
 * SharedPreferencesにJSONで保存する。
 */
class LangstoneConfig {
    var bandFreq = doubleArrayOf(
        70.200, 144.200, 432.200, 1296.200, 2320.200, 2400.100, 3400.100, 5760.100,
        10368.200, 24048.200, 47088.2, 10489.55, 433.2, 433.2, 433.2, 433.2, 433.2,
        433.2, 1296.2, 1296.2, 1296.2, 1296.2, 1296.2, 1296.2,
    )
    var bandTxOffset = doubleArrayOf(
        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, -9936.0, -23616.0, -46656.0, -10069.5,
        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
    )
    var bandRxOffset = doubleArrayOf(
        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, -9936.0, -23616.0, -46656.0, -10345.0,
        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
    )
    var bandRepShift = doubleArrayOf(
        0.0, -0.6, 1.6, -6.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
    )
    var bandTxHarmonic = IntArray(NUM_BANDS) { 1 }
    var bandRxHarmonic = IntArray(NUM_BANDS) { 1 }
    var bandMode = IntArray(NUM_BANDS) { 0 }
    var bandBitsRx = IntArray(NUM_BANDS) { it }
    var bandBitsTx = IntArray(NUM_BANDS) { it }
    var bandSquelch = Array(NUM_BANDS) { IntArray(NUM_MODES) }
    var bandFFTRef = IntArray(NUM_BANDS) { -10 }
    var bandTxAtt = IntArray(NUM_BANDS) { 0 }
    /** 100は自動(AD9361のslow_attack)。 */
    var bandRxGain = IntArray(NUM_BANDS) { 100 }
    var bandDuplex = IntArray(NUM_BANDS) { 0 }
    var bandCTCSS = IntArray(NUM_BANDS) { 0 }
    var bandSmeterZero = DoubleArray(NUM_BANDS) { -80.0 }
    var bandSSBFiltLow = IntArray(NUM_BANDS) { 300 }
    var bandSSBFiltHigh = IntArray(NUM_BANDS) { 3000 }
    var bandFFTBW = IntArray(NUM_BANDS) { 0 }
    /** Pi5統合版で追加された受信専用バンド(1なら送信しない)。 */
    var bandRxOnly = IntArray(NUM_BANDS) { 0 }

    var currentBand = 3
    var tuneDigit = 8
    var mode = 0
    var ssbMic = 50
    var fmMic = 50
    var amMic = 20
    var volume = 20
    var breakInTime = 100
    var bandBitsToPluto = 0
    var bands24 = 0
    var cwIdent = "TEST_DE_LANGSTONE"
    /** 1/100秒単位(設定ファイル上は秒)。 */
    var cwidKeyDownTime = 1000

    fun save(context: Context) {
        val o = JSONObject()
        o.put("bandFreq", JSONArray(bandFreq.toList()))
        o.put("bandTxOffset", JSONArray(bandTxOffset.toList()))
        o.put("bandRxOffset", JSONArray(bandRxOffset.toList()))
        o.put("bandRepShift", JSONArray(bandRepShift.toList()))
        o.put("bandTxHarmonic", JSONArray(bandTxHarmonic.toList()))
        o.put("bandRxHarmonic", JSONArray(bandRxHarmonic.toList()))
        o.put("bandMode", JSONArray(bandMode.toList()))
        o.put("bandBitsRx", JSONArray(bandBitsRx.toList()))
        o.put("bandBitsTx", JSONArray(bandBitsTx.toList()))
        o.put("bandSquelch", JSONArray(bandSquelch.map { JSONArray(it.toList()) }))
        o.put("bandFFTRef", JSONArray(bandFFTRef.toList()))
        o.put("bandTxAtt", JSONArray(bandTxAtt.toList()))
        o.put("bandRxGain", JSONArray(bandRxGain.toList()))
        o.put("bandDuplex", JSONArray(bandDuplex.toList()))
        o.put("bandCTCSS", JSONArray(bandCTCSS.toList()))
        o.put("bandSmeterZero", JSONArray(bandSmeterZero.toList()))
        o.put("bandSSBFiltLow", JSONArray(bandSSBFiltLow.toList()))
        o.put("bandSSBFiltHigh", JSONArray(bandSSBFiltHigh.toList()))
        o.put("bandFFTBW", JSONArray(bandFFTBW.toList()))
        o.put("bandRxOnly", JSONArray(bandRxOnly.toList()))
        o.put("currentBand", currentBand)
        o.put("tuneDigit", tuneDigit)
        o.put("mode", mode)
        o.put("ssbMic", ssbMic)
        o.put("fmMic", fmMic)
        o.put("amMic", amMic)
        o.put("volume", volume)
        o.put("breakInTime", breakInTime)
        o.put("bandBitsToPluto", bandBitsToPluto)
        o.put("bands24", bands24)
        o.put("cwIdent", cwIdent)
        o.put("cwidKeyDownTime", cwidKeyDownTime)
        prefs(context).edit().putString(STORAGE_KEY, o.toString()).apply()
    }

    companion object {
        const val NUM_BANDS = 24
        const val NUM_MODES = 6
        private const val PREFS = "langstone"
        private const val STORAGE_KEY = "langstoneConfig"
        /** 衛星から開いた直前に使っていたバンド。普通の「Langstone」カードで開くときに戻す。 */
        private const val PREV_BAND_KEY = "langstonePrevBand"

        /**
         * 10GHz受信用のバンド(Pi5版langstone_config.pyと同じ)。24バンドのうち通常使われない
         * 最後のバンド(番号23、画面上は24番目)を使う。LNB(局部発振9750MHz)で周波数を下げて
         * 受信する前提で、表示は10236.5MHz、Plutoの受信周波数は486.5MHz(=10236.5-9750)。
         * 486.5MHzはアマチュアバンド外のため受信専用(bandRxOnly)にして送信を禁止する。
         */
        const val SATELLITE_BAND = 23
        private const val SATELLITE_DISPLAY_MHZ = 10236.5
        private const val LNB_LO_MHZ = 9750.0

        /** 次にLangstoneを開くとき、10GHz受信用バンド(受信専用)で開くようにする。 */
        fun selectSatelliteBand(context: Context) {
            val c = load(context)
            if (c.currentBand != SATELLITE_BAND) {
                prefs(context).edit().putInt(PREV_BAND_KEY, c.currentBand).apply()
            }
            val b = SATELLITE_BAND
            c.bandFreq[b] = SATELLITE_DISPLAY_MHZ
            c.bandRxOffset[b] = -LNB_LO_MHZ
            c.bandTxOffset[b] = -LNB_LO_MHZ
            c.bandRxHarmonic[b] = 1
            c.bandTxHarmonic[b] = 1
            c.bandRxOnly[b] = 1
            c.currentBand = b
            c.save(context)
        }

        /** 衛星から開いたままなら、次のLangstoneを衛星の前のバンドで開くようにする。 */
        fun restorePreviousBand(context: Context) {
            val p = prefs(context)
            if (!p.contains(PREV_BAND_KEY)) return
            val prev = p.getInt(PREV_BAND_KEY, -1)
            p.edit().remove(PREV_BAND_KEY).apply()
            if (prev !in 0 until NUM_BANDS) return
            val c = load(context)
            // Langstone上で別のバンドへ切り替えていた場合は、その選択を尊重して変えない。
            if (c.currentBand != SATELLITE_BAND) return
            c.currentBand = prev
            c.save(context)
        }

        /** トーンスケルチ周波数(0.1Hz単位、先頭の0は「なし」)。 */
        val ctcssTones = intArrayOf(
            0, 670, 693, 719, 744, 770, 797, 825, 854, 885, 915, 948, 974, 1000, 1035, 1072,
            1109, 1148, 1188, 1230, 1273, 1318, 1365, 1413, 1462, 1500, 1514, 1567, 1598,
            1622, 1655, 1679, 1713, 1738, 1773, 1799, 1835, 1862, 1899, 1928, 1966, 1995,
            2035, 2065, 2107, 2181, 2257, 2291, 2336, 2418, 2503, 2541,
        )

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** 保存済みの設定を読む。壊れている項目・無い項目は初期値のまま。 */
        fun load(context: Context): LangstoneConfig {
            val c = LangstoneConfig()
            val text = prefs(context).getString(STORAGE_KEY, null) ?: return c
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return c
            fun doubles(key: String, into: DoubleArray) {
                val a = o.optJSONArray(key) ?: return
                for (i in 0 until minOf(a.length(), into.size)) into[i] = a.optDouble(i, into[i])
            }
            fun ints(key: String, into: IntArray) {
                val a = o.optJSONArray(key) ?: return
                for (i in 0 until minOf(a.length(), into.size)) into[i] = a.optInt(i, into[i])
            }
            doubles("bandFreq", c.bandFreq)
            doubles("bandTxOffset", c.bandTxOffset)
            doubles("bandRxOffset", c.bandRxOffset)
            doubles("bandRepShift", c.bandRepShift)
            ints("bandTxHarmonic", c.bandTxHarmonic)
            ints("bandRxHarmonic", c.bandRxHarmonic)
            ints("bandMode", c.bandMode)
            ints("bandBitsRx", c.bandBitsRx)
            ints("bandBitsTx", c.bandBitsTx)
            o.optJSONArray("bandSquelch")?.let { a ->
                for (b in 0 until minOf(a.length(), NUM_BANDS)) {
                    val row = a.optJSONArray(b) ?: continue
                    for (m in 0 until minOf(row.length(), NUM_MODES)) c.bandSquelch[b][m] = row.optInt(m, 0)
                }
            }
            ints("bandFFTRef", c.bandFFTRef)
            ints("bandTxAtt", c.bandTxAtt)
            ints("bandRxGain", c.bandRxGain)
            ints("bandDuplex", c.bandDuplex)
            ints("bandCTCSS", c.bandCTCSS)
            doubles("bandSmeterZero", c.bandSmeterZero)
            ints("bandSSBFiltLow", c.bandSSBFiltLow)
            ints("bandSSBFiltHigh", c.bandSSBFiltHigh)
            ints("bandFFTBW", c.bandFFTBW)
            ints("bandRxOnly", c.bandRxOnly)
            c.currentBand = o.optInt("currentBand", c.currentBand)
            c.tuneDigit = o.optInt("tuneDigit", c.tuneDigit)
            c.mode = o.optInt("mode", c.mode)
            c.ssbMic = o.optInt("ssbMic", c.ssbMic)
            c.fmMic = o.optInt("fmMic", c.fmMic)
            c.amMic = o.optInt("amMic", c.amMic)
            c.volume = o.optInt("volume", c.volume)
            c.breakInTime = o.optInt("breakInTime", c.breakInTime)
            c.bandBitsToPluto = o.optInt("bandBitsToPluto", c.bandBitsToPluto)
            c.bands24 = o.optInt("bands24", c.bands24)
            c.cwIdent = o.optString("cwIdent", c.cwIdent)
            c.cwidKeyDownTime = o.optInt("cwidKeyDownTime", c.cwidKeyDownTime)
            if (c.mode >= NUM_MODES) c.mode = 0
            if (c.currentBand !in 0 until NUM_BANDS) c.currentBand = 3
            for (b in 0 until NUM_BANDS) {
                c.bandCTCSS[b] = c.bandCTCSS[b].coerceIn(0, ctcssTones.size - 1)
                c.bandFFTBW[b] = c.bandFFTBW[b].coerceIn(0, 3)
            }
            return c
        }
    }
}

/**
 * Morse.c(CW IDの符号化)の移植。100回/秒で[key]を呼ぶと、キーのON(1)/OFF(0)、
 * またはID送出の終わり(-1)を返す。空白は「_」で表す(Pi5版と同じ)。
 */
class MorseKeyer {
    var ident: MutableList<Int> = "TEST_DE_LANGSTONE".map { it.code }.toMutableList()
    private var charIndex = 0
    private var bitIndex = 0
    private var charLength = 0
    private var bitTimer = 0
    private val bitInterval = 5
    private var shiftReg = 0
    private var lastRet = 0

    private fun encode(index: Int): Pair<Int, Int> {
        if (index >= ident.size) return 0 to 0
        var ch = ident[index]
        if (ch == 32 || ch == 95) return 0 to 7
        if (ch == 47) return 0x1757 to 13 + 3
        if (ch in 48..57) return NUMBERS[ch - 48] to NUMBER_LENGTH[ch - 48] + 3
        if (ch > 96) ch -= 32
        if (ch in 65..90) return LETTERS[ch - 65] to LETTER_LENGTH[ch - 65] + 3
        return 0 to 0
    }

    fun reset() {
        charIndex = 0
        bitIndex = 0
        bitTimer = 0
        lastRet = 0
        encode(0).let { (reg, len) -> shiftReg = reg; charLength = len }
    }

    fun key(): Int {
        val timer = bitTimer
        bitTimer += 1
        if (timer < bitInterval) return lastRet
        bitTimer = 0
        var ret = shiftReg and 1
        lastRet = ret
        shiftReg = shiftReg shr 1
        val index = bitIndex
        bitIndex += 1
        if (index >= charLength) {
            bitIndex = 0
            charIndex += 1
            if (charIndex >= ident.size) {
                charIndex = 0
                ret = -1
            }
            encode(charIndex).let { (reg, len) -> shiftReg = reg; charLength = len }
        }
        return ret
    }

    private companion object {
        val LETTERS = intArrayOf(
            0x001D, 0x0157, 0x05D7, 0x0057, 0x0001, 0x0175, 0x0177, 0x0055, 0x0005,
            0x1DDD, 0x01D7, 0x015D, 0x0077, 0x0017, 0x0777, 0x05DD, 0x1D77, 0x005D,
            0x0015, 0x0007, 0x0075, 0x01D5, 0x01DD, 0x0757, 0x1DD7, 0x0577,
        )
        val LETTER_LENGTH = intArrayOf(5, 9, 11, 7, 1, 9, 9, 7, 3, 13, 9, 9, 7, 5, 11, 11, 13, 7, 5, 3, 7, 9, 9, 11, 13, 11)
        val NUMBERS = intArrayOf(0x77777, 0x1DDDD, 0x7775, 0x1DD5, 0x0755, 0x0155, 0x0557, 0x1577, 0x5777, 0x17777)
        val NUMBER_LENGTH = intArrayOf(19, 17, 15, 13, 11, 9, 11, 13, 15, 17)
    }
}
