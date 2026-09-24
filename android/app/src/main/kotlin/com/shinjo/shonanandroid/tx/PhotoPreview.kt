package com.shinjo.shonanandroid.tx

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 選択した写真(送信時と同じく黒余白で16:9へ収めたもの)にオーバーレイを重ねて表示する。 */
@Composable
fun PhotoPreview(uri: String?, overlay: OverlaySpec, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) {
            PhotoSource.loadFittedBitmap(context, uri, PREVIEW_WIDTH, PREVIEW_HEIGHT)
        }
    }
    Box(modifier = modifier.background(Color.Black)) {
        bitmap?.let {
            Image(bitmap = it.asImageBitmap(), contentDescription = "選択した写真", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        OverlayPreview(overlay, modifier = Modifier.fillMaxSize())
    }
}

/**
 * 送信映像に重なるコールサイン・備考・日時をプレビュー上に表示する(16:9の送信フレームを
 * [ContentScale.Fit]で収めた位置に描く)。日時は毎秒更新する。
 */
@Composable
fun OverlayPreview(overlay: OverlaySpec, modifier: Modifier = Modifier) {
    if (overlay.isEmpty) return
    var bitmap by remember(overlay) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(overlay) {
        while (true) {
            // ★表示中のBitmapをrecycleすると描画時に例外になりうるため、差し替えのみ行いGCに任せる。
            bitmap = withContext(Dispatchers.Default) {
                VideoOverlay.renderBitmap(PREVIEW_WIDTH, PREVIEW_HEIGHT, overlay)
            }
            delay(1000L - System.currentTimeMillis() % 1000L)
        }
    }
    bitmap?.let {
        Image(bitmap = it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = modifier)
    }
}

private const val PREVIEW_WIDTH = 1280
private const val PREVIEW_HEIGHT = 720
