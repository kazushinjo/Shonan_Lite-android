package com.shinjo.shonanandroid.ui

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
import com.shinjo.shonanandroid.net.PlutoRebootController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class HomeMenuButton(val titleJA: String, val titleEN: String, val route: String)

private val homeMenuButtons = listOf(
    HomeMenuButton("送信", "Transmit", "tx"),
    HomeMenuButton("受信", "Receive", "rx"),
    HomeMenuButton("周波数", "Frequency", "frequency"),
    HomeMenuButton("相手局検索", "Find Station", "find"),
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

    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!viewModel.didRunStartupPlutoReboot) {
            runPlutoStartupReboot(settings.txDestinationIP)
            viewModel.didRunStartupPlutoReboot = true
            plutoRebootInProgress = false
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
                    plutoRebootInProgress = true
                    scope.launch(Dispatchers.IO) {
                        runPlutoStartupReboot(settings.txDestinationIP)
                        withContext(Dispatchers.Main) {
                            plutoRebootInProgress = false
                        }
                    }
                }
                "quit" -> showQuitConfirmation = true
                else -> navController.navigate(button.route)
            }
        }

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
                    Text(
                        text = settings.t("Pluto再起動中…", "Rebooting Pluto…"),
                        color = Color.White,
                        fontSize = 18.sp,
                        modifier = Modifier.padding(top = 16.dp),
                    )
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
                Text("⏻", color = Color(0xFFF05A45), fontSize = 56.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
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
