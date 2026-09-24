package com.shinjo.shonanandroid.tx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock

/**
 * 写真フォルダーから選択した静止画を映像フレームとして送出するソース。
 * 画像は縦横比を保ってフレームへ収め、余白は黒で埋める(Shonan_Lite-winの
 * `scale=...:force_original_aspect_ratio=decrease,pad=...`と同じ)。
 * コールサイン・備考・日時は[NV12OverlayBlender]で毎フレーム重ねる(日時は毎秒更新)。
 */
class PhotoSource(private val context: Context) {
    var onFrame: ((data: ByteArray, width: Int, height: Int, presentationTimeUs: Long) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var handlerThread: HandlerThread? = null
    @Volatile private var running = false

    fun start(uriString: String?, overlay: OverlaySpec, width: Int, height: Int, fps: Int) {
        if (running) return
        if (uriString.isNullOrBlank()) {
            onError?.invoke("送信する写真が選択されていません")
            return
        }
        val prepared = loadFittedBitmap(context, uriString, width, height)
        if (prepared == null) {
            onError?.invoke("選択した写真を読み込めません")
            return
        }
        val baseFrame = toNv12(prepared)
        prepared.recycle()
        val blender = NV12OverlayBlender(overlay)

        running = true
        val thread = HandlerThread("PhotoSource").apply { start() }
        handlerThread = thread
        val handler = Handler(thread.looper)
        val intervalMs = (1000L / fps.coerceAtLeast(1))
        val runnable = object : Runnable {
            override fun run() {
                if (!running) return
                val frame = if (blender.isEmpty) baseFrame else baseFrame.copyOf().also { blender.apply(it, width, height) }
                onFrame?.invoke(frame, width, height, SystemClock.elapsedRealtimeNanos() / 1000)
                handler.postDelayed(this, intervalMs)
            }
        }
        handler.post(runnable)
    }

    fun stop() {
        if (!running) return
        running = false
        handlerThread?.let {
            it.quitSafely()
            it.join()
        }
        handlerThread = null
    }

    companion object {
        /** 写真を[width]x[height]へ縦横比を保って収めたビットマップ(余白は黒)。
         *  オーバーレイは含まない。呼び出し側で返却Bitmapをrecycleする。 */
        fun loadFittedBitmap(context: Context, uriString: String?, width: Int = 1280, height: Int = 720): Bitmap? {
            if (uriString.isNullOrBlank()) return null
            val source = runCatching {
                context.contentResolver.openInputStream(Uri.parse(uriString)).use { BitmapFactory.decodeStream(it) }
            }.getOrNull() ?: return null
            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            canvas.drawColor(Color.BLACK)
            val scale = minOf(width.toFloat() / source.width, height.toFloat() / source.height)
            val drawWidth = source.width * scale
            val drawHeight = source.height * scale
            val left = (width - drawWidth) / 2
            val top = (height - drawHeight) / 2
            canvas.drawBitmap(source, null, RectF(left, top, left + drawWidth, top + drawHeight),
                              Paint(Paint.FILTER_BITMAP_FLAG))
            source.recycle()
            return output
        }

        private fun toNv12(bitmap: Bitmap): ByteArray {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val out = ByteArray(width * height * 3 / 2)
            for (row in 0 until height) {
                for (col in 0 until width) {
                    val p = pixels[row * width + col]
                    out[row * width + col] = y((p shr 16) and 0xff, (p shr 8) and 0xff, p and 0xff)
                }
            }
            val chromaOffset = width * height
            for (row in 0 until height / 2) {
                for (col in 0 until width / 2) {
                    val p = pixels[(row * 2) * width + col * 2]
                    val idx = chromaOffset + row * width + col * 2
                    out[idx] = u((p shr 16) and 0xff, (p shr 8) and 0xff, p and 0xff)
                    out[idx + 1] = v((p shr 16) and 0xff, (p shr 8) and 0xff, p and 0xff)
                }
            }
            return out
        }

        private fun y(r: Int, g: Int, b: Int) = (16 + (66 * r + 129 * g + 25 * b) / 256).coerceIn(0, 255).toByte()
        private fun u(r: Int, g: Int, b: Int) = (128 + (-38 * r - 74 * g + 112 * b) / 256).coerceIn(0, 255).toByte()
        private fun v(r: Int, g: Int, b: Int) = (128 + (112 * r - 94 * g - 18 * b) / 256).coerceIn(0, 255).toByte()
    }
}
