package com.shinjo.shonanandroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.shinjo.shonanandroid.AppViewModel
import com.shinjo.shonanandroid.rx.RxVideoView

private val CardBackground = Color(0xFF191D1F)
private val DividerColor = Color(0xFF303538)
private val CaptionColor = Color(0xFF9AA0A6)
private val SecondaryButtonColor = Color(0xFF303538)
private val StartColor = Color(0xFF1677FF)
private val StopColor = Color(0xFFD02020)
private val LockedGreen = Color(0xFF20C020)
private val LockBadgeGreen = Color(0xFF20A040)
private val IdleGray = Color(0xFF5A5A5A)

/**
 * 受信画面 -- Shonan_Lite-pi5(`pi5/gui/screens/rx.py`)と同じカードレイアウトに統一。
 * 左に固定幅ダークステータスカード(周波数/シンボルレート/変調方式/FECの2x2グリッド、
 * 区切り線、統計2x2グリッド)、右に受信映像、下段に音量・ボタン行を並べる
 * ([[shonan-lite-ipad-tx-rx-pi5-design]]と同様の移植)。映像タップで全画面表示に切り替わる。
 */
@Composable
fun RxScreen(viewModel: AppViewModel, navController: NavHostController) {
    val settings = viewModel.settings
    val rx = viewModel.rxController
    val running = viewModel.isReceiving
    val preparing = viewModel.isPreparingRx
    var fullscreen by remember { mutableStateOf(false) }

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
            if (!fullscreen) {
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
                            if (running) settings.t("受信中", "Receiving") else settings.t("受信停止中", "Receive stopped"),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        if (rx.isLocked) {
                            Box(
                                modifier = Modifier
                                    .background(LockBadgeGreen, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text("LOCK", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
                        settings.t("状態", "State") to if (rx.isLocked) settings.t("接続中", "Connected") else settings.t("切断中", "Disconnected"),
                        settings.t("パケット数", "Packets") to "${rx.continuityTracker.totalPackets}",
                    )
                    StatusFieldRow(
                        settings.t("エラー", "Errors") to "${rx.continuityTracker.estimatedLostPackets}",
                        settings.t("送信パケット/秒", "TX packets/sec") to
                            if (settings.useOnDeviceGRDVBS2Rx) "${viewModel.txController.stats.packetsPerSecond}" else "-",
                    )

                    viewModel.rxError?.let {
                        Text(
                            runtimeText(it, settings.language),
                            color = Color(0xFFF44336),
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color.Black, RoundedCornerShape(10.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { fullscreen = !fullscreen },
            ) {
                RxVideoView(
                    modifier = Modifier.fillMaxSize(),
                    onSurfaceAvailable = { surface -> rx.displayDecoder.configure(surface) },
                    onSurfaceDestroyed = { rx.displayDecoder.stop() },
                )
            }
        }

        if (!fullscreen) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(settings.t("音声レベル", "Audio Level"), color = Color.White, fontSize = 12.sp)
                }
                LinearProgressIndicator(
                    progress = { rx.audioLevel },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(settings.t("音量", "Volume"), color = Color.White, fontSize = 12.sp, modifier = Modifier.width(60.dp))
                    Slider(
                        value = settings.rxVolume,
                        onValueChange = { value -> viewModel.setRxVolume(value) },
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(thumbColor = Color(0xFFDDDDDD), activeTrackColor = StartColor),
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Button(
                        onClick = { if (running) viewModel.stopRX() else viewModel.startRX() },
                        enabled = !preparing,
                        colors = ButtonDefaults.buttonColors(containerColor = if (running) StopColor else StartColor),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            when {
                                preparing -> settings.t("準備中…", "Starting…")
                                running -> settings.t("受信停止", "Stop")
                                else -> settings.t("受信開始", "Start")
                            },
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    if (settings.useOnDeviceGRDVBS2Rx) {
                        SecondaryButton(settings.t("送信画面へ", "Transmit Screen")) {
                            navController.navigate("tx")
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
