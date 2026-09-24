package com.shinjo.shonanandroid.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.shinjo.shonanandroid.AppViewModel
import com.shinjo.shonanandroid.tx.CameraPosition
import com.shinjo.shonanandroid.tx.CameraPreviewView
import com.shinjo.shonanandroid.core.AppSettings
import com.shinjo.shonanandroid.tx.ColorBarPreview
import com.shinjo.shonanandroid.tx.OverlayPreview
import com.shinjo.shonanandroid.tx.OverlaySpec
import com.shinjo.shonanandroid.tx.PhotoPreview
import com.shinjo.shonanandroid.tx.VideoOverlay

private val OuterCardBorder = Color(0xFF34434B)
private val OuterCardBackground = Color(0xFF101416)
private val InnerCardBackground = Color(0xFF191D1F)
private val TitleCyan = Color(0xFF54BCE0)
private val ChipBackground = Color(0xFF303538)
private val AccentBlue = Color(0xFF1677FF)
private val PreviewBackground = Color(0xFF050607)
private val PreviewBorder = Color(0xFF46545B)
private val PreviewText = Color(0xFFAAB7BD)

/** コールサイン・備考の入力文字と入力枠(暗い背景で見やすいよう白)。 */
private val OverlayInputTextStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 15.sp)

private enum class VideoSourceOption { CAMERA_BACK, CAMERA_FRONT, PHOTO, COLOR_BAR }

private fun videoSourceOption(useFrontCamera: Boolean, useColorBarSource: Boolean, usePhotoSource: Boolean): VideoSourceOption = when {
    usePhotoSource -> VideoSourceOption.PHOTO
    useColorBarSource -> VideoSourceOption.COLOR_BAR
    useFrontCamera -> VideoSourceOption.CAMERA_FRONT
    else -> VideoSourceOption.CAMERA_BACK
}

private fun applyVideoSourceOption(viewModel: AppViewModel, option: VideoSourceOption) {
    viewModel.updateSettings { s ->
        s.copy(
            useFrontCamera = option == VideoSourceOption.CAMERA_FRONT,
            usePhotoSource = option == VideoSourceOption.PHOTO,
            useColorBarSource = option == VideoSourceOption.COLOR_BAR,
        )
    }
}

/**
 * 映像ソース設定画面 -- Shonan_Lite-pi5(`pi5/gui/screens/videosource.py`)の見た目に
 * できる限り忠実に合わせた版。外枠カードを画面いっぱいに広げ、pi5に存在しない運用メモ
 * 注記は削除した。pi5にある解像度/フレームレートのコンボボックスはAndroid版では固定
 * (Full HD/30fps)のため、代わりにマイク音声送信のトグルを下段に配置する。
 * コールサイン/備考のオーバーレイ(文字サイズ・文字色の選択を含む)はShonan_Lite-winの
 * 映像ソース画面(`app/gui/screens/videosource.py`)と同じ仕様で、カメラ・写真に適用し
 * テストパターンには適用しない。入力欄は映像ソースに関わらず常に表示する。
 */
@Composable
fun VideoSourceSettingsScreen(viewModel: AppViewModel, navController: NavHostController) {
    val settings = viewModel.settings
    val context = LocalContext.current
    val currentOption = videoSourceOption(settings.useFrontCamera, settings.useColorBarSource, settings.usePhotoSource)
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        viewModel.updateSettings {
            it.copy(usePhotoSource = true, useColorBarSource = false, useFrontCamera = false, selectedPhotoUri = uri.toString())
        }
    }

    // iPad版`VideoSourceSettingsView`のonAppear/onChange/onDisappearと同じく、この画面を
    // 表示している間だけ(送信中でなければ)カメラプレビューを起動する。カメラ選択時のみ
    // 起動し、他ソース選択時や画面離脱時は停止する(TxScreenと同じカメラを共有するため、
    // 実送信中はTxController.start側が管理しており何もしない)。
    DisposableEffect(settings.useFrontCamera, settings.useColorBarSource, settings.usePhotoSource) {
        val isCameraSource = !settings.useColorBarSource && !settings.usePhotoSource
        if (isCameraSource) {
            viewModel.txController.startCameraPreview(if (settings.useFrontCamera) CameraPosition.FRONT else CameraPosition.BACK)
        } else {
            viewModel.txController.stopCameraPreviewIfIdle()
        }
        onDispose { viewModel.txController.stopCameraPreviewIfIdle() }
    }

    SettingsSubScreen(
        title = settings.t("映像ソース", "Video Source"),
        onBack = { navController.popBackStack("home", false) },
        homeLabel = settings.t("ホームへ戻る", "Home"),
        scrollEnabled = false,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .background(OuterCardBackground, RoundedCornerShape(14.dp))
                .border(1.dp, OuterCardBorder, RoundedCornerShape(14.dp))
                .padding(12.dp, 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(InnerCardBackground, RoundedCornerShape(10.dp))
                        .border(1.dp, OuterCardBorder, RoundedCornerShape(10.dp))
                        .padding(10.dp, 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        settings.t("映像ソース選択", "Video Source"),
                        color = TitleCyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    SourceChip(
                        settings.t("背面カメラ(既定)", "Rear Camera"),
                        selected = currentOption == VideoSourceOption.CAMERA_BACK,
                    ) { applyVideoSourceOption(viewModel, VideoSourceOption.CAMERA_BACK) }
                    SourceChip(
                        settings.t("前面カメラ", "Front Camera"),
                        selected = currentOption == VideoSourceOption.CAMERA_FRONT,
                    ) { applyVideoSourceOption(viewModel, VideoSourceOption.CAMERA_FRONT) }
                    SourceChip(
                        settings.t("写真(写真フォルダー)", "Photo (Folder)"),
                        selected = currentOption == VideoSourceOption.PHOTO,
                    ) { photoPicker.launch(arrayOf("image/*")) }
                    SourceChip(
                        settings.t("テストパターン", "Test Pattern"),
                        selected = currentOption == VideoSourceOption.COLOR_BAR,
                    ) { applyVideoSourceOption(viewModel, VideoSourceOption.COLOR_BAR) }
                }

                Column(
                    modifier = Modifier
                        .weight(2f)
                        .fillMaxHeight()
                        .background(InnerCardBackground, RoundedCornerShape(10.dp))
                        .border(1.dp, OuterCardBorder, RoundedCornerShape(10.dp))
                        .padding(10.dp, 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        settings.t("プレビュー", "Preview"),
                        color = TitleCyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(PreviewBackground, RoundedCornerShape(6.dp))
                            .border(1.dp, PreviewBorder, RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        when (currentOption) {
                            VideoSourceOption.COLOR_BAR -> ColorBarPreview(modifier = Modifier.fillMaxSize())
                            VideoSourceOption.PHOTO -> if (settings.selectedPhotoUri.isNullOrBlank()) {
                                Text(settings.t("写真が未選択です", "No photo selected"), color = PreviewText, fontSize = 13.sp)
                            } else {
                                PhotoPreview(
                                    uri = settings.selectedPhotoUri,
                                    overlay = OverlaySpec.from(settings),
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            else -> {
                                CameraPreviewView(preview = viewModel.activeTxPreview, modifier = Modifier.fillMaxSize())
                                OverlayPreview(OverlaySpec.from(settings), modifier = Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }

            if (settings.usePhotoSource) {
                Text(
                    if (settings.selectedPhotoUri.isNullOrBlank()) settings.t("写真が未選択です", "No photo selected")
                    else settings.t("選択済み: ${settings.selectedPhotoUri!!.substringAfterLast('/')}", "Selected: ${settings.selectedPhotoUri!!.substringAfterLast('/')}"),
                    color = Color(0xFFCCCCCC),
                    fontSize = 12.sp,
                )
            }

            // 映像へ焼き込むコールサイン・備考(カメラ・写真に適用。テストパターンには元々
            // コールサインが描かれているため適用しない)。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = settings.photoCallsign,
                    textStyle = OverlayInputTextStyle,
                    colors = overlayInputColors(),
                    onValueChange = { viewModel.updateSettings { s -> s.copy(photoCallsign = it) } },
                    label = { Text(settings.t("コールサイン", "Callsign")) },
                    placeholder = { Text(settings.t("例: JA1XXX", "e.g. JA1XXX")) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                FontSizeDropdown(settings, VideoOverlay.CALLSIGN_FONT_SIZES, settings.overlayCallsignFontSize) { size ->
                    viewModel.updateSettings { it.copy(overlayCallsignFontSize = size) }
                }
                ColorDropdown(settings, settings.t("コールサインの文字色", "Callsign color"), settings.overlayCallsignColor) { color ->
                    viewModel.updateSettings { it.copy(overlayCallsignColor = color) }
                }
                OutlinedTextField(
                    value = settings.photoNote,
                    textStyle = OverlayInputTextStyle,
                    colors = overlayInputColors(),
                    onValueChange = { viewModel.updateSettings { s -> s.copy(photoNote = it) } },
                    label = { Text(settings.t("備考", "Note")) },
                    placeholder = { Text(settings.t("任意", "Optional")) },
                    modifier = Modifier.weight(1.5f),
                    singleLine = true,
                )
                FontSizeDropdown(settings, VideoOverlay.NOTE_FONT_SIZES, settings.overlayNoteFontSize) { size ->
                    viewModel.updateSettings { it.copy(overlayNoteFontSize = size) }
                }
                ColorDropdown(settings, settings.t("備考の文字色", "Note color"), settings.overlayNoteColor) { color ->
                    viewModel.updateSettings { it.copy(overlayNoteColor = color) }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(settings.t("マイク音声を送信", "Transmit Mic Audio"), color = Color(0xFFEEEEEE), fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = settings.transmitAudio,
                    onCheckedChange = { enabled -> viewModel.updateSettings { it.copy(transmitAudio = enabled) } },
                )
            }
        }
    }
}

@Composable
private fun SourceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) AccentBlue else ChipBackground,
            contentColor = Color.White,
        ),
        shape = RoundedCornerShape(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        modifier = Modifier.fillMaxWidth().height(30.dp),
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
    }
}

/** 文字サイズの選択(設定値が選択肢外でもその値を表示・維持する。Win版と同じ)。 */
@Composable
private fun FontSizeDropdown(settings: AppSettings, sizes: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = if (selected in sizes) sizes else sizes + selected
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text("${selected}px ▼", color = Color(0xFFEEEEEE), fontSize = 13.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Text(settings.t("文字サイズ", "Font size"), color = TitleCyan, fontSize = 12.sp,
                 modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            options.forEach { size ->
                DropdownMenuItem(
                    text = { Text("${size}px", fontWeight = if (size == selected) FontWeight.Bold else FontWeight.Normal) },
                    onClick = { expanded = false; onSelect(size) },
                )
            }
        }
    }
}

/** 文字色の選択(色見本付き。設定値が選択肢外でもその色を表示・維持する)。 */
@Composable
private fun ColorDropdown(settings: AppSettings, title: String, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val known = VideoOverlay.COLORS.map { (ja, en, hex) -> settings.t(ja, en) to hex }
    val options = if (known.any { it.second.equals(selected, ignoreCase = true) }) known else known + (selected to selected)
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) {
            ColorSwatch(selected)
            Text(" ▼", color = Color(0xFFEEEEEE), fontSize = 13.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Text(title, color = TitleCyan, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            options.forEach { (label, hex) ->
                DropdownMenuItem(
                    leadingIcon = { ColorSwatch(hex) },
                    text = { Text(label, fontWeight = if (hex.equals(selected, ignoreCase = true)) FontWeight.Bold else FontWeight.Normal) },
                    onClick = { expanded = false; onSelect(hex) },
                )
            }
        }
    }
}

@Composable
private fun ColorSwatch(hex: String) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .background(Color(VideoOverlay.parseColor(hex)), RoundedCornerShape(3.dp))
            .border(1.dp, Color(0xFF888888), RoundedCornerShape(3.dp)),
    )
}

@Composable
private fun overlayInputColors() = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = Color.White,
    unfocusedBorderColor = Color.White,
    focusedLabelColor = Color.White,
    unfocusedLabelColor = Color.White,
    focusedPlaceholderColor = Color(0xFFAAAAAA),
    unfocusedPlaceholderColor = Color(0xFFAAAAAA),
    cursorColor = Color.White,
)
