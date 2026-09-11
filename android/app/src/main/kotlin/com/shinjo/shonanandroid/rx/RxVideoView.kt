package com.shinjo.shonanandroid.rx

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** RXの復号済みライブ映像を表示する[SurfaceView]をホストする --
 *  iOS版`DisplayLayerView.swift`(AVSampleBufferDisplayLayer)のAndroid対応。
 *  [H264DisplaySurface]自体は所有せず、Surfaceをコールバック経由で渡すだけの
 *  薄いビューホストに留める(CameraPreviewViewと対称的なパターン)。 */
@Composable
fun RxVideoView(
    modifier: Modifier = Modifier,
    onSurfaceAvailable: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        onSurfaceAvailable(holder.surface)
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        onSurfaceDestroyed()
                    }
                })
            }
        },
    )
}
