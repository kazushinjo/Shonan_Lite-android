package com.shinjo.shonanandroid.tx

import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** CameraXの[PreviewView]をホストする -- iOS版`CameraPreviewView.swift`
 *  (AVCaptureVideoPreviewLayer)のAndroid対応。[preview]はこのcomposableより
 *  長生き(AppViewModelがProcessLifecycleOwnerに束縛して保持)するため、
 *  画面遷移後もゴースト表示が残らないようdispose時に明示的にsurface providerを解除する。 */
@Composable
fun CameraPreviewView(preview: Preview, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            PreviewView(context).also { previewView ->
                // SurfaceViewはComposeのテキストより前面に残ることがあるため、
                // 診断画面でもレイアウト順を守るTextureViewモードを使う。
                previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                preview.setSurfaceProvider(previewView.surfaceProvider)
            }
        },
        update = { previewView ->
            preview.setSurfaceProvider(previewView.surfaceProvider)
        },
    )
    DisposableEffect(preview) {
        onDispose {
            preview.setSurfaceProvider(null)
        }
    }
}
