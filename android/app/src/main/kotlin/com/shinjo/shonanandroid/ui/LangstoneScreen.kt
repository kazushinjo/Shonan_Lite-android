package com.shinjo.shonanandroid.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import com.shinjo.shonanandroid.AppViewModel
import com.shinjo.shonanandroid.langstone.LangstoneController
import com.shinjo.shonanandroid.langstone.LangstoneController.InputMode
import com.shinjo.shonanandroid.langstone.LangstoneController.Popup
import com.shinjo.shonanandroid.langstone.LangstoneController.Status
import com.shinjo.shonanandroid.langstone.LangstoneDisplay
import com.shinjo.shonanandroid.langstone.LangstoneMode
import com.shinjo.shonanandroid.langstone.LangstoneSetting
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val lsGreen = Color(0xFF00FF00)
private val lsRed = Color(0xFFFF0000)
private val lsYellow = Color(0xFFFFFF00)
private val lsFreq = Color(0xFF00C8FF)
private val lsUnderline = Color(0xFF0096FF)
private val lsOrange = Color(0xFFFF8C00)

// ボタンの配色(ホーム画面のカードに合わせた濃紺と水色、操作中は深い赤。iPad版と同じ)
private val btnText = Color(0xFFDBEDF7)
private val btnBorder = Color(0xFF70BECE)
private val btnFill = listOf(Color(0xFF1A2636), Color(0xFF0A0F1A))
private val btnActiveBorder = Color(0xFFFF6B5C)
private val btnActiveFill = listOf(Color(0xFF9E1A1A), Color(0xFF570A0D))
private val btnWarn = Color(0xFFFF8073)

/**
 * Langstone V3の画面(LangstoneGUI_Pluto.cのinitGUI/displayMenu等)をタブレット向けに組み直したもの。
 * iPad版(LangstoneView.swift)と同じ配置・配色にしている。
 */
@Composable
fun LangstoneScreen(viewModel: AppViewModel, navController: NavHostController) {
    val settings = viewModel.settings
    val context = LocalContext.current
    val controller = remember { LangstoneController(context.applicationContext) }
    var isExiting by remember { mutableStateOf(false) }
    var showQuitConfirmation by remember { mutableStateOf(false) }

    fun startController() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        controller.start(
            plutoHost = settings.txDestinationIP.trim(),
            pttControllerHost = settings.activePttControllerHost.takeIf { it.isNotEmpty() },
            recordGranted = granted,
        )
    }

    fun exit() {
        if (isExiting) return
        isExiting = true
        controller.shutdown { navController.popBackStack("home", false) }
    }

    // プログラム終了: Plutoを受信状態に戻して切断してから、ホーム画面の「プログラム終了」と同じ終了処理
    // (PTT・12V電源OFF→プロセス終了)を行う。
    fun quit() {
        if (isExiting) return
        isExiting = true
        showQuitConfirmation = false
        controller.shutdown { viewModel.quitApp() }
    }

    LaunchedEffect(Unit) { startController() }
    DisposableEffect(Unit) { onDispose { controller.release() } }
    BackHandler { exit() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TopBar(controller)
                FrequencyRow(controller)
                // タブレットは縦が短いため、スペクトラムとウォーターフォールは残りの高さに合わせる。
                SpectrumView(controller.display, Modifier.fillMaxWidth().weight(1f)) { controller.spectrumTapped() }
                LevelRow(controller, settings::t)
                PopupRow(controller)
                FunctionButtons(controller, settings::t) { exit() }
            }
            SidePanel(
                controller, settings::t, isExiting, Modifier.width(240.dp).fillMaxHeight(),
                onExit = { exit() }, onQuit = { showQuitConfirmation = true },
            )
        }

        when (val st = controller.status) {
            Status.Connecting -> OverlayBox {
                CircularProgressIndicator(color = Color.White)
                Text(settings.t("Plutoへ接続中…", "Connecting to Pluto…"), color = Color.White, fontSize = 20.sp)
            }
            is Status.Failed -> OverlayBox {
                Text(st.message, color = Color.White, fontSize = 17.sp, textAlign = TextAlign.Center)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedButton(
                        onClick = { startController() },
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.6f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    ) { Text(settings.t("再試行", "Retry")) }
                    Button(onClick = { exit() }, shape = RoundedCornerShape(6.dp)) {
                        Text(settings.t("ホームへ戻る", "Back to Home"))
                    }
                }
            }
            else -> Unit
        }
    }

    if (showQuitConfirmation) {
        QuitConfirmationDialog(settings::t, onQuit = { quit() }, onDismiss = { showQuitConfirmation = false })
    }
}

// MARK: - 上段(Sメーター・モード・LOCK・XVTR等・MONI・Rx/Tx)

@Composable
private fun TopBar(c: LangstoneController) {
    val style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        MeterBarView(
            c.display, c.squelch,
            Modifier.width(220.dp).height(54.dp).clickable { c.sMeterTapped() },
        )
        Text(c.modeLabel, color = lsYellow, style = style, modifier = Modifier.width(100.dp))
        Text(if (c.dialLock) "LOCK" else "", color = lsRed, style = style, modifier = Modifier.width(70.dp))
        Text(c.rigModeLabel, color = lsGreen, style = style, modifier = Modifier.width(80.dp))
        Text(if (c.moni) "MONI" else "", color = lsGreen, style = style, modifier = Modifier.width(70.dp))
        Text(if (c.transmitting) "Tx" else "Rx", color = if (c.transmitting) lsRed else lsGreen, style = style)
        Spacer(Modifier.weight(1f))
        c.errorText?.let {
            Text(it, color = lsRed, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
        }
    }
}

// MARK: - 周波数表示とRIT

@Composable
private fun FrequencyRow(c: LangstoneController) {
    val showRit = c.mode != LangstoneMode.FM && c.mode != LangstoneMode.AM
    BoxWithConstraints(Modifier.fillMaxWidth().height(100.dp)) {
        val ritWidth = 96.dp
        val available = maxWidth - ritWidth - 12.dp
        val charWidth = (available / 12).coerceIn(28.dp, 50.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            FrequencyView(c.frequencyText, c.tuneDigit, charWidth, { c.selectDigit(it) }, { c.turnDial(it) })
            Spacer(Modifier.weight(1f))
            Box(Modifier.width(ritWidth)) {
                if (showRit) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(c.ritText, color = lsGreen, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace, maxLines = 1)
                        LsButton("RIT", active = c.inputMode == InputMode.RIT) { c.ritButton() }
                    }
                }
            }
        }
    }
}

/** 12文字の周波数表示。桁をタップするとその桁がチューニング桁になり、上下にドラッグすると選局できる。 */
@Composable
private fun FrequencyView(
    text: String, tuneDigit: Int, charWidth: Dp, onSelectDigit: (Int) -> Unit, onTurn: (Int) -> Unit,
) {
    val density = LocalDensity.current
    val stepPx = with(density) { 18.dp.toPx() }
    var accum by remember { mutableFloatStateOf(0f) }
    val fontSize = with(density) { (charWidth * 1.55f).toSp() }
    Row(
        Modifier.pointerInput(Unit) {
            detectVerticalDragGestures(onDragEnd = { accum = 0f }) { _, dy ->
                accum += -dy
                val steps = (accum / stepPx).toInt()
                if (steps != 0) {
                    accum -= steps * stepPx
                    onTurn(steps)
                }
            }
        },
    ) {
        text.forEachIndexed { index, ch ->
            Column(
                Modifier.width(charWidth).pointerInput(index) { detectTapGestures { onSelectDigit(index) } },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(ch.toString(), color = lsFreq, fontSize = fontSize, fontFamily = FontFamily.Monospace, maxLines = 1)
                Box(
                    Modifier.width(charWidth - 6.dp).height(4.dp)
                        .background(if (index == tuneDigit) lsUnderline else Color.Transparent),
                )
            }
        }
    }
}

// MARK: - SQL / 設定 / Vol

@Composable
private fun LevelRow(c: LangstoneController, t: (String, String) -> String) {
    val numStyle = TextStyle(color = lsGreen, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LsButton("SQL", active = c.inputMode == InputMode.SQUELCH, modifier = Modifier.width(100.dp)) { c.sqlButton() }
        Text("${c.squelch}", style = numStyle, modifier = Modifier.width(50.dp))
        Box(Modifier.weight(1f)) {
            if (c.inputMode == InputMode.SETTINGS) SettingPanel(c, t)
        }
        Text("${c.volume}", style = numStyle, textAlign = TextAlign.End, modifier = Modifier.width(50.dp))
        LsButton("Vol", active = c.inputMode == InputMode.VOLUME, modifier = Modifier.width(100.dp)) { c.volButton() }
    }
}

// MARK: - ポップアップ(MODE / BAND / BEACON)

@Composable
private fun PopupRow(c: LangstoneController) {
    Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (c.popup) {
            Popup.NONE -> Unit
            Popup.MODE -> {
                LangstoneMode.entries.forEach { m ->
                    LsButton(m.label, active = m == c.mode, modifier = Modifier.weight(1f)) { c.selectMode(m) }
                }
                Spacer(Modifier.weight(1f))
            }
            Popup.BAND -> {
                LsButton("More..", modifier = Modifier.weight(1f)) { c.bandPopupMore() }
                for (n in 0 until 6) {
                    val b = n + c.popupFirstBand
                    LsButton(c.bandButtonLabel(b), active = b == c.band, modifier = Modifier.weight(1f)) { c.selectBand(b) }
                }
            }
            Popup.BEACON -> {
                Spacer(Modifier.weight(5f))
                LsButton("DOTS", modifier = Modifier.weight(1f)) { c.selectBeacon(2) }
                LsButton("CWID", modifier = Modifier.weight(1f)) { c.selectBeacon(1) }
            }
        }
    }
}

// MARK: - 下段の7ボタン

@Composable
private fun FunctionButtons(c: LangstoneController, t: (String, String) -> String, onExit: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (i in 0 until 7) {
            val (title, active, color) = functionButtonSpec(c, i, t)
            LsButton(title, active = active, color = color, modifier = Modifier.weight(1f), enabled = title.isNotEmpty()) {
                if (c.inputMode == InputMode.SETTINGS && i == 5) onExit() else c.functionButton(i)
            }
        }
    }
}

private fun functionButtonSpec(c: LangstoneController, i: Int, t: (String, String) -> String): Triple<String, Boolean, Color?> {
    if (c.inputMode == InputMode.SETTINGS) {
        return when (i) {
            0 -> Triple("MENU", false, null)
            2 -> Triple("NEXT", false, null)
            3 -> Triple("PREV", false, null)
            4 -> Triple("RESTART", false, btnWarn)
            5 -> Triple(t("ホームへ", "Home"), false, btnWarn)
            else -> Triple("", false, null)
        }
    }
    return when (i) {
        0 -> Triple("BAND", c.popup == Popup.BAND, null)
        1 -> Triple("MODE", c.popup == Popup.MODE, null)
        2 -> when {
            !c.showDupButton -> Triple("", false, null)
            c.ptts && c.dupActive -> Triple("1750", c.toneBurstActive, null)
            else -> Triple("DUP", c.dupActive, null)
        }
        3 -> Triple("SET", false, null)
        4 -> Triple(if (c.satMode()) "MONI" else "", c.moni, null)
        5 -> when (c.sendBeacon) {
            1 -> Triple("CWID", true, null)
            2 -> Triple("DOTS", true, null)
            else -> Triple("BEACON", c.popup == Popup.BEACON, null)
        }
        else -> if (c.rxOnly) Triple("RX ONLY", false, Color.Gray) else Triple("PTT", c.ptts, null)
    }
}

// MARK: - 右側(ホームへ・プログラム終了・針式メーター・KEY・ダイヤル・桁移動・LOCK)

@Composable
private fun SidePanel(
    c: LangstoneController, t: (String, String) -> String, isExiting: Boolean, modifier: Modifier,
    onExit: () -> Unit, onQuit: () -> Unit,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = onExit,
            enabled = !isExiting,
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E6FE0), contentColor = Color.White),
        ) { Text(t("ホームへ戻る", "Back to Home"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        Button(
            onClick = onQuit,
            enabled = !isExiting,
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF05A45), contentColor = Color.White),
        ) { Text(t("プログラム終了", "Quit"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }

        Spacer(Modifier.weight(1f))
        AnalogMeterView(c.display, Modifier.width(230.dp).height(120.dp))
        Spacer(Modifier.height(4.dp))
        if (c.mode.isCW) {
            KeyButton(c.keyButtonDown, enabled = !c.rxOnly) { c.setKeyButton(it) }
        }
        Text(dialTargetLabel(c, t), color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        DialView(Modifier.size(170.dp)) { c.turnDial(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LsButton("◀", modifier = Modifier.weight(1f)) { c.moveDigit(-1) }
            LsButton("LOCK", active = c.dialLock, modifier = Modifier.weight(1.3f)) { c.toggleDialLock() }
            LsButton("▶", modifier = Modifier.weight(1f)) { c.moveDigit(1) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun dialTargetLabel(c: LangstoneController, t: (String, String) -> String): String = when (c.inputMode) {
    InputMode.FREQ -> if (c.dialLock) t("ダイヤル: ロック中", "Dial: locked") else t("ダイヤル: 周波数", "Dial: frequency")
    InputMode.VOLUME -> t("ダイヤル: 音量", "Dial: volume")
    InputMode.SQUELCH -> t("ダイヤル: スケルチ", "Dial: squelch")
    InputMode.RIT -> t("ダイヤル: RIT", "Dial: RIT")
    InputMode.SETTINGS -> t("ダイヤル: 設定値", "Dial: setting value")
}

// MARK: - 接続中・接続失敗の表示

@Composable
private fun OverlayBox(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(520.dp).background(Color(0xFF1C2228), RoundedCornerShape(16.dp)).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) { content() }
    }
}

// MARK: - 部品

/** Langstoneのボタン(濃紺に水色の枠、操作中は赤)。 */
@Composable
private fun LsButton(
    title: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    color: Color? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    val textColor = if (active) Color.White else (color ?: btnText)
    val borderColor = if (active) btnActiveBorder else (color ?: btnBorder)
    val empty = title.isEmpty()
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(if (empty) Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
                else Brush.verticalGradient(if (active) btnActiveFill else btnFill))
            .border(1.5.dp, if (empty) Color.Transparent else borderColor.copy(alpha = if (active) 1f else 0.8f), shape)
            .clickable(enabled = enabled && !empty, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, color = textColor, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** 押している間だけキーダウンになるCWキー。 */
@Composable
private fun KeyButton(isDown: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.fillMaxWidth().height(64.dp).clip(shape)
            .background(Brush.verticalGradient(if (isDown) btnActiveFill else btnFill))
            .border(1.5.dp, if (isDown) btnActiveBorder else btnBorder.copy(alpha = 0.8f), shape)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    onChange(true)
                    do {
                        val event = awaitPointerEvent()
                    } while (event.changes.any { it.pressed })
                    onChange(false)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("KEY", color = if (isDown) Color.White else btnText, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

/** ロータリーエンコーダの代わりのダイヤル。円周に沿ってドラッグすると、15°ごとに1ステップ進む(時計回りで+)。 */
@Composable
private fun DialView(modifier: Modifier, onTurn: (Int) -> Unit) {
    var rotation by remember { mutableFloatStateOf(0f) }
    var lastAngle by remember { mutableStateOf<Float?>(null) }
    var accum by remember { mutableFloatStateOf(0f) }
    Box(
        modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { lastAngle = null; accum = 0f },
                onDragEnd = { lastAngle = null; accum = 0f },
            ) { change, _ ->
                val cx = size.width / 2f
                val cy = size.height / 2f
                val angle = Math.toDegrees(atan2((change.position.y - cy).toDouble(), (change.position.x - cx).toDouble())).toFloat()
                lastAngle?.let { last ->
                    var delta = angle - last
                    if (delta > 180) delta -= 360
                    if (delta < -180) delta += 360
                    rotation += delta
                    accum += delta
                    val steps = (accum / 15f).toInt()
                    if (steps != 0) {
                        accum -= steps * 15f
                        onTurn(steps)
                    }
                }
                lastAngle = angle
            }
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = min(size.width, size.height) / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(Brush.radialGradient(listOf(Color(0xFF383838), Color(0xFF141414)), center, r), r, center)
            drawCircle(btnBorder.copy(alpha = 0.8f), r, center, style = Stroke(1.5.dp.toPx()))
            for (i in 0 until 24) {
                val a = Math.toRadians(i * 15.0 - 90)
                val p1 = Offset(center.x + (r - 6.dp.toPx()) * cos(a).toFloat(), center.y + (r - 6.dp.toPx()) * sin(a).toFloat())
                val p2 = Offset(center.x + (r - 18.dp.toPx()) * cos(a).toFloat(), center.y + (r - 18.dp.toPx()) * sin(a).toFloat())
                drawLine(Color(0xFF595959), p1, p2, strokeWidth = 3.dp.toPx())
            }
            val a = Math.toRadians(rotation - 90.0)
            val knob = Offset(center.x + (r - 36.dp.toPx()) * cos(a).toFloat(), center.y + (r - 36.dp.toPx()) * sin(a).toFloat())
            drawCircle(btnBorder, 11.dp.toPx(), knob)
        }
    }
}

/**
 * 針式のSメーター。受信中はS1〜S9+40dB(S9までを弧の6割、それより上を赤で残り4割)、
 * 送信中は出力レベル(P_Meterの0〜100)をPo目盛りで示す。値は棒グラフのメーターと同じ。
 */
@Composable
private fun AnalogMeterView(display: LangstoneDisplay, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val tx = display.meterIsTx
    val value = display.meter
    Canvas(
        modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFFEDE0BD))
            .border(1.5.dp, btnBorder.copy(alpha = 0.8f), RoundedCornerShape(10.dp)),
    ) {
        val sweep = 100.0
        fun fraction(v: Double): Double {
            if (tx) return (v / 100).coerceIn(0.0, 1.0)
            if (v <= 54) return maxOf(v, 0.0) / 54 * 0.6
            return 0.6 + minOf(v - 54, 40.0) / 40 * 0.4
        }
        val pivot = Offset(size.width / 2f, size.height - 8.dp.toPx())
        val radius = min(size.width / 2f - 14.dp.toPx(), size.height - 30.dp.toPx())
        fun point(r: Float, f: Double): Offset {
            val a = Math.toRadians(-sweep / 2 + f * sweep)
            return Offset(pivot.x + r * sin(a).toFloat(), pivot.y - r * cos(a).toFloat())
        }
        val split = if (tx) 1.0 else 0.6
        for ((range, col) in listOf((0.0 to split) to Color.Black, (split to 1.0) to Color.Red)) {
            val (from, to) = range
            if (to <= from) continue
            val path = Path()
            for (i in 0..30) {
                val p = point(radius, from + (to - from) * i / 30)
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(path, col, style = Stroke(2.5.dp.toPx()))
        }
        if (!tx) {
            for (s in 1..9) {
                val f = fraction(s * 6.0)
                drawLine(Color.Black, point(radius, f), point(radius - 6.dp.toPx(), f), 1.5.dp.toPx())
            }
        }
        val ticks = if (tx) listOf(0.0 to "0", 25.0 to "25", 50.0 to "50", 75.0 to "75", 100.0 to "100")
        else listOf(6.0 to "1", 18.0 to "3", 30.0 to "5", 42.0 to "7", 54.0 to "9", 74.0 to "+20", 94.0 to "+40")
        for ((v, label) in ticks) {
            val red = !tx && v > 54
            val f = fraction(v)
            drawLine(if (red) Color.Red else Color.Black, point(radius, f), point(radius - 11.dp.toPx(), f), 2.dp.toPx())
            val layout = measurer.measure(label, TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (red) Color.Red else Color.Black))
            val p = point(radius + 9.dp.toPx(), f)
            drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
        }
        val unit = measurer.measure(if (tx) "Po" else "S", TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black.copy(alpha = 0.7f)))
        drawText(unit, topLeft = Offset(size.width / 2f - unit.size.width / 2f, size.height - 34.dp.toPx() - unit.size.height / 2f))
        val needleEnd = point(radius + 4.dp.toPx(), fraction(value))
        drawLine(Color(0xFFCC0000), pivot, needleEnd, 2.5.dp.toPx())
        drawCircle(Color.Black, 6.dp.toPx(), pivot)
    }
}

/** Sメーター(受信)/出力レベル(送信)と、その上のスケルチ設定バー(S_Meter/P_Meter)。 */
@Composable
private fun MeterBarView(display: LangstoneDisplay, squelch: Int, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(Modifier.fillMaxWidth().height(20.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val u = size.width / 160f // 元の画面の160px幅を全幅とする
                fun bar(value: Double, y: Float, h: Float, color: Color) {
                    if (value < 55) {
                        drawRect(color, Offset(0f, y), androidx.compose.ui.geometry.Size((value * 2 * u).toFloat(), h))
                    } else {
                        drawRect(color, Offset(0f, y), androidx.compose.ui.geometry.Size(110 * u, h))
                        drawRect(lsRed, Offset(110 * u, y), androidx.compose.ui.geometry.Size((minOf((value - 55) * 2, 50.0) * u).toFloat(), h))
                    }
                }
                if (!display.meterIsTx) {
                    bar(squelch.toDouble(), 0f, 3.dp.toPx(), lsGreen)
                    bar(display.meter, 6.dp.toPx(), 14.dp.toPx(), Color.White)
                } else {
                    drawRect(lsGreen, Offset(0f, 6.dp.toPx()),
                        androidx.compose.ui.geometry.Size((minOf(display.meter * 1.5, 160.0) * u).toFloat(), 14.dp.toPx()))
                }
            }
        }
        Text(display.meterText, color = lsGreen, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

/** スペクトラム(上)とウォーターフォール(下)。スペクトラムをタップすると表示幅が切り替わる。 */
@Composable
private fun SpectrumView(display: LangstoneDisplay, modifier: Modifier, onTapSpectrum: () -> Unit) {
    val points = LangstoneController.POINTS
    val ticks = intArrayOf(0, 21, 43, 64, 85, 107, 128, 149, 171, 192, 213)
    val measurer = rememberTextMeasurer()
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().weight(0.45f).pointerInput(Unit) { detectTapGestures { onTapSpectrum() } }) {
            fun x(p: Int) = p * size.width / points
            val half = points / 2
            val top = 4.dp.toPx()
            val bottom = size.height
            val ref = display.spectrumRef.toFloat()
            val spectrum = display.spectrum
            if (spectrum.size == points) {
                val path = Path()
                for (p in 0 until points) {
                    val level = (spectrum[p] - (ref - 80)) / 80f
                    val y = bottom - level * (bottom - top - 6.dp.toPx())
                    if (p == 0) path.moveTo(x(p), y) else path.lineTo(x(p), y)
                }
                drawPath(path, Color.White, style = Stroke(1.2.dp.toPx()))
            }
            // 受信帯域の表示(オレンジ)
            val start = x(half + display.bwBarStart - display.bwBarOffset)
            val end = x(half + display.bwBarEnd - display.bwBarOffset)
            val w = 2.dp.toPx()
            drawLine(lsOrange, Offset(start, top), Offset(end, top), w)
            if (display.bwBarStart > -255) drawLine(lsOrange, Offset(start, top), Offset(start, top + 6.dp.toPx()), w)
            if (display.bwBarEnd < 255) drawLine(lsOrange, Offset(end, top), Offset(end, top + 6.dp.toPx()), w)
            // 表示周波数の位置(赤)
            val cx = x(half + display.centreShift)
            drawLine(lsRed, Offset(cx, top), Offset(cx, bottom - 10.dp.toPx()), 1.5.dp.toPx())
        }
        Canvas(Modifier.fillMaxWidth().height(24.dp)) {
            fun x(p: Int) = p * size.width / points
            val half = points / 2
            drawLine(lsGreen, Offset(0f, 2f), Offset(size.width, 2f), 1.dp.toPx())
            for (t in ticks) for (p in intArrayOf(half + t, half - t)) {
                drawLine(lsGreen, Offset(x(p), 2f), Offset(x(p), 6.dp.toPx()), 1.dp.toPx())
            }
            val labels = arrayOf("10k" to "20k", "5k" to "10k", "2.5k" to "5k", "1.25k" to "2.5k")
            val (l5, l10) = labels[display.fftBW.coerceIn(0, 3)]
            val items = listOf(0 to "0", -ticks[5] to "-$l5", -ticks[10] to "-$l10", ticks[5] to "+$l5", ticks[10] to "+$l10")
            for ((offset, label) in items) {
                val layout = measurer.measure(label, TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace, color = lsGreen))
                drawText(layout, topLeft = Offset(x(half + offset) - layout.size.width / 2f, 8.dp.toPx()))
            }
        }
        Box(Modifier.fillMaxWidth().weight(0.55f).background(Color.Black)) {
            display.waterfall?.let {
                Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.None)
            }
        }
    }
}

/** 設定項目の表示(displaySetting)。ダイヤルのほか、−/+ボタンでも値を変えられる。 */
@Composable
private fun SettingPanel(c: LangstoneController, t: (String, String) -> String) {
    val se = c.settingNo
    val valueStyle = TextStyle(color = Color.White, fontSize = 21.sp, fontFamily = FontFamily.Monospace)
    Row(
        Modifier.fillMaxWidth().border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(t(se.japaneseLabel, se.label), color = Color.White.copy(alpha = 0.8f), fontSize = 21.sp, maxLines = 1)
            when (se) {
                LangstoneSetting.CWID -> {
                    var draft by remember(se) { mutableStateOf(c.cwIdentText) }
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it; c.setCWIdent(it) },
                        singleLine = true,
                        textStyle = valueStyle,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                        cursorBrush = Brush.verticalGradient(listOf(Color.White, Color.White)),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                LangstoneSetting.BAND_BITS_RX, LangstoneSetting.BAND_BITS_TX -> {
                    val text = c.settingValueText(se)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        text.forEachIndexed { i, ch ->
                            Text(ch.toString(), style = valueStyle.copy(color = if (i == c.setIndex) lsGreen else Color.White))
                        }
                    }
                }
                else -> Text(c.settingValueText(se), style = valueStyle, maxLines = 1)
            }
        }
        if (se != LangstoneSetting.CWID) {
            LsButton("−", modifier = Modifier.width(60.dp)) { c.turnDial(-1) }
            LsButton("+", modifier = Modifier.width(60.dp)) { c.turnDial(1) }
        }
    }
}
