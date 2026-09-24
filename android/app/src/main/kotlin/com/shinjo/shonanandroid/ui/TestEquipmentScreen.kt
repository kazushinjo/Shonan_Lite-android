package com.shinjo.shonanandroid.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.shinjo.shonanandroid.AppViewModel
import com.shinjo.shonanandroid.tx.CameraPreviewView

private val OuterCardBorder = Color(0xFF34434B)
private val OuterCardBackground = Color(0xFF101416)
private val CardBackground = Color(0xFF191D1F)
private val HeaderRowBackground = Color(0xFF252A2D)
private val CaptionColor = Color(0xFFCCCCCC)
private val GridLineColor = Color(0xFF30383C)
private val LogBackground = Color(0xFF101416)
private val LogText = Color(0xFFB9C4C8)
private val HealthGreen = Color(0xFF36B47A)
private val HealthRed = Color(0xFFCC3333)
private val ActionBlue = Color(0xFF1677FF)

private enum class DiagStatus { IDLE, RUNNING, DONE }

/**
 * 機器試験画面 -- Shonan_Lite-pi5(`pi5/gui/screens/testequipment.py`)と同じ
 * 「左に試験項目テーブル+実行ボタン、右に診断結果(システム状態+ログ)」の
 * 2カラムレイアウトに変更([[shonan-lite-ipad-tx-rx-pi5-design]]と同様の移植)。
 *
 * pi5版はPi5実機のPluto再起動・5行テーブル(Pluto接続/送信/受信/温度センサー/ファン回転)
 * を行うが、Android版はPluto実機に対しlibiio経由でDVB-S2診断する[com.shinjo.shonanandroid.dvbs2.Dvbs2TestRunner]
 * (TX/RX単体診断・カメラ+音声送出診断の2系統)しか持たないため、テーブルはその2項目とする。
 * 温度センサー/ファン回転はAndroid端末に対応するハードウェアがないため含めない。
 */
@Composable
fun TestEquipmentScreen(viewModel: AppViewModel, navController: NavHostController) {
    val runner = viewModel.dvbs2TestRunner
    val settings = viewModel.settings
    val pageScrollState = rememberScrollState()

    LaunchedEffect(runner.cameraDiagRunning, pageScrollState.maxValue) {
        if (runner.cameraDiagRunning) {
            pageScrollState.animateScrollTo(pageScrollState.maxValue)
        }
    }

    val txRxStatus = when {
        runner.diagRunning -> DiagStatus.RUNNING
        runner.diagResultText.isNotEmpty() -> DiagStatus.DONE
        else -> DiagStatus.IDLE
    }
    val txRxOk = runner.diagResultText.let { it.contains("✅") || it.contains("正常") } &&
        !(runner.diagResultText.contains("❌") || runner.diagResultText.contains("異常"))
    val cameraStatus = when {
        runner.cameraDiagRunning -> DiagStatus.RUNNING
        runner.cameraDiagResultText.isNotEmpty() -> DiagStatus.DONE
        else -> DiagStatus.IDLE
    }
    val cameraOk = runner.cameraDiagResultText.let { it.contains("✅") || it.contains("正常") } &&
        !(runner.cameraDiagResultText.contains("❌") || runner.cameraDiagResultText.contains("異常"))
    val anyFailed = (txRxStatus == DiagStatus.DONE && !txRxOk) || (cameraStatus == DiagStatus.DONE && !cameraOk)

    SettingsSubScreen(
        title = settings.t("機器試験", "Diagnostic"),
        onBack = {
            runner.cancelDiag()
            runner.cancelCameraAudioDiag()
            navController.popBackStack("home", false)
        },
        homeLabel = settings.t("ホームへ戻る", "Home"),
        scrollState = pageScrollState,
        scrollEnabled = false,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .background(OuterCardBackground, RoundedCornerShape(14.dp))
                .border(1.dp, OuterCardBorder, RoundedCornerShape(14.dp))
                .padding(12.dp, 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // --- 左カラム: 試験項目テーブル ---
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(CardBackground, RoundedCornerShape(12.dp))
                    .padding(8.dp, 5.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(HeaderRowBackground, RoundedCornerShape(4.dp))
                        .padding(6.dp, 4.dp),
                ) {
                    Text(settings.t("項目", "Item"), color = CaptionColor, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(settings.t("ステータス", "Status"), color = CaptionColor, fontSize = 12.sp, modifier = Modifier.width(72.dp))
                    Text(settings.t("結果", "Result"), color = CaptionColor, fontSize = 12.sp, modifier = Modifier.width(48.dp))
                }
                DiagRow(settings.t("TX/RX単体診断", "TX/RX Diagnostic"), txRxStatus, txRxOk, settings)
                DiagRow(settings.t("カメラ+音声送出", "Camera + Audio"), cameraStatus, cameraOk, settings)

                Spacer(modifier = Modifier.weight(1f))

                Button(
                    onClick = { viewModel.runDvbs2Diagnostics() },
                    enabled = !runner.diagRunning && !viewModel.isTransmitting && !viewModel.isReceiving,
                    colors = ButtonDefaults.buttonColors(containerColor = ActionBlue),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().height(34.dp),
                ) {
                    Text(settings.t("全体試験(TX/RX)", "Run TX/RX Test"), fontSize = 13.sp)
                }
                if (runner.diagRunning) {
                    Button(
                        onClick = { viewModel.cancelDvbs2Diagnostics() },
                        colors = ButtonDefaults.buttonColors(containerColor = HealthRed),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
                        modifier = Modifier.fillMaxWidth().height(30.dp).padding(top = 4.dp),
                    ) {
                        Text(settings.t("中断", "Cancel"), fontSize = 12.sp)
                    }
                }
                Button(
                    onClick = { viewModel.runCameraAudioDiagnostics() },
                    enabled = !runner.cameraDiagRunning && !runner.diagRunning && !viewModel.isTransmitting && !viewModel.isReceiving,
                    colors = ButtonDefaults.buttonColors(containerColor = ActionBlue),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
                    modifier = Modifier.fillMaxWidth().height(30.dp).padding(top = 4.dp),
                ) {
                    Text(settings.t("カメラ＋音声診断", "Run Camera + Audio Test"), fontSize = 12.sp)
                }
                if (runner.cameraDiagRunning) {
                    Button(
                        onClick = { viewModel.cancelCameraAudioDiagnostics() },
                        colors = ButtonDefaults.buttonColors(containerColor = HealthRed),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
                        modifier = Modifier.fillMaxWidth().height(30.dp).padding(top = 4.dp),
                    ) {
                        Text(settings.t("中断", "Cancel"), fontSize = 12.sp)
                    }
                }
            }

            // --- 右カラム: 診断結果 ---
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(CardBackground, RoundedCornerShape(12.dp))
                    .padding(12.dp, 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(settings.t("診断結果", "Diagnostic Result"), color = CaptionColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (txRxStatus == DiagStatus.IDLE && cameraStatus == DiagStatus.IDLE) {
                        settings.t("システム状態: 待機中", "System: Idle")
                    } else if (runner.diagRunning || runner.cameraDiagRunning) {
                        settings.t("システム状態: 診断中", "System: Running")
                    } else if (anyFailed) {
                        settings.t("システム状態: 異常あり", "System: Failure detected")
                    } else {
                        settings.t("システム状態: 正常", "System: OK")
                    },
                    color = if (anyFailed) HealthRed else HealthGreen,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(LogBackground, RoundedCornerShape(6.dp))
                        .border(1.dp, GridLineColor, RoundedCornerShape(6.dp))
                        .padding(6.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(diagnosticText(runner.diagLog, settings), color = LogText, fontSize = 11.sp)
                }

                if (runner.diagRunning && runner.diagPhaseText.isNotEmpty()) {
                    Text(diagnosticText(runner.diagPhaseText, settings), color = Color(0xFFF44336), fontSize = 12.sp)
                }
                if (runner.cameraDiagRunning) {
                    if (runner.cameraDiagPhaseText.isNotEmpty()) {
                        Text(diagnosticText(runner.cameraDiagPhaseText, settings), color = Color(0xFFF44336), fontSize = 12.sp)
                    }
                    Text(
                        settings.t(
                            "映像フレーム: ${runner.cameraDiagVideoFrames} / 音声フレーム: ${runner.cameraDiagAudioFrames}",
                            "Video frames: ${runner.cameraDiagVideoFrames} / Audio frames: ${runner.cameraDiagAudioFrames}",
                        ),
                        color = CaptionColor,
                        fontSize = 11.sp,
                    )
                    CameraPreviewView(
                        runner.cameraAudioPipeline.cameraPreview,
                        modifier = Modifier.fillMaxWidth().height(90.dp),
                    )
                } else if (runner.cameraDiagResultText.isNotEmpty()) {
                    Text(
                        settings.t(
                            "映像フレーム: ${runner.cameraDiagVideoFrames} / 音声フレーム: ${runner.cameraDiagAudioFrames}",
                            "Video frames: ${runner.cameraDiagVideoFrames} / Audio frames: ${runner.cameraDiagAudioFrames}",
                        ),
                        color = CaptionColor,
                        fontSize = 11.sp,
                    )
                }

                Text(
                    settings.t(
                        "カメラ+音声送出の診断は、カメラ映像+マイク音声を実際にキャプチャし、H.264/AACエンコード→TS多重化→Pluto送出という実運用と同じ経路が健全かを確認します。RX側や実際の復調確認は行いません。",
                        "The camera + audio diagnostic captures camera video and microphone audio and checks the same H.264/AAC encoding, TS muxing, and Pluto TX path used in operation. It does not test RX or actual demodulation.",
                    ),
                    color = CaptionColor,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun DiagRow(label: String, status: DiagStatus, ok: Boolean, settings: com.shinjo.shonanandroid.core.AppSettings) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(
            when (status) {
                DiagStatus.IDLE -> settings.t("待機中", "Idle")
                DiagStatus.RUNNING -> settings.t("試験中", "Running")
                DiagStatus.DONE -> settings.t("完了", "Done")
            },
            color = CaptionColor,
            fontSize = 11.sp,
            modifier = Modifier.width(72.dp),
        )
        Text(
            if (status == DiagStatus.DONE) (if (ok) "OK" else "NG") else "—",
            color = if (status == DiagStatus.DONE) (if (ok) HealthGreen else HealthRed) else CaptionColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(48.dp),
        )
    }
}

private fun diagnosticText(text: String, settings: com.shinjo.shonanandroid.core.AppSettings): String {
    if (settings.language != com.shinjo.shonanandroid.core.AppLanguage.ENGLISH) return text
    val translated = text
        .replace("診断中...", "Running diagnostic...")
        .replace("TXへ接続中...", "Connecting to TX...")
        .replace("RXへ接続中...", "Connecting to RX...")
        .replace("TX起動中(ウォームアップ)...", "Starting TX (warm-up)...")
        .replace("停止処理中...", "Stopping...")
        .replace("カメラ/マイク起動中...", "Starting camera/microphone...")
        .replace("映像/音声の送出確認中...", "Checking video/audio transmission...")
        .replace("TX健全性確認中...", "Checking TX health...")
        .replace("RX継続確認中...", "Checking RX continuity...")
        .replace("TX接続失敗", "TX connection failed")
        .replace("RX接続失敗", "RX connection failed")
        .replace("✅ 正常", "✅ OK")
        .replace("❌ 異常", "❌ Failed")
        .replace("送受信の診断", "TX/RX diagnostic")
        .replace("[診断]", "[Diagnostic]")
        .replace("カメラ+音声送出の診断", "Camera + audio diagnostic")
        .replace("中断しました", "cancelled")
        .replace("完了", "completed")
        .replace("接続成功", "connection succeeded")
        .replace("接続失敗", "connection failed")
        .replace("結果", "result")
        .replace("接続=", "connected=")
        .replace("健全サンプル", "healthy samples")
        .replace("受信バイト数", "received bytes")
        .replace("有効サンプル", "active samples")
        .replace("映像/音声", "video/audio")
        .replace("カメラ/マイク", "camera/microphone")
        .replace("開始します", "starting")
        .replace("送出", "transmission")
        .replace("映像フレーム", "Video frames")
        .replace("音声フレーム", "Audio frames")
        .replace("最終", "final")
        .replace("正常", "OK")
        .replace("異常", "Failed")
    return if (translated.any { it in '぀'..'ヿ' || it in '一'..'鿿' }) {
        "Diagnostic message unavailable in English"
    } else {
        translated
    }
}
