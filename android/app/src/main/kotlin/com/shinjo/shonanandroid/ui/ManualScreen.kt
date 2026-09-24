package com.shinjo.shonanandroid.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.navigation.NavHostController
import com.shinjo.shonanandroid.AppViewModel
import com.shinjo.shonanandroid.R
import com.shinjo.shonanandroid.core.AppLanguage
import java.util.Locale
import kotlinx.coroutines.launch

private data class ManualEntry(val id: String, val title: String, val terms: List<String>)

private val manualEntries = listOf(
    ManualEntry("overview", "1. 概要", listOf("概要", "overview", "shonan", "datv", "pluto")),
    ManualEntry("menu", "2. ホーム画面(メインメニュー)構成", listOf("メニュー", "main menu", "help", "ヘルプ")),
    ManualEntry("tx", "3. 送信(Tx)画面", listOf("送信", "tx", "transmit")),
    ManualEntry("rx", "4. 受信(Rx)画面", listOf("受信", "rx", "receive")),
    ManualEntry("frequency", "5. 周波数(Frequency)画面", listOf("周波数", "frequency")),
    ManualEntry("find", "6. RSSI測定画面", listOf("RSSI測定", "相手局検索", "afc", "rssi", "電波強度", "グラフ", "自動周波数")),
    ManualEntry("symbolrate", "7. シンボルレート(Symbol Rate)画面", listOf("シンボルレート", "symbol rate")),
    ManualEntry("fec", "8. 誤り訂正(FEC)画面", listOf("fec", "誤り訂正", "符号化率")),
    ManualEntry("modulation", "9. 変調(Modulation)画面", listOf("変調", "modulation", "qpsk")),
    ManualEntry("videosource", "10. 映像ソース(Video Source)画面", listOf("映像", "video source", "カメラ", "color bar")),
    ManualEntry("streamoutput", "11. 配信先(Stream Output)画面", listOf("配信先", "stream output", "ip", "port")),
    ManualEntry("rxgain", "12. 受信感度(RX Gain)画面", listOf("受信感度", "rx gain", "gain", "agc")),
    ManualEntry("txpower", "13. 送信出力(TX Power)画面", listOf("送信出力", "tx power", "power")),
    ManualEntry("config", "14. 設定(Config)画面", listOf("設定", "config")),
    ManualEntry("diagnostic", "15. 機器試験(Diagnostic)画面", listOf("機器試験", "diagnostic", "診断")),
    ManualEntry("credits", "クレジット", listOf("クレジット", "credits", "JE1BTA", "山崎慎慈", "真城和一", "JA6FUF", "JH1XHX")),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualScreen(@Suppress("UNUSED_PARAMETER") viewModel: AppViewModel, navController: NavHostController) {
    if (viewModel.settings.language == AppLanguage.ENGLISH) {
        EnglishManualScreen(navController)
        return
    }
    var query by remember { mutableStateOf("") }
    val sectionOffsets = remember { mutableStateMapOf<String, Int>() }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val normalizedQuery = normalizeManualSearch(query)
    val match = manualEntries.firstOrNull { entry ->
        normalizedQuery.isNotBlank() && (listOf(entry.title) + entry.terms).any { normalizeManualSearch(it).contains(normalizedQuery) }
    }

    LaunchedEffect(match?.id, sectionOffsets.size) {
        match?.let { entry -> sectionOffsets[entry.id]?.let { offset -> scrollState.animateScrollTo(offset) } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ヘルプ") },
                navigationIcon = {
                    HomeBackAction({ navController.popBackStack("home", false) }, "ホームへ戻る")
                },
                actions = {
                    TextButton(onClick = { scope.launch { scrollState.animateScrollTo(0) } }) { Text("目次に戻る") }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("説明書を検索") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
            )
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                ManualSection("クレジット", sectionModifier("credits", sectionOffsets, scrollState)) {
                    ManualBullet("受信部の方式考案・受信部原システム設計: 山崎慎慈氏(JE1BTA) rpi-dvbs2-receiver-guiの設計に基づきます")
                    ManualBullet("受信部安定化調査修正・再捕捉修正・本アプリ開発: 真城和一(JA6FUF/JH1XHX)")
                    ManualBullet("本アプリは、Dave Crump氏(G8GKQ)が開発したDATV送受信機プロジェクト「Portsdown」に啓発され、開発したものです。同氏の先駆的な取り組みに感謝いたします。")
                    ManualBullet("本プログラムを使用して生じたいかなる損害についても、開発者は一切の責任を負いません。ご自身の責任においてご利用ください。")
                }
                ManualSection("目次", sectionModifier("toc", sectionOffsets, scrollState)) {
                    manualEntries.forEach { entry ->
                        TextButton(onClick = { scope.launch { sectionOffsets[entry.id]?.let { offset -> scrollState.animateScrollTo(offset) } } }) {
                            Text(entry.title, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                ManualSection("1. 概要", sectionModifier("overview", sectionOffsets, scrollState)) {
                    ManualParagraph("Shonan for Androidは、Plutoを介したDVB-S2 DATVの送受信をタブレットから行うアプリです。")
                    ManualBullet("送信(Tx): 映像と音声をH.264/AACでエンコードし、MPEG-TSをUDPでPlutoの8282番ポートへ送出します。")
                    ManualBullet("受信(Rx): オンデバイス復調時はPlutoのRFをAndroidで復調し、映像・音声を表示します。")
                    ManualBullet("送受信開始時にPlutoの変調設定を自動反映し、UDP受信経路を起動します。")
                }
                ManualSection("2. ホーム画面(メインメニュー)構成", sectionModifier("menu", sectionOffsets, scrollState)) {
                    Image(painter = painterResource(R.drawable.manual_home), contentDescription = "ホーム画面", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    ManualTable(listOf("ボタン", "機能"), listOf(
                        listOf("受信感度 / RX Gain", "AGCと手動受信ゲインを設定"),
                        listOf("送信出力 / TX Power", "送信出力減衰値を設定"),
                        listOf("設定 / Config", "表示言語・オンデバイス復調(RFループバック)などの基本設定"),
                        listOf("機器試験 / Diagnostic", "TX/RX単体診断とカメラ+音声送出診断"),
                        listOf("RSSI測定 / RSSI Measurement", "指定範囲のRSSIを測定し、最も強い周波数を探す"),
                        listOf("ヘルプ / Help", "この操作説明書を表示"),
                        listOf("アプリ再起動", "Plutoへ再起動要求を送り、復旧を待つ"),
                        listOf("プログラム終了", "アプリを終了"),
                    ))
                }
                ManualSection("3. 送信(Tx)画面", sectionModifier("tx", sectionOffsets, scrollState)) {
                    Image(painter = painterResource(R.drawable.manual_tx), contentDescription = "送信画面", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    ManualParagraph("送信開始すると、選択した映像ソースをH.264、音声をAACでエンコードし、MPEG-TSとしてPlutoのUDP 8282番ポートへ送出します。RTMPは使用しません。")
                    ManualBullet("送信開始時にPlutoへ周波数・変調・FEC・シンボルレート・ロールオフを反映します。")
                    ManualBullet("送信を停止するとPlutoへのUDP送信も停止します。実運用では送信中に受信を同時開始しません。")
                    ManualBullet("送信画面の音声設定で、マイク音声を送信するか選択できます。")
                    ManualBullet("画面をタップすると操作パネルが表示され、マイク入力レベルをバーでリアルタイム表示します。")
                }
                ManualSection("4. 受信(Rx)画面", sectionModifier("rx", sectionOffsets, scrollState)) {
                    Image(painter = painterResource(R.drawable.manual_rx), contentDescription = "受信画面", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    ManualParagraph("オンデバイス復調がONの場合、PlutoのRF IQをAndroid側でDVB-S2復調し、MPEG-TSをデコードして映像・音声を再生します。")
                    ManualBullet("オンデバイス復調を使用する場合は、送信開始後に受信開始を押します。")
                    ManualBullet("現在のオンデバイス復調器の安定動作上限により、1Msym/s以上を選択しても実効値は0.5Msym/sに制限されます。設定画面の実効値を確認してください。")
                    ManualBullet("同期ロック、推定ロスパケット数、送信UDPパケット数を画面下部に表示します。")
                    ManualBullet("ロック表示は復調器の同期状態です。ロック後も映像が出ない場合は、受信を停止して再開始してください。")
                    ManualBullet("相手側の音声入力レベルをバーでリアルタイム表示します。")
                    ManualBullet("音量スライダーでタブレットの音声出力音量を調整できます(設定は次回起動時も保持)。")
                }
                ManualSection("5. 周波数(Frequency)画面", sectionModifier("frequency", sectionOffsets, scrollState)) {
                    Image(painter = painterResource(R.drawable.manual_frequency), contentDescription = "周波数画面", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    ManualParagraph("運用バンド(1200MHz〜24GHz)を選択すると、日本のアマチュア無線DATVバンドプランに沿った代表周波数が送受信周波数欄(kHz)へ自動反映されます。")
                    ManualBullet("送受信周波数は手動でも変更できます(kHz単位)。")
                    ManualBullet("ここで設定した周波数は、送信/受信開始時にPlutoへ実際に同調されます。")
                }
                ManualSection("6. RSSI測定画面", sectionModifier("find", sectionOffsets, scrollState)) {
                    ManualParagraph("周波数画面で設定した運用周波数を中心に、「周波数±5/10/20MHz」で範囲を選び(画面を開くと±10MHz)、ステップ(既定100 kHz)を入力して「検索開始」を押すと、PlutoのRSSIを範囲の端から順に測定します。「検索停止」で即座に止まります。")
                    Image(painter = painterResource(R.drawable.manual_find), contentDescription = "RSSI測定画面", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    ManualBullet("グラフは値が小さいほど上に描きます(AD9361のRSSIは値が小さいほど信号が強い)。白い破線が中心周波数、黄色の点がその周回で最も強い点です。")
                    ManualBullet("1周ごとにRSSIの変動幅が3 dB以上あれば「最も強い周波数」を更新します。明確なピークが無い周回では前回の結果を残します。")
                    ManualBullet("検索方法は「連続」(「検索停止」まで繰り返す)と「1回」(範囲の終わりで自動停止)から選べます。")
                    ManualBullet("RXゲイン(AGC/手動)はRXゲイン画面と同じ設定で、検索中も変更できます。変更するとその周回を最初からやり直します。")
                    ManualBullet("オンデバイス復調がONのときは、検索開始と同時に送信を開始し、3秒待ってから測定します。検索停止で送信も止まります。受信中は開始できません。他の画面へ移ると検索は止まります。")
                }
                ManualSection("7. シンボルレート(Symbol Rate)画面", sectionModifier("symbolrate", sectionOffsets, scrollState)) {
                    Image(painter = painterResource(R.drawable.manual_symbolrate), contentDescription = "シンボルレート画面", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    ManualParagraph("シンボルレートを選択または手動入力します。設定を変更したら、送信停止・受信停止後に送信開始、受信開始の順で再起動してください。")
                    ManualBullet("実効映像ビットレートは、シンボルレート・変調方式・FECから自動計算し、RF伝送容量を超えないよう制限します。")
                    ManualBullet("オンデバイス復調で1Msym/s以上を選択した場合、実効シンボルレートは0.5Msym/sです。")
                }
                ManualSection("8. 誤り訂正(FEC)画面", sectionModifier("fec", sectionOffsets, scrollState)) {
                    Image(painter = painterResource(R.drawable.manual_fec), contentDescription = "誤り訂正画面", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    ManualParagraph("FEC符号化率を選択し、Pluto互換のFECパラメータとして保存します。")
                }
                ManualSection("9. 変調(Modulation)画面", sectionModifier("modulation", sectionOffsets, scrollState)) {
                    Image(painter = painterResource(R.drawable.manual_modulation), contentDescription = "変調方式画面", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    ManualParagraph("変調方式(QPSK/8PSK)を設定します。")
                }
                ManualSection("10. 映像ソース(Video Source)画面", sectionModifier("videosource", sectionOffsets, scrollState)) {
                    Image(painter = painterResource(R.drawable.manual_videosource), contentDescription = "映像ソース画面", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    ManualParagraph("背面/前面カメラ、カラーバー、または写真フォルダーの写真を選択します。")
                    ManualBullet("カメラと写真の映像には、コールサインを左上、備考と日時を右下に重ねて送信します(日時は毎秒更新)。テストパターンには重ねません。")
                    ManualBullet("コールサイン・備考の文字サイズと文字色は入力欄の横で選べます(文字サイズは1920x1080の映像上での大きさ)。コールサインと備考が両方空欄なら何も重ねません。")
                    ManualBullet("写真ソースでは、画像を縦横比を保ったまま黒い余白で16:9に収め、静止画を30 fpsで繰り返し送信します。")
                }
                ManualSection("11. 配信先(Stream Output)画面", sectionModifier("streamoutput", sectionOffsets, scrollState)) {
                    Image(painter = painterResource(R.drawable.manual_streamoutput), contentDescription = "配信先画面", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    ManualParagraph("送信先IPはPlutoのIPアドレスを設定します。実機宛て送信時はポート指定に関わらずPluto側の固定ポート8282へ送られます。")
                    ManualBullet("「自動検出」を押すと、ESP32ブリッジ経由でネットワーク上のPlutoを検索し、見つかればIPアドレス欄へ自動反映します(最大数十秒)。")
                    ManualBullet("受信設定のTS/ステータスポートは、設定画面でオンデバイス復調がオフの時に、外部復調機器からのUDP-TSを待ち受けるポートです。")
                }
                ManualSection("12. 受信感度(RX Gain)画面", sectionModifier("rxgain", sectionOffsets, scrollState)) {
                    Image(painter = painterResource(R.drawable.manual_rxgain), contentDescription = "受信感度画面", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    ManualParagraph("AGCをONにすると受信ゲインを自動調整します。OFFにするとRX Gainを0〜73 dBで手動設定できます。")
                }
                ManualSection("13. 送信出力(TX Power)画面", sectionModifier("txpower", sectionOffsets, scrollState)) {
                    Image(painter = painterResource(R.drawable.manual_txpower), contentDescription = "送信出力画面", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    ManualParagraph("送信出力の減衰値を-70〜0 dBの範囲で設定します。0 dBが最大出力です(既定値は-40 dB)。")
                }
                ManualSection("14. 設定(Config)画面", sectionModifier("config", sectionOffsets, scrollState)) {
                    Image(painter = painterResource(R.drawable.manual_config), contentDescription = "設定画面", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    ManualParagraph("表示言語(ホーム画面を除く全画面)と、オンデバイス復調(GNU Radio)の有効/無効を設定します。送信先は「配信先」、映像と音声は「映像ソース」、受信ゲインは「受信感度」で設定します。")
                    ManualBullet("オンデバイス復調をONにすると、Pluto 1台でRFのループバック試験ができます(画像を送信しながら同時に受信します)。")
                    ManualBullet("外部アッテネータなしでONにするとTX出力がPlutoのRX入力に直接回り込み、Pluto本体を破損するおそれがあるため、有効化時に必ず警告ダイアログが表示されます。")
                    ManualBullet("オンデバイス復調ONの状態では、ロールオフはPlutoのオンボード変調に合わせて0.35固定(編集不可)で復調されます。")
                }
                ManualSection("15. 機器試験(Diagnostic)画面", sectionModifier("diagnostic", sectionOffsets, scrollState)) {
                    Image(painter = painterResource(R.drawable.manual_diagnostic), contentDescription = "機器試験画面", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    ManualParagraph("「TX/RX単体診断」と「カメラ+音声送出」の2種類の試験ができます。いずれもPluto実機に対しlibiio経由で行いますが、RFを実際に飛ばす送受信画面とは別の診断専用経路です。")
                    ManualBullet("TX/RX単体診断: 「全体試験(TX/RX)」でTXとRXそれぞれの単体動作を確認します。実行中は診断結果欄にログが表示されます。")
                    ManualBullet("カメラ+音声送出診断: カメラ映像とマイク音声を実際にキャプチャし、H.264/AACエンコード→TS多重化→Pluto送出という実運用と同じ経路が健全かを確認します。RX側や実際の復調確認は行いません。")
                    ManualBullet("いずれの試験も送信中・受信中は開始できません。試験中はそれぞれ「中断」で停止できます。")
                    ManualBullet("オンデバイス復調は機器試験ではなく、実際のPluto RFをAndroid側で復調する運用機能(設定画面で切り替え)です。")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnglishManualScreen(navController: NavHostController) {
    var query by remember { mutableStateOf("") }
    val sectionOffsets = remember { mutableStateMapOf<String, Int>() }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val entries = listOf(
        ManualEntry("overview", "1. Overview", listOf("overview", "shonan", "datv", "pluto")),
        ManualEntry("menu", "2. Home Menu", listOf("menu", "main menu", "help")),
        ManualEntry("tx", "3. Transmit (Tx)", listOf("transmit", "tx")),
        ManualEntry("rx", "4. Receive (Rx)", listOf("receive", "rx")),
        ManualEntry("frequency", "5. Frequency", listOf("frequency")),
        ManualEntry("find", "6. RSSI Measurement", listOf("rssi measurement", "find station", "afc", "rssi")),
        ManualEntry("symbolrate", "7. Symbol Rate", listOf("symbol rate")),
        ManualEntry("fec", "8. FEC", listOf("fec", "error correction")),
        ManualEntry("modulation", "9. Modulation", listOf("modulation", "qpsk")),
        ManualEntry("videosource", "10. Video Source", listOf("video", "camera", "color bar")),
        ManualEntry("streamoutput", "11. Stream Output", listOf("stream", "ip", "port")),
        ManualEntry("rxgain", "12. RX Gain", listOf("rx gain", "gain", "agc")),
        ManualEntry("txpower", "13. TX Power", listOf("tx power", "power")),
        ManualEntry("config", "14. Settings", listOf("settings", "config")),
        ManualEntry("diagnostic", "15. Diagnostic", listOf("diagnostic", "test")),
        ManualEntry("credits", "Credits", listOf("credits", "je1bta", "ja6fuf", "jh1xhx", "kazuichi shinjo")),
    )
    val normalizedQuery = normalizeManualSearch(query)
    val match = entries.firstOrNull { entry ->
        normalizedQuery.isNotBlank() && (listOf(entry.title) + entry.terms).any { normalizeManualSearch(it).contains(normalizedQuery) }
    }
    LaunchedEffect(match?.id, sectionOffsets.size) {
        match?.let { sectionOffsets[it.id]?.let { offset -> scrollState.animateScrollTo(offset) } }
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Help") },
            navigationIcon = {
                HomeBackAction({ navController.popBackStack("home", false) }, "Home")
            },
            actions = {
                TextButton(onClick = { scope.launch { scrollState.animateScrollTo(0) } }) { Text("Back to Contents") }
            },
        )
    }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search help") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
            )
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                ManualSection("Credits", sectionModifier("credits", sectionOffsets, scrollState)) {
                    ManualBullet("Receiver method and original receiver system design: Shinji Yamazaki (JE1BTA), based on rpi-dvbs2-receiver-gui.")
                    ManualBullet("Receiver stabilization, reacquisition fixes, and application development: Kazuichi Shinjo (JA6FUF/JH1XHX).")
                    ManualBullet("This application was developed inspired by \"Portsdown\", the DATV transceiver project created by Dave Crump (G8GKQ). We extend our deep gratitude for his pioneering work.")
                    ManualBullet("The developers accept no liability whatsoever for any damage arising from the use of this program. Use it at your own risk.")
                }
                ManualSection("Contents", sectionModifier("toc", sectionOffsets, scrollState)) {
                    entries.forEach { entry ->
                        TextButton(onClick = { scope.launch { sectionOffsets[entry.id]?.let { offset -> scrollState.animateScrollTo(offset) } } }) {
                            Text(entry.title, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                ManualSection("1. Overview", sectionModifier("overview", sectionOffsets, scrollState)) {
                    ManualParagraph("Shonan for Android is a tablet application for DVB-S2 DATV transmission and reception through Pluto.")
                    ManualBullet("Transmit (Tx): encodes video and audio as H.264/AAC and sends MPEG-TS to Pluto over UDP.")
                    ManualBullet("Receive (Rx): when on-device demodulation is enabled, Android demodulates Pluto RF and plays the video and audio.")
                    ManualBullet("Pluto modulation settings and the UDP receiving path are prepared automatically when a session starts.")
                }
                ManualSection("2. Home Menu", sectionModifier("menu", sectionOffsets, scrollState)) {
                    Image(painter = painterResource(R.drawable.manual_home), contentDescription = "Home screen", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    ManualTable(listOf("Button", "Function"), listOf(
                        listOf("RX Gain", "Configure AGC and manual receive gain"),
                        listOf("TX Power", "Configure transmit power attenuation"),
                        listOf("Settings", "Display language, on-device demodulation (RF loopback), and other basic settings"),
                        listOf("Diagnostic", "Run TX/RX diagnostic and camera + audio transmission diagnostic"),
                        listOf("RSSI Measurement", "Scan a range for RSSI and find the best frequency"),
                        listOf("Help", "Open this user guide"),
                        listOf("App Restart", "Send a reboot request to Pluto and wait for recovery"),
                        listOf("Quit", "Exit the application"),
                    ))
                }
                ManualSection("3. Transmit (Tx)", sectionModifier("tx", sectionOffsets, scrollState)) {
                    Image(painter = painterResource(R.drawable.manual_tx), contentDescription = "Transmit screen", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    ManualParagraph("When transmission starts, the selected video source is encoded as H.264, audio as AAC, and MPEG-TS is sent to Pluto over UDP.")
                    ManualBullet("Frequency, modulation, FEC, symbol rate, and roll-off are applied to Pluto at startup.")
                    ManualBullet("Stopping transmission also stops UDP output to Pluto.")
                    ManualBullet("The audio setting on the transmit screen selects whether microphone audio is sent.")
                }
                ManualSection("4. Receive (Rx)", sectionModifier("rx", sectionOffsets, scrollState)) {
                    Image(painter = painterResource(R.drawable.manual_rx), contentDescription = "Receive screen", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    ManualParagraph("With on-device demodulation enabled, Android demodulates Pluto RF using DVB-S2 and plays the decoded video and audio.")
                    ManualBullet("For on-device demodulation, start reception after starting transmission.")
                    ManualBullet("The effective symbol rate may be limited to 0.5 Msym/s by the current demodulator stability limit.")
                    ManualBullet("Sync lock, estimated lost packets, and transmitted UDP packet count are shown at the bottom of the screen.")
                    ManualBullet("Use the volume slider to adjust tablet audio output; the setting is retained for the next launch.")
                }
                ManualSection("5. Frequency", sectionModifier("frequency", sectionOffsets, scrollState)) {
                    Image(painter = painterResource(R.drawable.manual_frequency), contentDescription = "Frequency screen", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    ManualParagraph("Select an operating band to apply a representative TX/RX frequency based on the Japanese amateur-radio DATV band plan.")
                    ManualBullet("The TX/RX frequency can also be changed manually in kHz.")
                    ManualBullet("The selected frequency is tuned on Pluto when transmission or reception starts.")
                }
                ManualSection("6. RSSI Measurement", sectionModifier("find", sectionOffsets, scrollState)) {
                    ManualParagraph("Choose a range around the operating frequency set on the Frequency screen with the ±5/10/20 MHz buttons (±10 MHz when the screen opens), enter a step (100 kHz by default), and tap Start Search to measure Pluto's RSSI across the range. Stop Search stops immediately.")
                    Image(painter = painterResource(R.drawable.manual_find), contentDescription = "RSSI Measurement screen", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    ManualBullet("Smaller values are drawn higher (a smaller AD9361 RSSI means a stronger signal). The white dashed line is the center frequency and the yellow dot is the strongest point of the sweep.")
                    ManualBullet("After each sweep the strongest frequency is updated only if the RSSI varied by 3 dB or more; otherwise the previous result is kept.")
                    ManualBullet("Search Mode: Repeat (until Stop Search) or Once (stops at the end of the range).")
                    ManualBullet("RX gain (AGC/manual) shares the RX Gain screen setting and can be changed while searching; the sweep restarts when it changes.")
                    ManualBullet("With on-device demodulation ON, TX starts together with the search and measurement begins after 3 seconds; stopping the search also stops TX. It cannot start while receiving, and it stops when you leave the screen.")
                }
                ManualSection("7. Symbol Rate", sectionModifier("symbolrate", sectionOffsets, scrollState)) {
                    Image(painter = painterResource(R.drawable.manual_symbolrate), contentDescription = "Symbol Rate screen", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    ManualParagraph("Select or manually enter the symbol rate. Restart transmission and reception after changing this setting.")
                    ManualBullet("The effective video bitrate is calculated from symbol rate, modulation, and FEC, and is limited to the RF capacity.")
                }
                ManualSection("8. FEC", sectionModifier("fec", sectionOffsets, scrollState)) {
                    Image(painter = painterResource(R.drawable.manual_fec), contentDescription = "FEC screen", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    ManualParagraph("Select the FEC coding rate and save it as a Pluto-compatible FEC parameter.")
                }
                ManualSection("9. Modulation", sectionModifier("modulation", sectionOffsets, scrollState)) {
                    Image(painter = painterResource(R.drawable.manual_modulation), contentDescription = "Modulation screen", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    ManualParagraph("Configure the modulation scheme: QPSK or 8PSK.")
                }
                ManualSection("10. Video Source", sectionModifier("videosource", sectionOffsets, scrollState)) {
                    Image(painter = painterResource(R.drawable.manual_videosource), contentDescription = "Video Source screen", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    ManualParagraph("Select the rear or front camera, color bars, or a photo from the photo folder.")
                    ManualBullet("Camera and photo video is overlaid with the callsign (top left) and the note and date/time (bottom right, updated every second). The test pattern is not overlaid.")
                    ManualBullet("The font size and color of the callsign and note can be selected next to each input field (sizes are on the 1920x1080 frame). Nothing is overlaid when both the callsign and note are empty.")
                    ManualBullet("Photo sources fit the image into 16:9 with black borders, keeping its aspect ratio, and transmit the still image repeatedly at 30 fps.")
                }
                ManualSection("11. Stream Output", sectionModifier("streamoutput", sectionOffsets, scrollState)) {
                    Image(painter = painterResource(R.drawable.manual_streamoutput), contentDescription = "Stream Output screen", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    ManualParagraph("Set the Pluto IP address as the destination. When targeting real Pluto hardware, data always goes to its fixed port 8282 regardless of this field.")
                    ManualBullet("Tap \"Auto-Detect\" to search for Pluto on the network via the ESP32 bridge; the IP address field is filled in automatically if found (up to about a minute).")
                    ManualBullet("The TS/Status ports under Receive Settings are used to listen for UDP-TS from an external demodulator when on-device demodulation is off on the Settings screen.")
                }
                ManualSection("12. RX Gain", sectionModifier("rxgain", sectionOffsets, scrollState)) {
                    Image(painter = painterResource(R.drawable.manual_rxgain), contentDescription = "RX Gain screen", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    ManualParagraph("Enable AGC for automatic gain control. When disabled, set RX Gain manually from 0 to 73 dB.")
                }
                ManualSection("13. TX Power", sectionModifier("txpower", sectionOffsets, scrollState)) {
                    Image(painter = painterResource(R.drawable.manual_txpower), contentDescription = "TX Power screen", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    ManualParagraph("Set transmit power attenuation from -70 to 0 dB. 0 dB is maximum output.")
                }
                ManualSection("14. Settings", sectionModifier("config", sectionOffsets, scrollState)) {
                    Image(painter = painterResource(R.drawable.manual_config), contentDescription = "Settings screen", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    ManualParagraph("Configure the display language (all screens except Home) and whether on-device demodulation (GNU Radio) is enabled. Destination, video/audio, and receive gain are configured in their respective menu screens.")
                    ManualBullet("Enabling on-device demodulation lets you run an RF loopback test with a single Pluto: you transmit an image while simultaneously receiving that same image.")
                    ManualBullet("Enabling it without an external attenuator can let TX output feed straight back into Pluto's RX input and damage the Pluto, so a warning dialog is always shown before it is enabled.")
                    ManualBullet("While on-device demodulation is on, roll-off is fixed at 0.35 (not editable) to match Pluto's onboard modulator.")
                }
                ManualSection("15. Diagnostic", sectionModifier("diagnostic", sectionOffsets, scrollState)) {
                    Image(painter = painterResource(R.drawable.manual_diagnostic), contentDescription = "Diagnostic screen", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    ManualParagraph("Two tests are available: \"TX/RX Diagnostic\" and \"Camera + Audio\". Both talk to the real Pluto over libiio, through a dedicated diagnostic path separate from the Transmit/Receive screens that actually put RF on the air.")
                    ManualBullet("TX/RX Diagnostic: \"Run TX/RX Test\" checks TX and RX operation individually; the log is shown in the result panel while running.")
                    ManualBullet("Camera + Audio: captures real camera video and microphone audio and checks the same H.264/AAC encoding, TS muxing, and Pluto TX path used in operation. It does not test RX or actual demodulation.")
                    ManualBullet("Neither test can start while transmitting or receiving; each can be stopped with Cancel while running.")
                    ManualBullet("On-device demodulation is an operational feature (toggled on the Settings screen) that demodulates actual Pluto RF on Android, not a diagnostic test.")
                }
            }
        }
    }
}

private fun sectionModifier(id: String, offsets: MutableMap<String, Int>, scrollState: androidx.compose.foundation.ScrollState): Modifier =
    Modifier.onGloballyPositioned { coordinates -> offsets[id] = scrollState.value + coordinates.positionInParent().y.toInt() }

private fun normalizeManualSearch(value: String): String = value.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

@Composable
private fun ManualSection(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier = modifier) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = { content() })
    }
}

@Composable private fun ManualParagraph(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium)
@Composable private fun ManualBullet(text: String) = Row { Text("・"); Text(text, style = MaterialTheme.typography.bodyMedium) }

@Composable
private fun ManualTable(headers: List<String>, rows: List<List<String>>) {
    Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant).padding(4.dp)) {
        ManualTableRow(headers, true)
        rows.forEach { ManualTableRow(it, false) }
    }
}

@Composable
private fun ManualTableRow(cells: List<String>, header: Boolean) = Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
    cells.forEach { Text(it, modifier = Modifier.weight(1f).padding(horizontal = 4.dp), fontWeight = if (header) FontWeight.Bold else FontWeight.Normal, style = MaterialTheme.typography.bodySmall) }
}
