package com.shinjo.shonanandroid.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.shinjo.shonanandroid.AfcMeasurement
import com.shinjo.shonanandroid.AppViewModel
import kotlin.math.max

private val OuterCardBorder = Color(0xFF34434B)
private val OuterCardBackground = Color(0xFF101416)
private val InnerCardBackground = Color(0xFF191D1F)
private val TitleCyan = Color(0xFF54BCE0)
private val CaptionColor = Color(0xFFCCCCCC)
private val AccentCyan = Color(0xFF0C9BC0)
private val ActionButton = Color(0xFF0C91B5)
private val ChartBackground = Color(0xFF050607)
private val ChartBorder = Color(0xFF46545B)
private val ChartAxis = Color(0xFF2A3438)

/**
 * 相手局検索画面 -- Shonan_Lite-pi5(`pi5/gui/screens/afc.py`)と同じ「左に検索条件、
 * 右に検索結果」の2カラムダークカードレイアウトに変更([[shonan-lite-ipad-tx-rx-pi5-design]]
 * と同様の移植)。pi5は絶対周波数の開始/終了(テンキー入力)+結果テーブル(上位5件)方式だが、
 * Android版は中心周波数±範囲の連続スキャン+RSSIグラフ方式のため、右カラムはpi5の
 * テーブルではなく既存のライブ測定値+グラフをそのままpi5配色のカードに収める。
 */
@Composable
fun AfcScreen(viewModel: AppViewModel, navController: NavHostController) {
    // ★既定値はShonan_Lite-pi5(pi5/gui/screens/afc.py)の既定プリセット(±10MHz)/
    // 既定ステップ(100kHz)に合わせる。
    var rangeHz by remember { mutableLongStateOf(10_000_000L) }
    var stepKhzText by remember { mutableStateOf("100") }
    val settings = viewModel.settings
    val restrictionMessage = viewModel.afcRestrictionMessage

    SettingsSubScreen(
        title = settings.t("相手局検索", "Find Station"),
        onBack = { navController.popBackStack("home", false) },
        homeLabel = settings.t("ホームへ戻る", "Home"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp)
                .background(OuterCardBackground, RoundedCornerShape(14.dp))
                .border(1.dp, OuterCardBorder, RoundedCornerShape(14.dp))
                .padding(12.dp, 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // --- 左カラム: 検索条件 ---
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(InnerCardBackground, RoundedCornerShape(10.dp))
                    .padding(10.dp, 8.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(settings.t("検索条件", "Search Settings"), color = TitleCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                AfcValueRow(settings.t("中心周波数", "Center Frequency"), formatFrequency(settings.effectiveLoHz))
                Text(settings.t("探索範囲", "Search Range"), color = Color(0xFFEEEEEE), fontSize = 13.sp)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AfcRangeChip("±5 MHz", rangeHz == 5_000_000L, modifier = Modifier.weight(1f)) { rangeHz = 5_000_000L }
                    AfcRangeChip("±10 MHz", rangeHz == 10_000_000L, modifier = Modifier.weight(1f)) { rangeHz = 10_000_000L }
                    AfcRangeChip("±20 MHz", rangeHz == 20_000_000L, modifier = Modifier.weight(1f)) { rangeHz = 20_000_000L }
                }
                OutlinedTextField(
                    value = stepKhzText,
                    onValueChange = { stepKhzText = it },
                    label = { Text(settings.t("ステップ (kHz)", "Step (kHz)")) },
                    enabled = !viewModel.afcIsScanning,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                Text(
                    settings.t("中心周波数の前後を指定ステップで1回だけ走査します。", "Scans once, in the given step, around the center frequency."),
                    color = CaptionColor,
                    fontSize = 11.sp,
                )

                if (viewModel.afcIsScanning) {
                    OutlinedButton(
                        onClick = viewModel::stopAfc,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF44336)),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) {
                        Text(settings.t("検索停止", "Stop"))
                    }
                } else {
                    Button(
                        onClick = {
                            val stepHz = (stepKhzText.toLongOrNull() ?: 100L).coerceAtLeast(1L) * 1_000L
                            viewModel.startAfc(rangeHz, stepHz)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ActionButton),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) {
                        Text(settings.t("検索開始", "Start"))
                    }
                }
                Text(runtimeText(viewModel.afcStatus, settings.language), color = Color(0xFFEEEEEE), fontSize = 12.sp)
            }

            // --- 右カラム: 検索結果 ---
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(InnerCardBackground, RoundedCornerShape(10.dp))
                    .padding(10.dp, 8.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(settings.t("検索結果", "Search Result"), color = TitleCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                viewModel.afcCurrentFrequencyHz?.let { AfcValueRow(settings.t("現在の測定周波数", "Current Frequency"), formatFrequency(it)) }
                viewModel.afcCurrentRssiDb?.let { AfcValueRow(settings.t("現在のRSSI", "Current RSSI"), formatRssi(it)) }
                viewModel.afcBestFrequencyHz?.let { AfcValueRow(settings.t("最良周波数", "Best Frequency"), formatFrequency(it)) }
                viewModel.afcBestRssiDb?.let { AfcValueRow(settings.t("最高RSSI", "Best RSSI"), formatRssi(it)) }

                if (viewModel.afcBestFrequencyHz != null) {
                    Button(
                        onClick = viewModel::applyAfcBestAsCenter,
                        colors = ButtonDefaults.buttonColors(containerColor = ActionButton),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    ) {
                        Text(settings.t("新中心周波数", "Apply New Center"))
                    }
                }

                if (viewModel.afcMeasurements.isNotEmpty()) {
                    Text(settings.t("RSSI グラフ", "RSSI Graph"), color = CaptionColor, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    Text("Frequency (MHz) / RSSI (dB)", color = CaptionColor, fontSize = 10.sp)
                    AfcRssiChart(viewModel.afcMeasurements)
                }
            }
        }

        Text(
            settings.t(
                "指定範囲を1回だけ走査します。RSSIの変動幅が3dB以上あった場合のみ最良周波数として表示され、" +
                    "「新中心周波数」を押すと送受信周波数に反映されます(押すまでは反映されません)。送信中・受信中は開始できません。",
                "Scans the given range once. A best frequency is shown only when the RSSI varies by 3 dB or more; " +
                    "press \"Apply New Center\" to use it for TX/RX (it is not applied automatically). Scanning cannot start during TX or RX.",
            ),
            fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }

    if (restrictionMessage != null) {
        AlertDialog(
            onDismissRequest = viewModel::clearAfcRestrictionMessage,
            confirmButton = { TextButton(onClick = viewModel::clearAfcRestrictionMessage) { Text("OK") } },
            title = { Text(settings.t("相手局検索を開始できません", "Cannot start Find Station")) },
            text = { Text(settings.t(restrictionMessage, "Find Station cannot run while TX or RX is active. Stop the current session first.")) },
        )
    }
}

@Composable
private fun AfcRangeChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
    val contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)
    if (selected) {
        Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = AccentCyan), shape = shape, contentPadding = contentPadding, modifier = modifier) {
            Text(label, fontSize = 13.sp, maxLines = 1)
        }
    } else {
        OutlinedButton(onClick = onClick, shape = shape, contentPadding = contentPadding, modifier = modifier) {
            Text(label, fontSize = 13.sp, maxLines = 1)
        }
    }
}

@Composable
private fun AfcValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = CaptionColor, fontSize = 12.sp)
        Text(value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AfcRssiChart(measurements: List<AfcMeasurement>) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .background(ChartBackground, RoundedCornerShape(8.dp))
            .border(1.dp, ChartBorder, RoundedCornerShape(8.dp)),
    ) {
        val minFrequency = measurements.minOf { it.frequencyHz }
        val maxFrequency = max(measurements.maxOf { it.frequencyHz }, minFrequency + 1L)
        val minRssi = measurements.minOf { it.rssiDb }
        val maxRssi = max(measurements.maxOf { it.rssiDb }, minRssi + 1.0)
        val horizontalPadding = 16.dp.toPx()
        val verticalPadding = 12.dp.toPx()
        fun point(measurement: AfcMeasurement): Offset {
            val x = horizontalPadding + ((measurement.frequencyHz - minFrequency).toFloat() / (maxFrequency - minFrequency).toFloat()) * (size.width - 2 * horizontalPadding)
            val y = size.height - verticalPadding - ((measurement.rssiDb - minRssi) / (maxRssi - minRssi)).toFloat() * (size.height - 2 * verticalPadding)
            return Offset(x, y)
        }
        repeat(5) { index ->
            val y = verticalPadding + index / 4f * (size.height - 2 * verticalPadding)
            drawLine(ChartAxis, Offset(horizontalPadding, y), Offset(size.width - horizontalPadding, y), strokeWidth = 1.dp.toPx())
        }
        measurements.zipWithNext().forEach { (a, b) -> drawLine(AccentCyan, point(a), point(b), strokeWidth = 2.dp.toPx()) }
        measurements.forEach { drawCircle(AccentCyan, radius = 3.dp.toPx(), center = point(it)) }
    }
}

private fun formatFrequency(frequencyHz: Long): String = "%.3f MHz".format(frequencyHz / 1_000_000.0)
private fun formatRssi(rssi: Double): String = "%.2f dB".format(rssi)
