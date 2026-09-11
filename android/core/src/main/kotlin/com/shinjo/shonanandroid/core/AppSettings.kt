package com.shinjo.shonanandroid.core

/**
 * 送信/受信は別タブレット2台が基本だが、1台で送受信を切り替える運用も排除しない。
 * iOS版`AppMode`(Shonan/Models/AppSettings.swift)のKotlin移植。
 */
enum class AppMode(val displayName: String) {
    TRANSMIT("送信(Tx)"),
    RECEIVE("受信(Rx)"),
}

/** 送信/受信/周波数画面の表示言語。iOS版`AppLanguage`(Shonan/Models/AppSettings.swift)のKotlin移植。 */
enum class AppLanguage(val displayName: String, val displayNameEnglish: String) {
    JAPANESE("日本語", "Japanese"),
    ENGLISH("English", "English"),
}

/**
 * DVB-S2のFEC符号化率。Plutoのtx_rf.sh互換パラメータも保持する。
 */
enum class FECRate(val label: String, val plutoParameter: String) {
    R1_2("1/2", "12"), R3_5("3/5", "35"), R8_9("8/9", "89"),

    ;

    val codeRate: Double
        get() = when (this) {
            R1_2 -> 1.0 / 2.0; R3_5 -> 3.0 / 5.0; R8_9 -> 8.0 / 9.0
        }
}

/**
 * DVB-S2の変調方式。Pluto側のハードウェアパラメータでアプリからは制御しないが、
 * 運用メモとして記録・確認できるようにする(iOS版`ModulationScheme`の移植)。
 */
enum class ModulationScheme(val label: String) {
    QPSK("QPSK"), PSK8("8PSK"), APSK16("16APSK"),
}

/** Wi-Fiルータが運用する2バンド(iOS版`WiFiBand`の移植)。 */
enum class WiFiBand(val label: String, val labelEnglish: String) {
    GHZ_2_4("2.4GHz", "2.4 GHz"),
    GHZ_5("5GHz", "5 GHz"),
    /** 1200/10G/24GHzはどちらでも重複しないため、運用上どちらでもよい。 */
    EITHER("2.4GHz/5GHzいずれも可", "Either 2.4 GHz or 5 GHz"),
}

/**
 * 全デバイスのIPアドレス・ポートの既定値。設定画面でユーザーが任意に変更できる
 * フォールバック値(192.168.0.0/24)。iOS版`NetworkDefaults`の移植。
 */
object NetworkDefaults {
    const val ROUTER = "192.168.0.1"
    const val PLUTO_TX = "192.168.0.10"
    const val PLUTO_RX = "192.168.0.20"
    const val TX_TABLET = "192.168.0.11"
    const val RX_TABLET = "192.168.0.21"

    /** F5OEO plutosdr-fw既定値。 */
    const val TX_PORT: Int = 7272
    /** Longmynd公式README準拠。 */
    const val RX_TS_PORT: Int = 4003
    const val RX_STATUS_PORT: Int = 4002
}

/**
 * バンドプロファイル。1200/2400/5600/10000/24000MHzの各運用帯域と、
 * ループ試験モードを1つのenumで扱う(iOS版`BandProfile`の移植)。
 *
 * symbolRateMsps/videoBitrateBpsはDATV運用で一般的な目安値の暫定値
 * (iOS版と同様、Pi4版仕様書の正式値に置き換えること)。
 */
enum class BandProfile(val displayName: String, val displayNameEnglish: String) {
    BAND_1200("1200MHz", "1200 MHz"),
    BAND_2400("2400MHz", "2400 MHz"),
    BAND_5600("5600MHz", "5600 MHz"),
    BAND_10000("10GHz", "10 GHz"),
    BAND_24000("24GHz", "24 GHz"),
    /** Pluto/RF実機を介さないソフトウェアチェーン単体の動作確認用。 */
    LOOPBACK("ループ試験", "Loopback Test");

    val isLoopback: Boolean get() = this == LOOPBACK

    val recommendedWiFiBand: WiFiBand
        get() = when (this) {
            BAND_1200, BAND_10000, BAND_24000, LOOPBACK -> WiFiBand.EITHER
            BAND_2400 -> WiFiBand.GHZ_5
            BAND_5600 -> WiFiBand.GHZ_2_4
        }

    val wiFiBandReason: String
        get() = when (this) {
            BAND_1200 -> "RF帯域と重複しないため直接干渉なし"
            BAND_2400 -> "2.4GHz Wi-Fi(2.400〜2.4835GHz)はRF帯域と完全に重なり強い自己干渉を生じるため、5GHz Wi-Fiを使用する"
            BAND_5600 -> "5GHz Wi-Fi(5.15〜5.85GHz帯)は5600MHzのRF帯域と重複するため、2.4GHz Wi-Fiを使用する"
            BAND_10000, BAND_24000 -> "重複なし"
            LOOPBACK -> "Pluto/RFを経由しないため、Wi-Fi帯の干渉回避ルールは適用対象外"
        }

    val wiFiBandReasonEnglish: String
        get() = when (this) {
            BAND_1200 -> "No direct interference because the RF and Wi-Fi bands do not overlap"
            BAND_2400 -> "2.4 GHz Wi-Fi (2.400–2.4835 GHz) fully overlaps the RF band and causes strong self-interference; use 5 GHz Wi-Fi"
            BAND_5600 -> "5 GHz Wi-Fi (5.15–5.85 GHz) overlaps the 5600 MHz RF band; use 2.4 GHz Wi-Fi"
            BAND_10000, BAND_24000 -> "No overlap"
            LOOPBACK -> "Not applicable because this profile does not use Pluto RF"
        }

    /** 暫定値。 */
    val symbolRateMsps: Double?
        get() = if (this == LOOPBACK) null else 2.0

    /** 暫定値。DATV用途で一般的なH.264 1080p30の目安として1.5Mbpsを暫定採用。 */
    val videoBitrateBps: Int
        get() = if (this == LOOPBACK) 4_000_000 else 1_500_000

    /** 各バンドの代表周波数(LO、Hz単位)。ループ試験はRFを使わないためnull。 */
    val loHz: Long?
        get() = when (this) {
            BAND_1200 -> 1_273_000_000L
            BAND_2400 -> 2_407_000_000L
            BAND_5600 -> 5_730_000_000L
            BAND_10000 -> 10_180_000_000L
            BAND_24000 -> 24_000_000_000L
            LOOPBACK -> null
        }

    companion object {
        val default = BAND_1200
    }
}

/**
 * アプリ全体の設定。iOS版`AppSettings`(ObservableObject+UserDefaults永続化)の
 * Kotlin移植。永続化はAndroid SDK依存のため:app側のSettingsStoreが担当し、
 * ここは不変データクラスとしてのみ扱う(:coreはAndroid非依存)。
 */
data class AppSettings(
    var mode: AppMode = AppMode.TRANSMIT,
    /** 送信/受信/周波数画面の表示言語。 */
    var language: AppLanguage = AppLanguage.JAPANESE,
    var selectedBand: BandProfile = BandProfile.default,

    // 送信側(Tx)設定
    var txDestinationIP: String = NetworkDefaults.PLUTO_TX,
    var txDestinationPort: Int = NetworkDefaults.TX_PORT,

    // 受信側(Rx)設定
    var rxListenPort: Int = NetworkDefaults.RX_TS_PORT,
    var rxStatusPort: Int = NetworkDefaults.RX_STATUS_PORT,
    /** 受信音声のタブレット出力音量(0f〜1f)。 */
    var rxVolume: Float = 0.5f,
    /** PlutoのRF IQをAndroid側で直接復調する実運用モード。 */
    var useOnDeviceGRDVBS2Rx: Boolean = false,
    /** オンデバイスDVB-S2復調のRRCロールオフ係数。 */
    var dvbs2Rolloff: Double = 0.35,
    // カメラ設定
    var useFrontCamera: Boolean = false,
    /** trueの場合、Picturesから選択した静止画へ情報を重ねて送信する。 */
    var usePhotoSource: Boolean = false,
    /** 永続化された写真のcontent URI。 */
    var selectedPhotoUri: String? = null,
    /** 写真へ重ねるコールサイン。 */
    var photoCallsign: String = "",
    /** 写真へ重ねる備考。 */
    var photoNote: String = "",
    /** trueの場合、カメラの代わりに固定のカラーバーパターンを送信する(実機カメラなしで
     *  送信パイプラインの動作確認をするための機能)。 */
    var useColorBarSource: Boolean = false,
    /** trueの場合、送信時にマイク音声をAACで多重化する。 */
    var transmitAudio: Boolean = true,

    // 受信感度
    var rxAgcEnabled: Boolean = true,
    /** AGCを使わないときのAD936x RX gain。Plutoの有効範囲に合わせて0〜73 dB。 */
    var rxGainDb: Int = 60,

    // 送信出力
    /** 0が最大出力、負値ほど減衰。Pluto側のRF減衰値(dB)。 */
    /** 送信出力減衰値。0 dBが最大出力(外部アッテネータ併用前提)。 */
    var txPowerDb: Int = 0,

    // シンボルレート(★運用メモ、Pluto側パラメータの記録用。実制御なし)
    var symbolRateMsps: Double = BandProfile.default.symbolRateMsps ?: 2.0,

    // FEC/変調(★運用メモ、Pluto側パラメータの記録用。実制御なし)
    var fecRate: FECRate = FECRate.R1_2,
    var modulationScheme: ModulationScheme = ModulationScheme.QPSK,

    // 機器診断用周波数(★運用メモ)
    var useCustomLoFrequency: Boolean = false,
    var customLoFrequencyHz: Long = BandProfile.default.loHz ?: 1_200_000_000L,
) {
    /** 診断機能が実際に使うLO周波数(Hz)。バンド選択の代表値と手動入力のどちらかを解決する。 */
    val effectiveLoHz: Long
        get() = if (useCustomLoFrequency) customLoFrequencyHz else (selectedBand.loHz ?: customLoFrequencyHz)

    /** 現行オンデバイス復調器は2Msps入力でネイティブクラッシュするため安全値に制限する。 */
    val effectiveOperationalSymbolRateMsps: Double
        get() = if (useOnDeviceGRDVBS2Rx && symbolRateMsps > 0.5) 0.5 else symbolRateMsps

    /** RF伝送容量を超えない映像エンコーダ設定。余裕を残してTS輻輳を防ぐ。 */
    val effectiveVideoBitrateBps: Int
        get() {
            if (selectedBand.isLoopback) return selectedBand.videoBitrateBps
            val bitsPerSymbol = when (modulationScheme) {
                ModulationScheme.QPSK -> 2.0
                ModulationScheme.PSK8 -> 3.0
                ModulationScheme.APSK16 -> 4.0
            }
            // MPEG-TS/PES/H.264の実測オーバーヘッドを含め、RF容量の55%を映像目標にする。
            // 1Msym/s QPSK 3/5では映像約660kbps、TS実測約0.8〜0.9Mbpsを目安とする。
            val safeCapacity = effectiveOperationalSymbolRateMsps * 1_000_000.0 * bitsPerSymbol * fecRate.codeRate * 0.55
            val audioReserve = if (transmitAudio) 64_000.0 else 0.0
            return minOf(selectedBand.videoBitrateBps.toDouble(), safeCapacity - audioReserve)
                .coerceAtLeast(200_000.0).toInt()
        }

    /** ループ試験プロファイル選択時に実際に使うUDP送出先を解決する。 */
    fun effectiveTxDestination(): Pair<String, Int> {
        return txDestinationIP to txDestinationPort
    }

    /** `settings.t("日本語文言", "English copy")`の形で使う簡易翻訳ヘルパー。 */
    fun t(japanese: String, english: String): String = if (language == AppLanguage.JAPANESE) japanese else english

    companion object {
        val default = AppSettings()
    }
}
