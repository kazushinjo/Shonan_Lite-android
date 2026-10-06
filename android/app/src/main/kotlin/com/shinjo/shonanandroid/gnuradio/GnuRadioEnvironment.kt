package com.shinjo.shonanandroid.gnuradio

import android.content.Context
import android.system.Os
import java.io.File

/**
 * 同梱のGNU Radio(third_party/gr-dvbs2rx-install-arm64-v8a)を読み込む前の準備。
 * 受信(dvbs2rx_bridge)とLangstone(langstone_bridge)の両方から使う。
 */
object GnuRadioEnvironment {
    @Volatile private var ready = false

    /**
     * ★gr::prefs::singleton()がgr::prefix()/prefsdir()経由でビルド時に焼き込まれた
     * ホストPCのパス(Android実機に存在しない)をstd::filesystem::canonical()にかけ、
     * filesystem_errorが未捕捉のままSIGABRTするクラッシュを実機で確認済み
     * (`System.loadLibrary("gnuradio-runtime")`の静的初期化中に発生するため、
     * ネイティブ側で環境変数を設定しても手遅れ -- ここで`System.loadLibrary`より前に
     * 設定する必要がある)。GR_PREFIX環境変数で上書きできる(gnuradio-runtime/lib/constants.cc.in参照)。
     * 同じくgr::vmcircbuf系が前提にする書き込み可能な/tmpが無い問題(Android実機での既知事項)には
     * TMP環境変数で対応する。gr::fft::fftはFFTW wisdomを$XDG_CACHE_HOME(未設定なら$HOME/.cache)へ
     * 書くため、iPad版と同じく書き込めるキャッシュフォルダを指定しておく。
     */
    @Synchronized
    fun ensure(context: Context) {
        if (ready) return
        val grPrefix = File(context.cacheDir, "gr_prefix")
        File(grPrefix, "etc/gnuradio/conf.d").mkdirs()
        Os.setenv("GR_PREFIX", grPrefix.absolutePath, true)
        Os.setenv("TMP", context.cacheDir.absolutePath, true)
        if (Os.getenv("XDG_CACHE_HOME") == null) {
            Os.setenv("XDG_CACHE_HOME", context.cacheDir.absolutePath, true)
        }

        // ★依存順(NEEDEDの逆トポロジカル順)にロードする: pmt/volk → runtime →
        // blocks → fft → filter → analog/iio/dvbs2rx。
        System.loadLibrary("gnuradio-pmt")
        System.loadLibrary("volk")
        System.loadLibrary("gnuradio-runtime")
        System.loadLibrary("gnuradio-blocks")
        System.loadLibrary("gnuradio-fft")
        System.loadLibrary("gnuradio-filter")
        System.loadLibrary("gnuradio-analog")
        System.loadLibrary("gnuradio-iio")
        System.loadLibrary("gnuradio-dvbs2rx")
        ready = true
    }
}
