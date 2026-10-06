package com.shinjo.shonanandroid.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.shinjo.shonanandroid.AppViewModel
import com.shinjo.shonanandroid.core.AppSettings
import com.shinjo.shonanandroid.core.BandProfile

private val CardBackground = Color(0xFF191D1F)
private val CaptionColor = Color(0xFF9AA0A6)
private val DisplayBackground = Color(0xFF0A0A0A)
private val DisplayBorder = Color(0xFF4B5357)
private val KeyBackground = Color(0xFFF5F7F8)
private val KeyText = Color(0xFF101820)
private val KeyBorder = Color(0xFFC7D0D6)
private val OkKeyBackground = Color(0xFF0797BD)
private val ChipBackground = Color(0xFF303538)
private val AccentBlue = Color(0xFF1677FF)

/** Hz→MHzの表示(末尾の0は省く、Pi5版の`:g`表記と同じ)。 */
private fun formatMegahertz(hz: Long): String {
    val mhz = hz / 1_000_000.0
    return if (mhz % 1.0 == 0.0) "${mhz.toLong()}" else mhz.toString().trimEnd('0').trimEnd('.')
}

private fun bandMegahertzLabel(band: BandProfile): String {
    val hz = band.loHz ?: return ""
    val mhz = hz / 1_000_000.0
    return if (mhz % 1.0 == 0.0) "${mhz.toInt()} MHz" else "%.2f MHz".format(mhz)
}

/**
 * 周波数設定画面 -- Shonan_Lite-pi5(`pi5/gui/screens/frequency.py`)の見た目を参考にした版。
 * pi5は左に周波数入力・右にバンド選択だが、Android版は左右のバランスを取るため
 * 左にバンド選択チップ・右に周波数入力(大きな数値表示+テンキー)と左右を入れ替えている。
 * テンキーはpi5と同じ明るい配色(他画面のダーク配色とは異なる、pi5自体の意図的なコントラスト)。
 */
@Composable
fun FrequencySettingsScreen(viewModel: AppViewModel, navController: NavHostController) {
    val settings = viewModel.settings
    var pendingText by remember(settings.effectiveLoHz) { mutableStateOf((settings.effectiveLoHz / 1_000).toString()) }

    // 10GHz帯を押したときの「LNBを使用しますか?」確認(Pi5版の周波数画面から移植、iPad版と同じ)。
    var showLnbPrompt by remember { mutableStateOf(false) }

    fun selectBand(band: BandProfile) {
        val loHz = band.loHz ?: return
        viewModel.updateSettings { s ->
            s.copy(selectedBand = band, useCustomLoFrequency = false, customLoFrequencyHz = loHz, useLNB = false)
        }
        pendingText = (loHz / 1_000).toString()
        // 選択中の10GHz帯を押し直しても選び直せるようにする。
        if (band == BandProfile.BAND_10000) showLnbPrompt = true
    }

    if (showLnbPrompt) {
        val plutoMhz = formatMegahertz(AppSettings.LNB_DISPLAY_HZ - AppSettings.LNB_LO_HZ)
        val lnbLoMhz = formatMegahertz(AppSettings.LNB_LO_HZ)
        val displayGhz = (AppSettings.LNB_DISPLAY_HZ / 1_000_000_000.0).toString().trimEnd('0').trimEnd('.')
        val bandMhz = formatMegahertz(BandProfile.BAND_10000.loHz ?: 0L)
        AlertDialog(
            onDismissRequest = { showLnbPrompt = false },
            title = { Text(settings.t("LNBの使用", "Use LNB")) },
            text = {
                Text(
                    settings.t(
                        "LNBを使用しますか?\n\n「はい」: 表示周波数 $displayGhz GHz、Pluto受信 $plutoMhz MHz(LNB局部発振 $lnbLoMhz MHz)。受信専用で送信はできません。\n「いいえ」: $bandMhz MHz(LNBなし)",
                        "Use an LNB?\n\nYes: displayed frequency $displayGhz GHz, Pluto RX $plutoMhz MHz (LNB local oscillator $lnbLoMhz MHz). Receive only; transmitting is not possible.\nNo: $bandMhz MHz (no LNB)",
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showLnbPrompt = false
                    viewModel.updateSettings { s ->
                        s.copy(useLNB = true, useCustomLoFrequency = true, customLoFrequencyHz = AppSettings.LNB_DISPLAY_HZ)
                    }
                    pendingText = (AppSettings.LNB_DISPLAY_HZ / 1_000).toString()
                }) { Text(settings.t("はい", "Yes")) }
            },
            dismissButton = {
                TextButton(onClick = { showLnbPrompt = false }) { Text(settings.t("いいえ", "No")) }
            },
        )
    }

    fun onKey(key: String) {
        when (key) {
            "C" -> pendingText = ""
            "DEL" -> pendingText = pendingText.dropLast(1)
            "OK" -> {
                val khz = pendingText.toLongOrNull() ?: return
                viewModel.updateSettings { s -> s.copy(useCustomLoFrequency = true, customLoFrequencyHz = khz * 1_000) }
            }
            else -> if (key.all { it.isDigit() } && pendingText.length < 8) pendingText += key
        }
    }

    SettingsSubScreen(
        title = settings.t("周波数", "Frequency"),
        onBack = { navController.popBackStack("home", false) },
        homeLabel = settings.t("ホームへ戻る", "Home"),
        scrollEnabled = false,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // --- 左カラム: バンド選択 ---
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(CardBackground, RoundedCornerShape(12.dp))
                    .padding(14.dp, 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    settings.t("バンド選択", "Band"),
                    color = CaptionColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
                BandProfile.entries.filterNot { it.isLoopback }.forEach { band ->
                    val lnbSelected = band == BandProfile.BAND_10000 && settings.selectedBand == band && settings.lnbActive
                    val selected = (!settings.useCustomLoFrequency && settings.selectedBand == band) || lnbSelected
                    Button(
                        onClick = { selectBand(band) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selected) AccentBlue else ChipBackground,
                            contentColor = Color.White,
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                settings.t(band.displayName, band.displayNameEnglish),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Start,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                if (lnbSelected) "${formatMegahertz(settings.effectiveLoHz)} (LNB)" else bandMegahertzLabel(band),
                                fontSize = 12.sp,
                                textAlign = TextAlign.Start,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
            }

            // --- 右カラム: 周波数入力 ---
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(CardBackground, RoundedCornerShape(12.dp))
                    .padding(16.dp, 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    settings.t("周波数入力", "Frequency Input"),
                    color = CaptionColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    pendingText,
                    color = Color.White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DisplayBackground, RoundedCornerShape(8.dp))
                        .border(1.dp, DisplayBorder, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                Text(
                    "kHz",
                    color = CaptionColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )

                val keypadRows = listOf(
                    listOf("7", "8", "9", "DEL"),
                    listOf("4", "5", "6", "C"),
                    listOf("1", "2", "3", ""),
                    listOf("", "0", "", "OK"),
                )
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    keypadRows.forEach { rowKeys ->
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            rowKeys.forEach { key ->
                                if (key.isEmpty()) {
                                    Spacer(modifier = Modifier.size(60.dp, 38.dp))
                                } else {
                                    Button(
                                        onClick = { onKey(key) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (key == "OK") OkKeyBackground else KeyBackground,
                                            contentColor = if (key == "OK") Color.White else KeyText,
                                        ),
                                        border = if (key == "OK") null else BorderStroke(1.dp, KeyBorder),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                                        shape = RoundedCornerShape(5.dp),
                                        modifier = Modifier.size(60.dp, 38.dp),
                                    ) {
                                        Text(key, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    settings.t("現在の周波数: ${settings.effectiveLoHz / 1_000} kHz", "Current: ${settings.effectiveLoHz / 1_000} kHz"),
                    color = CaptionColor,
                    fontSize = 13.sp,
                )
                if (settings.lnbActive) {
                    Text(
                        settings.t(
                            "LNB使用(受信専用): Pluto受信 ${settings.rxTuneHz / 1_000} kHz",
                            "LNB in use (RX only): Pluto RX ${settings.rxTuneHz / 1_000} kHz",
                        ),
                        color = CaptionColor,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}
