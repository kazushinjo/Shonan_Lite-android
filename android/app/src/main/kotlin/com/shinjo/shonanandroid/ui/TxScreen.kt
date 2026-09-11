package com.shinjo.shonanandroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.shinjo.shonanandroid.AppViewModel
import com.shinjo.shonanandroid.tx.CameraPosition
import com.shinjo.shonanandroid.tx.CameraPreviewView
import com.shinjo.shonanandroid.tx.ColorBarPreview
import com.shinjo.shonanandroid.tx.PhotoPreview

private val CardBackground = Color(0xFF191D1F)
private val DividerColor = Color(0xFF303538)
private val CaptionColor = Color(0xFF9AA0A6)
private val SecondaryButtonColor = Color(0xFF303538)
private val StartColor = Color(0xFF1677FF)
private val StopColor = Color(0xFFD02020)
private val LockedGreen = Color(0xFF20C020)
private val IdleGray = Color(0xFF5A5A5A)

/**
 * 送信画面 -- Shonan_Lite-pi5(`pi5/gui/screens/tx.py`)と同じカードレイアウトに統一。
 * 左に映像プレビュー、右に固定幅ダークステータスカード(周波数/シンボルレート/変調方式/FECの
 * 2x2グリッド、区切り線、統計2x2グリッド)、下段にボタン行を並べる([[shonan-lite-ipad-tx-rx-pi5-design]]と同様の移植)。
 */
@Composable
fun TxScreen(viewModel: AppViewModel, navController: NavHostController) {
    val settings = viewModel.settings
    val stats = viewModel.activeTxStats
    val running = viewModel.isTransmitting
    val preparing = viewModel.isPreparingTx

    // iPad版`TxView`のonAppear/onDisappearと同じく、送信中でない間もカメラ映像を
    // 確認できるよう、画面表示中だけカメラプレビューを起動する。
    DisposableEffect(settings.useFrontCamera, settings.useColorBarSource, settings.usePhotoSource, running) {
        val isCameraSource = !settings.useColorBarSource && !settings.usePhotoSource
        if (isCameraSource && !running) {
            viewModel.txController.startCameraPreview(if (settings.useFrontCamera) CameraPosition.FRONT else CameraPosition.BACK)
        } else {
            viewModel.txController.stopCameraPreviewIfIdle()
        }
        onDispose { viewModel.txController.stopCameraPreviewIfIdle() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color.Black, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (settings.usePhotoSource) {
                    PhotoPreview(
                        uri = settings.selectedPhotoUri,
                        callsign = settings.photoCallsign,
                        note = settings.photoNote,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (settings.useColorBarSource) {
                    ColorBarPreview(modifier = Modifier.fillMaxSize())
                } else {
                    CameraPreviewView(preview = viewModel.activeTxPreview, modifier = Modifier.fillMaxSize())
                }
            }

            Column(
                modifier = Modifier
                    .width(270.dp)
                    .fillMaxHeight()
                    .background(CardBackground, RoundedCornerShape(12.dp))
                    .padding(14.dp, 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(if (running) LockedGreen else IdleGray, CircleShape),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (running) settings.t("送信中", "Transmitting") else settings.t("送信停止中", "Transmit stopped"),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (running) {
                        Box(
                            modifier = Modifier
                                .background(StopColor, RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text("ON AIR", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                StatusFieldRow(
                    settings.t("周波数", "Frequency") to "${settings.effectiveLoHz / 1_000} kHz",
                    settings.t("シンボルレート", "Symbol rate") to "%.1f Msym/s".format(settings.symbolRateMsps),
                )
                StatusFieldRow(
                    settings.t("変調方式", "Modulation") to settings.modulationScheme.label,
                    "FEC" to settings.fecRate.label,
                )

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(DividerColor))

                StatusFieldRow(
                    settings.t("出力", "Power") to "${settings.txPowerDb} dBm",
                    settings.t("合計パケット数", "Total packets") to "${stats.totalPackets}",
                )
                StatusFieldRow(
                    settings.t("ビットレート", "Bitrate") to "%.2f Mbps".format(stats.bitsPerSecond / 1_000_000),
                    settings.t("パケット/秒", "Packets/sec") to "${stats.packetsPerSecond}",
                )

                Text(
                    if (stats.isConnected) settings.t("接続中", "Connected") else settings.t("切断中", "Disconnected"),
                    color = if (stats.isConnected) LockedGreen else StopColor,
                    fontSize = 12.sp,
                )
                Text(settings.t("音声レベル", "Audio Level"), color = CaptionColor, fontSize = 12.sp)
                LinearProgressIndicator(
                    progress = { stats.audioLevel },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                )

                viewModel.txError?.let {
                    Text(
                        settings.t("エラー: ${runtimeText(it, settings.language)}", "Error: ${runtimeText(it, settings.language)}"),
                        color = Color(0xFFF44336),
                        fontSize = 12.sp,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            Button(
                onClick = { if (running) viewModel.stopTX() else viewModel.startTX() },
                enabled = !preparing,
                colors = ButtonDefaults.buttonColors(containerColor = if (running) StopColor else StartColor),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    when {
                        preparing -> settings.t("準備中…", "Starting…")
                        running -> settings.t("送信停止", "Stop")
                        else -> settings.t("送信開始", "Start")
                    },
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (settings.useOnDeviceGRDVBS2Rx) {
                SecondaryButton(settings.t("受信画面へ", "Receive Screen")) {
                    navController.navigate("rx")
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            SecondaryButton(settings.t("設定", "Settings")) {
                navController.navigate("settings")
            }
            Spacer(modifier = Modifier.width(8.dp))
            SecondaryButton(settings.t("ホームへ戻る", "Home")) {
                navController.popBackStack("home", false)
            }
        }
    }
}

@Composable
private fun StatusFieldRow(vararg fields: Pair<String, String>) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        fields.forEach { (caption, value) ->
            Column(modifier = Modifier.weight(1f)) {
                Text(caption, color = CaptionColor, fontSize = 13.sp)
                Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = SecondaryButtonColor),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
