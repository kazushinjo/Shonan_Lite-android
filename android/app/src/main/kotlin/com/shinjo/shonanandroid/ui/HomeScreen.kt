package com.shinjo.shonanandroid.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.CircularProgressIndicator
import androidx.navigation.NavHostController
import com.shinjo.shonanandroid.AppViewModel
import com.shinjo.shonanandroid.net.PlutoDiscoveryClient
import com.shinjo.shonanandroid.net.PlutoRebootController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class HomeMenuButton(val titleJA: String, val titleEN: String, val route: String)

/**
 * 背景画像(home_background)に焼き込まれた旧カード文字を隠すための重ね描き矩形
 * (native座標系、1575x999基準)。アイコン部分は焼き込みのまま流用し、文字部分だけ
 * 不透明矩形で覆って`homeMenuButtons`の現在のラベルを重ね描きする
 * (Windows/Pi5版の`_add_card_label`相当)。座標はアイコン終端・旧文字終端を
 * 画像の輝度プロファイルから自動検出して算出。
 */
private data class CardOverlayRect(val x: Float, val y: Float, val width: Float, val height: Float)

private val cardOverlayRects = mapOf(
    "tx" to CardOverlayRect(148f, 202f, 224f, 85f),
    "rx" to CardOverlayRect(494f, 202f, 216f, 85f),
    // 「周波数」カードのみ、下段の動的な周波数値表示(y=271〜)と重ならないよう高さを抑える。
    "frequency" to CardOverlayRect(838f, 190f, 187f, 78f),
    "find" to CardOverlayRect(1146f, 202f, 169f, 85f),
    "symbolrate" to CardOverlayRect(153f, 360f, 219f, 85f),
    "fec" to CardOverlayRect(484f, 360f, 225f, 85f),
    "modulation" to CardOverlayRect(828f, 360f, 197f, 85f),
    "videosource" to CardOverlayRect(1148f, 360f, 167f, 85f),
    "streamoutput" to CardOverlayRect(148f, 507f, 223f, 85f),
    "rxgain" to CardOverlayRect(483f, 507f, 226f, 85f),
    "txpower" to CardOverlayRect(826f, 507f, 199f, 85f),
    "settings" to CardOverlayRect(1167f, 507f, 148f, 85f),
    "testequipment" to CardOverlayRect(154f, 651f, 217f, 85f),
    "manual" to CardOverlayRect(487f, 651f, 222f, 85f),
    "pluto_reboot" to CardOverlayRect(820f, 651f, 205f, 85f),
    "quit" to CardOverlayRect(1140f, 651f, 178f, 85f),
)

private val homeMenuButtons = listOf(
    HomeMenuButton("送信", "Transmit", "tx"),
    HomeMenuButton("受信", "Receive", "rx"),
    HomeMenuButton("周波数", "Frequency", "frequency"),
    HomeMenuButton("RSSI測定", "RSSI Measurement", "find"),
    HomeMenuButton("シンボルレート", "Symbol Rate", "symbolrate"),
    HomeMenuButton("誤り訂正", "FEC", "fec"),
    HomeMenuButton("変調", "Modulation", "modulation"),
    HomeMenuButton("映像ソース", "Video Source", "videosource"),
    HomeMenuButton("配信先", "Stream Output", "streamoutput"),
    HomeMenuButton("受信感度", "RX Gain", "rxgain"),
    HomeMenuButton("送信出力", "TX Power", "txpower"),
    HomeMenuButton("設定", "Config", "settings"),
    HomeMenuButton("機器試験", "Diagnostic", "testequipment"),
    HomeMenuButton("ヘルプ", "Help", "manual"),
    HomeMenuButton("アプリ再起動", "App Restart", "pluto_reboot"),
    HomeMenuButton("プログラム終了", "Quit", "quit"),
)

/**
 * PlutoへSSH経由でreboot要求を送り、Web UIとiiod両方の復旧を確認するまで待つ
 * -- iPad版`ContentView.runPlutoStartupReboot()`と同じ処理。起動時の初回実行と、
 * ホーム画面の「アプリ再起動」カードの両方から呼ぶ共通処理とする(iPad版も同様に
 * 両方から`runPlutoStartupReboot()`を共用している)。
 */
private suspend fun runPlutoStartupReboot(host: String) {
    val trimmed = host.trim()
    if (trimmed.isEmpty()) return
    val started = PlutoRebootController.rebootAtStartup(trimmed, timeoutMillis = 20_000L)
    if (started) {
        PlutoRebootController.waitUntilOnline(trimmed) { }
    }
}

/** iPad版と同じ背景画像に、各カードと一致するタップ領域を重ねたホーム画面。 */
@Composable
fun HomeScreen(viewModel: AppViewModel, navController: NavHostController) {
    val settings = viewModel.settings
    val scope = rememberCoroutineScope()
    var showQuitConfirmation by remember { mutableStateOf(false) }
    // iPad版の`startupState`に相当。起動時の初回実行と「アプリ再起動」カードの
    // 両方がこの同じフルスクリーン表示をトリガーする(二重実装を避けるため
    // 独立した状態やUIを別途持たない)。
    var plutoRebootInProgress by remember { mutableStateOf(!viewModel.didRunStartupPlutoReboot) }
    // 「アプリ再起動」カードから起動した場合のみtrue。Win/Pi4/Pi5版と同様、
    // 「アプリを再起動しています…」に加えて「Plutoも再起動しています…」を表示するため、
    // 起動時の自動Pluto再起動(このフラグはfalseのまま)と表示を区別する。
    var manualAppRestartInProgress by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!viewModel.didRunStartupPlutoReboot) {
            runPlutoStartupReboot(settings.txDestinationIP)
            viewModel.didRunStartupPlutoReboot = true
            plutoRebootInProgress = false
        }
    }

    // 起動時に一度だけPluto自動検出を試みる: まずタブレット自身の接続中サブネットを
    // スキャンし(一般的なWiFiルーター運用でも動作)、見つからなければESP32ブリッジ
    // (hardware/ESP32_WiFi_Ethernet_Bridge)のAPIにフォールバックする。どちらも
    // 見つからない場合は数秒で失敗するだけなので、UIをブロックせずエラー表示もしない。
    val discoveryContext = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!viewModel.didRunStartupPlutoDiscovery) {
            viewModel.didRunStartupPlutoDiscovery = true
            val found = PlutoDiscoveryClient.discoverPlutoIp(discoveryContext)
            if (found != null && found != settings.txDestinationIP) {
                viewModel.updateSettings { s -> s.copy(txDestinationIP = found) }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val sourceWidth = 1575f
        val sourceHeight = 999f
        val scale = minOf(maxWidth.value / sourceWidth, maxHeight.value / sourceHeight)
        val imageWidth = sourceWidth * scale
        val imageHeight = sourceHeight * scale
        val offsetX = (maxWidth.value - imageWidth) / 2f
        val offsetY = (maxHeight.value - imageHeight) / 2f

        Image(
            painter = painterResource(com.shinjo.shonanandroid.R.drawable.home_background),
            contentDescription = null,
            modifier = Modifier
                .width(imageWidth.dp)
                .height(imageHeight.dp)
                .offset(offsetX.dp, offsetY.dp),
        )

        val onMenuClick: (HomeMenuButton) -> Unit = { button ->
            when (button.route) {
                "pluto_reboot" -> {
                    // iPad版`ContentView.restartApp()`と同じソフトリスタート: プロセスは終了させず、
                    // 送受信セッションを停止してから起動時と同じrunPlutoStartupReboot()を再実行し、
                    // 完了するまで起動時と同じフルスクリーン表示でブロックする。
                    if (viewModel.isTransmitting) viewModel.stopTX()
                    if (viewModel.isReceiving) viewModel.stopRX()
                    manualAppRestartInProgress = true
                    plutoRebootInProgress = true
                    scope.launch(Dispatchers.IO) {
                        runPlutoStartupReboot(settings.txDestinationIP)
                        withContext(Dispatchers.Main) {
                            plutoRebootInProgress = false
                            manualAppRestartInProgress = false
                        }
                    }
                }
                "quit" -> showQuitConfirmation = true
                else -> navController.navigate(button.route)
            }
        }

        Text(
            text = "${settings.effectiveLoHz / 1_000} kHz",
            color = Color.White,
            fontSize = (20f * scale).sp,
            modifier = Modifier.offset((offsetX + 856f * scale).dp, (offsetY + 271f * scale).dp),
        )

        val cardX = floatArrayOf(64f, 402f, 738f, 1058f)
        val cardWidth = floatArrayOf(315f, 315f, 295f, 265f)
        val cardY = floatArrayOf(175f, 333f, 480f, 624f)
        homeMenuButtons.forEachIndexed { index, button ->
            val column = index % 4
            val row = index / 4
            HomeTapTarget(
                x = cardX[column],
                y = cardY[row],
                width = cardWidth[column],
                height = 135f,
                scale = scale,
                offsetX = offsetX,
                offsetY = offsetY,
                enabled = !plutoRebootInProgress,
                onClick = { onMenuClick(button) },
            )
        }

        homeMenuButtons.forEach { button ->
            val rect = cardOverlayRects[button.route] ?: return@forEach
            HomeCardTextOverlay(
                japanese = button.titleJA,
                english = button.titleEN,
                x = rect.x,
                y = rect.y,
                width = rect.width,
                height = rect.height,
                scale = scale,
                offsetX = offsetX,
                offsetY = offsetY,
            )
        }

        if (plutoRebootInProgress) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            ) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(color = Color.White)
                    if (manualAppRestartInProgress) {
                        Text(
                            text = settings.t("アプリを再起動しています…", "Restarting the app…"),
                            color = Color.White,
                            fontSize = 18.sp,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                        Text(
                            text = settings.t("Plutoも再起動しています…", "Pluto is also restarting…"),
                            color = Color(0xFF0C9BC0),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    } else {
                        Text(
                            text = settings.t("Pluto再起動中…", "Rebooting Pluto…"),
                            color = Color.White,
                            fontSize = 18.sp,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                    Text(
                        text = settings.t("20秒以内に開始できない場合は起動を続行します", "The app will continue after 20 seconds if reboot does not start."),
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }

    if (showQuitConfirmation) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showQuitConfirmation = false }) {
            Column(
                modifier = Modifier
                    .background(Color(0xFF101416), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .padding(30.dp, 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            ) {
                PowerIcon(color = Color(0xFFF05A45), modifier = Modifier.size(56.dp))
                Text(
                    settings.t("プログラムを終了します。\nよろしいですか？", "The program will quit.\nAre you sure?"),
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                    androidx.compose.material3.Button(
                        onClick = { android.os.Process.killProcess(android.os.Process.myPid()) },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFFF05A45)),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    ) {
                        Text(settings.t("終了する", "Quit"), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    }
                    androidx.compose.material3.OutlinedButton(
                        onClick = { showQuitConfirmation = false },
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3B5159)),
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    ) {
                        Text(settings.t("キャンセル", "Cancel"), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeTapTarget(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width((width * scale).dp)
            .height((height * scale).dp)
            .offset((offsetX + x * scale).dp, (offsetY + y * scale).dp)
            .clickable(enabled = enabled, onClick = onClick),
    )
}

/**
 * 背景画像に焼き込まれたカード文字を隠し、Compose側で日本語(大)+英語(小)の
 * 2行を重ね描きする(Windows/Pi5版の`_add_card_label`相当)。
 */
@Composable
private fun HomeCardTextOverlay(
    japanese: String,
    english: String,
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
) {
    Column(
        modifier = Modifier
            .width((width * scale).dp)
            .height((height * scale).dp)
            .offset((offsetX + x * scale).dp, (offsetY + y * scale).dp)
            .background(Color(0xFF03101F)),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Text(
            japanese,
            color = Color.White,
            fontSize = (19f * scale).sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
        )
        Text(
            english,
            color = Color.White,
            fontSize = (12f * scale).sp,
            maxLines = 1,
            softWrap = false,
            overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
        )
    }
}

/**
 * 一般的な電源記号(丸に上部の切れ目+縦線)をCanvasで直接描画する -- Unicode文字
 * ⏻(U+23FB)は実機フォントに含まれずグリフ欠落(豆腐)表示になるため、フォント
 * 依存を避けてベクター描画にする。
 */
@Composable
private fun PowerIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val strokeWidth = size.minDimension * 0.12f
        val inset = strokeWidth / 2f
        val arcSize = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
        drawArc(
            color = color,
            startAngle = -55f,
            sweepAngle = 290f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
            size = arcSize,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(size.width / 2f, 0f),
            end = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height * 0.5f),
            strokeWidth = strokeWidth,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}
