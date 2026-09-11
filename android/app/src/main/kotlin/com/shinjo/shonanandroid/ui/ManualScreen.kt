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
    ManualEntry("find", "6. 相手局検索画面", listOf("相手局検索", "afc", "rssi", "電波強度", "グラフ", "自動周波数")),
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
                        listOf("設定 / Config", "バンドなどの基本設定"),
                        listOf("機器試験 / Diagnostic", "TX/RXの単体診断"),
                        listOf("相手局検索 / Find Station", "RSSIを走査し、最も強い受信周波数を設定"),
                        listOf("ヘルプ / Help", "この操作説明書を表示"),
                        listOf("Pluto再起動", "Plutoを再起動"),
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
                ManualSection("6. 相手局検索画面", sectionModifier("find", sectionOffsets, scrollState)) {
                    ManualParagraph("相手局検索は中心周波数の前後を10 kHz刻みでストップを押すまで往復走査を続け、PlutoのRSSIが最も高い周波数を探します。±100 kHzまたは±500 kHzを選び、スタートを押してください。")
                    Image(painter = painterResource(R.drawable.manual_find), contentDescription = "相手局検索画面", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    ManualBullet("測定中は現在のRSSIと周波数を表示し、RSSIグラフへ測定点をリアルタイム追加します。")
                    ManualBullet("ストップを押すと、その時点で最もRSSIが高かった周波数を送受信周波数に設定します。一度もRSSIを取得できなかった場合は、走査前の周波数のまま変更しません。")
                    ManualBullet("送信中または受信中は相手局検索を開始できず、理由を表示します。")
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
                    ManualParagraph("変調方式(QPSK/8PSK/16APSK/32APSK)を設定します。")
                }
                ManualSection("10. 映像ソース(Video Source)画面", sectionModifier("videosource", sectionOffsets, scrollState)) {
                    Image(painter = painterResource(R.drawable.manual_videosource), contentDescription = "映像ソース画面", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    ManualParagraph("背面/前面カメラ、カラーバー、または写真フォルダーの写真を選択します。")
                    ManualBullet("写真を選択すると、画像へコールサインを左上、送信開始日時と備考を右下に重ねて送信します。")
                    ManualBullet("コールサインは128 px相当、日時と備考は24 px相当で、背景は透明です。")
                    ManualBullet("写真ソースでは、映像は静止画を30 fpsで繰り返し送信します。")
                }
                ManualSection("11. 配信先(Stream Output)画面", sectionModifier("streamoutput", sectionOffsets, scrollState)) {
                    Image(painter = painterResource(R.drawable.manual_streamoutput), contentDescription = "配信先画面", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    ManualParagraph("送信先IPはPlutoのIPアドレスを設定します。実運用のUDP TS送信先ポートは8282番に固定されています。")
                    ManualBullet("受信側のUDPポート設定は、外部復調器からUDP-TSを受ける場合に使用します。")
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
                    ManualParagraph("オンデバイス復調、表示言語、ロールオフなど全体的な基本設定を編集します。送信先は「配信先」、映像と音声は「映像ソース」、受信ゲインは「受信感度」で設定します。")
                    ManualBullet("オンデバイス復調ONでは、同じPlutoを介した送信と受信を同時に実行できます。")
                }
                ManualSection("15. 機器試験(Diagnostic)画面", sectionModifier("diagnostic", sectionOffsets, scrollState)) {
                    Image(painter = painterResource(R.drawable.manual_diagnostic), contentDescription = "機器試験画面", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    ManualParagraph("Pluto単体のRF経路を使わないデジタルループバック試験と、カメラ・音声送出経路の診断を実行します。")
                    ManualBullet("オンデバイス復調は機器試験ではなく、実際のPluto RFをAndroid側で復調する運用機能です。")
                    ManualBullet("試験中のログやエラー表示を確認し、通常運用では送信・受信画面を使用してください。")
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
        ManualEntry("find", "6. Find Station", listOf("find", "afc", "rssi")),
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
                        listOf("Settings", "Configure band and other basic settings"),
                        listOf("Diagnostic", "Run TX/RX component diagnostics"),
                        listOf("Find Station", "Scan RSSI and select the strongest frequency"),
                        listOf("Help", "Open this user guide"),
                        listOf("Reboot Pluto", "Restart Pluto"),
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
                ManualSection("6. Find Station", sectionModifier("find", sectionOffsets, scrollState)) {
                    ManualParagraph("Find Station scans in 10 kHz steps around the center frequency until stopped and searches for the frequency with the highest Pluto RSSI.")
                    Image(painter = painterResource(R.drawable.manual_find), contentDescription = "Find Station screen", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    ManualBullet("The current RSSI and frequency are shown while measurement points are added to the graph.")
                    ManualBullet("When stopped, the strongest measured frequency is applied. If no RSSI was received, the original frequency is retained.")
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
                    ManualParagraph("Configure the modulation scheme: QPSK, 8PSK, 16APSK, or 32APSK.")
                }
                ManualSection("10. Video Source", sectionModifier("videosource", sectionOffsets, scrollState)) {
                    Image(painter = painterResource(R.drawable.manual_videosource), contentDescription = "Video Source screen", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    ManualParagraph("Select the rear or front camera, color bars, or a photo from the photo folder.")
                    ManualBullet("Selected photos are overlaid with the callsign, transmission start time, and note before transmission.")
                    ManualBullet("Photo sources transmit the still image repeatedly at 30 fps.")
                }
                ManualSection("11. Stream Output", sectionModifier("streamoutput", sectionOffsets, scrollState)) {
                    Image(painter = painterResource(R.drawable.manual_streamoutput), contentDescription = "Stream Output screen", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    ManualParagraph("Set the Pluto IP address as the destination. The production UDP TS destination port is fixed at 8282.")
                    ManualBullet("The receive UDP port is used when receiving UDP-TS from an external demodulator.")
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
                    ManualParagraph("Configure on-device demodulation, display language, roll-off, and other global settings. Destination, video/audio, and receive gain are configured in their respective menu screens.")
                    ManualBullet("When on-device demodulation is enabled, transmission and reception through the same Pluto can run simultaneously.")
                }
                ManualSection("15. Diagnostic", sectionModifier("diagnostic", sectionOffsets, scrollState)) {
                    Image(painter = painterResource(R.drawable.manual_diagnostic), contentDescription = "Diagnostic screen", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    ManualParagraph("Run a digital loopback test and a camera/audio transmission-path diagnostic without using the Pluto RF path.")
                    ManualBullet("On-device demodulation is an operational feature that demodulates actual Pluto RF on Android, not a diagnostic test.")
                    ManualBullet("Check logs and errors during testing; use the transmit and receive screens for normal operation.")
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
