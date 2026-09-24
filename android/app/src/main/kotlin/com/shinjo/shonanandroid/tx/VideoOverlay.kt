package com.shinjo.shonanandroid.tx

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.shinjo.shonanandroid.core.AppSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * カメラ・写真の送信映像へ重ねるコールサイン/備考/日時オーバーレイ --
 * Shonan_Lite-win(`app/gui/backend.py`の`_render_overlay_image`/`_build_overlay_pipeline`)の移植。
 *
 * 配置はWin版と同じ(座標・文字サイズは1920x1080上の値で、実際のフレーム高さに比例して縮小する):
 * - コールサイン: 左上(24, 24)、左寄せ
 * - 備考: 右下、右端24px、上端 1080-126-文字サイズ(日時のすぐ上)、右寄せ
 * - 日時: 右下、右端24px・下端24px、24px白、毎秒更新
 * コールサイン・備考が両方空ならオーバーレイ(日時を含む)は描かない。
 * テストパターンは画像自体にコールサインが描かれているため対象外(呼び出し側で適用しない)。
 */
data class OverlaySpec(
    val callsign: String,
    val note: String,
    val callsignFontSize: Int,
    val noteFontSize: Int,
    val callsignColor: String,
    val noteColor: String,
) {
    val isEmpty: Boolean get() = callsign.isEmpty() && note.isEmpty()

    companion object {
        fun from(settings: AppSettings) = OverlaySpec(
            callsign = settings.photoCallsign.trim(),
            note = settings.photoNote.trim(),
            callsignFontSize = settings.overlayCallsignFontSize,
            noteFontSize = settings.overlayNoteFontSize,
            callsignColor = settings.overlayCallsignColor,
            noteColor = settings.overlayNoteColor,
        )
    }
}

object VideoOverlay {
    /** コールサイン文字サイズの選択肢(px、1920x1080の送信映像上での大きさ。Win版と同じ)。 */
    val CALLSIGN_FONT_SIZES = listOf(36, 48, 68, 96, 128, 192, 256)
    val NOTE_FONT_SIZES = listOf(16, 24, 32, 48, 64)
    /** 文字色の選択肢(日本語名, 英語名, "#RRGGBB")。Win版と同じ。 */
    val COLORS = listOf(
        Triple("白", "White", "#FFFFFF"),
        Triple("黄", "Yellow", "#FFFF00"),
        Triple("赤", "Red", "#FF3030"),
        Triple("緑", "Green", "#00E000"),
        Triple("青", "Blue", "#3080FF"),
        Triple("水色", "Cyan", "#00FFFF"),
        Triple("橙", "Orange", "#FF9900"),
        Triple("黒", "Black", "#000000"),
    )

    private const val REFERENCE_HEIGHT = 1080f
    private const val MARGIN = 24f
    private const val DATE_FONT_SIZE = 24f
    private const val NOTE_BOTTOM_OFFSET = 126f

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun dateText(timeMs: Long = System.currentTimeMillis()): String =
        synchronized(dateFormat) { dateFormat.format(Date(timeMs)) }

    /** "#RRGGBB"をARGBへ変換する。不正な値は白(Win版`_parse_color`と同じ)。 */
    fun parseColor(value: String): Int {
        val hex = value.trim().removePrefix("#")
        val rgb = if (hex.length == 6) hex.toIntOrNull(16) else null
        return 0xFF000000.toInt() or (rgb ?: 0xFFFFFF)
    }

    private fun clampFontSize(size: Int) = size.coerceIn(8, 256)

    private fun textPaint(color: Int, size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        typeface = Typeface.DEFAULT
    }

    /** 文字の影。通常は黒、黒など暗い文字色では見えないため白っぽい影にする(Win版と同じ)。 */
    private fun shadowColor(color: Int): Int {
        val r = (color shr 16) and 0xff
        val g = (color shr 8) and 0xff
        val b = color and 0xff
        val luminance = 0.299 * r + 0.587 * g + 0.114 * b
        return if (luminance >= 80) 0xCC000000.toInt() else 0xCCFFFFFF.toInt()
    }

    /** [top]は文字の上端(アセンダ位置)。[alignRight]なら[x]が右端。 */
    private fun drawText(canvas: Canvas, text: String, x: Float, top: Float, size: Float, color: Int,
                         alignRight: Boolean, shadowOffset: Float) {
        val paint = textPaint(color, size)
        paint.textAlign = if (alignRight) Paint.Align.RIGHT else Paint.Align.LEFT
        val baseline = top - paint.ascent()
        val shadow = Paint(paint).apply { this.color = shadowColor(color) }
        canvas.drawText(text, x + shadowOffset, baseline + shadowOffset, shadow)
        canvas.drawText(text, x, baseline, paint)
    }

    private fun scaleOf(height: Int) = height / REFERENCE_HEIGHT
    private fun shadowOffsetOf(scale: Float) = scale.roundToInt().coerceAtLeast(1).toFloat()

    /** コールサインと備考(毎秒変わらない部分)を描く。 */
    fun drawStatic(canvas: Canvas, width: Int, height: Int, spec: OverlaySpec) {
        if (spec.isEmpty) return
        val scale = scaleOf(height)
        val shadowOffset = shadowOffsetOf(scale)
        if (spec.callsign.isNotEmpty()) {
            drawText(canvas, spec.callsign, MARGIN * scale, MARGIN * scale,
                     clampFontSize(spec.callsignFontSize) * scale, parseColor(spec.callsignColor),
                     alignRight = false, shadowOffset = shadowOffset)
        }
        if (spec.note.isNotEmpty()) {
            // 文字サイズに関わらず備考の下端を日時のすぐ上に揃える(Win版と同じ式)。
            val size = clampFontSize(spec.noteFontSize)
            drawText(canvas, spec.note, width - MARGIN * scale,
                     (REFERENCE_HEIGHT - NOTE_BOTTOM_OFFSET - size) * scale, size * scale,
                     parseColor(spec.noteColor), alignRight = true, shadowOffset = shadowOffset)
        }
    }

    /** 日時を右下へ描く。[canvasTop]は[canvas]の上端がフレーム上のどの行に当たるか
     *  (日時だけを描く帯状ビットマップ用)。 */
    fun drawDate(canvas: Canvas, width: Int, height: Int, text: String, canvasTop: Int = 0) {
        val scale = scaleOf(height)
        val paint = textPaint(0xFFFFFFFF.toInt(), DATE_FONT_SIZE * scale).apply { textAlign = Paint.Align.RIGHT }
        // ffmpeg drawtextの y=h-text_h-24 と同じく、数字の下端(ベースライン)を下から24pxに置く。
        val baseline = height - MARGIN * scale - canvasTop
        val shadowOffset = shadowOffsetOf(scale)
        val shadow = Paint(paint).apply { color = shadowColor(0xFFFFFFFF.toInt()) }
        canvas.drawText(text, width - MARGIN * scale + shadowOffset, baseline + shadowOffset, shadow)
        canvas.drawText(text, width - MARGIN * scale, baseline, paint)
    }

    /** 日時の帯の高さ(フレーム下端からの行数)。 */
    fun dateStripHeight(height: Int): Int =
        (((MARGIN + DATE_FONT_SIZE * 1.5f) * scaleOf(height)).roundToInt() + 4).coerceAtMost(height) and 1.inv()

    /** プレビュー用: オーバーレイ全体(日時を含む)を透過ビットマップへ描く。 */
    fun renderBitmap(width: Int, height: Int, spec: OverlaySpec, date: String = dateText()): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        if (spec.isEmpty) return bitmap
        val canvas = Canvas(bitmap)
        drawStatic(canvas, width, height, spec)
        drawDate(canvas, width, height, date)
        return bitmap
    }
}

/**
 * NV12フレームへ[OverlaySpec]を合成する。文字の画素だけを疎な配列として事前計算し、
 * フレームごとにはその画素だけをアルファ合成する(1フレーム全体をBitmap経由で変換しない)。
 * コールサイン・備考はフレームサイズごとに1回、日時は秒が変わったときだけ再計算する。
 * 1インスタンスを複数スレッドから同時に呼ばないこと。
 */
class NV12OverlayBlender(private val spec: OverlaySpec) {
    private var layoutWidth = 0
    private var layoutHeight = 0
    private var staticLayer: Layer? = null
    private var dateLayer: Layer? = null
    private var dateLayerText = ""

    val isEmpty: Boolean get() = spec.isEmpty

    fun apply(nv12: ByteArray, width: Int, height: Int) {
        if (spec.isEmpty) return
        if (width != layoutWidth || height != layoutHeight) {
            layoutWidth = width
            layoutHeight = height
            staticLayer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).let { bitmap ->
                VideoOverlay.drawStatic(Canvas(bitmap), width, height, spec)
                Layer.from(bitmap, width, height, 0).also { bitmap.recycle() }
            }
            dateLayer = null
        }
        val text = VideoOverlay.dateText()
        if (dateLayer == null || text != dateLayerText) {
            val stripHeight = VideoOverlay.dateStripHeight(height)
            val top = height - stripHeight
            dateLayer = Bitmap.createBitmap(width, stripHeight, Bitmap.Config.ARGB_8888).let { bitmap ->
                VideoOverlay.drawDate(Canvas(bitmap), width, height, text, canvasTop = top)
                Layer.from(bitmap, width, height, top).also { bitmap.recycle() }
            }
            dateLayerText = text
        }
        staticLayer?.blend(nv12)
        dateLayer?.blend(nv12)
    }

    /** 不透明度>0の画素だけを保持した合成用レイヤー(輝度は全画素、色差は2x2ブロックの左上画素)。 */
    private class Layer(
        private val yIndex: IntArray, private val yAlpha: IntArray, private val yValue: IntArray,
        private val cIndex: IntArray, private val cAlpha: IntArray,
        private val uValue: IntArray, private val vValue: IntArray,
    ) {
        fun blend(nv12: ByteArray) {
            for (i in yIndex.indices) {
                val idx = yIndex[i]
                val a = yAlpha[i]
                nv12[idx] = (((nv12[idx].toInt() and 0xff) * (255 - a) + yValue[i] * a) / 255).toByte()
            }
            for (i in cIndex.indices) {
                val idx = cIndex[i]
                val a = cAlpha[i]
                nv12[idx] = (((nv12[idx].toInt() and 0xff) * (255 - a) + uValue[i] * a) / 255).toByte()
                nv12[idx + 1] = (((nv12[idx + 1].toInt() and 0xff) * (255 - a) + vValue[i] * a) / 255).toByte()
            }
        }

        companion object {
            /** [bitmap]はフレーム幅[frameWidth]で、フレームの[top]行目から下に重ねる。 */
            fun from(bitmap: Bitmap, frameWidth: Int, frameHeight: Int, top: Int): Layer {
                val w = bitmap.width
                val h = bitmap.height
                val pixels = IntArray(w * h)
                bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                val chromaOffset = frameWidth * frameHeight
                val yi = ArrayList<Int>(); val ya = ArrayList<Int>(); val yv = ArrayList<Int>()
                val ci = ArrayList<Int>(); val ca = ArrayList<Int>(); val uv = ArrayList<Int>(); val vv = ArrayList<Int>()
                for (row in 0 until h) {
                    val frameRow = top + row
                    for (col in 0 until w) {
                        val p = pixels[row * w + col]
                        val a = (p ushr 24) and 0xff
                        if (a == 0) continue
                        // Bitmapは乗算済みアルファではない(getPixelsは非乗算値を返す)。
                        val r = (p shr 16) and 0xff
                        val g = (p shr 8) and 0xff
                        val b = p and 0xff
                        yi.add(frameRow * frameWidth + col); ya.add(a)
                        yv.add((16 + (66 * r + 129 * g + 25 * b) / 256).coerceIn(0, 255))
                        if (frameRow % 2 == 0 && col % 2 == 0) {
                            ci.add(chromaOffset + (frameRow / 2) * frameWidth + col); ca.add(a)
                            uv.add((128 + (-38 * r - 74 * g + 112 * b) / 256).coerceIn(0, 255))
                            vv.add((128 + (112 * r - 94 * g - 18 * b) / 256).coerceIn(0, 255))
                        }
                    }
                }
                return Layer(yi.toIntArray(), ya.toIntArray(), yv.toIntArray(),
                             ci.toIntArray(), ca.toIntArray(), uv.toIntArray(), vv.toIntArray())
            }
        }
    }
}
