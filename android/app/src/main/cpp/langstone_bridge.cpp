// Langstone V3(g4eml氏のSDRトランシーバー、Shonan_Lite-RasPI5のpi5/third_party/Langstone-V3)の
// GNU Radioフローグラフ`Lang_TRX_Pluto.py`をC++で組み直したもの。iPad版
// (Shonan_Lite-iPad/Shonan/Shonan/Langstone/LangstoneTRxSession.mm)と同じブロック構成・
// パラメータ・各setterの式にしてある。
//
// iPad版との違い:
// - 音声入出力はAAudio(48kHz、モノラル、float)をこのファイルの中で直接扱う。
// - FFT結果はコールバックではなく最新の1フレームを保持し、Kotlin側が100回/秒で取りに来る。
// - 同梱のAndroid版GNU RadioはFORCE_SINGLE_MAPPEDではない(既定のdouble mapped)ため、
//   iPad版のbuffer_double_mappedへの差し替えは不要。
// - Plutoの直接制御(LangstonePlutoControl.swift相当)もここに置き、libiio 0.xの関数は
//   libgnuradio-iio.soが公開しているものを使う。

#include <jni.h>
#include <android/log.h>
#include <aaudio/AAudio.h>

#include <gnuradio/top_block.h>
#include <gnuradio/sync_block.h>
#include <gnuradio/io_signature.h>
#include <gnuradio/iio/fmcomms2_source.h>
#include <gnuradio/iio/fmcomms2_sink.h>
#include <gnuradio/filter/firdes.h>
#include <gnuradio/filter/fir_filter_blk.h>
#include <gnuradio/filter/freq_xlating_fir_filter.h>
#include <gnuradio/filter/rational_resampler.h>
#include <gnuradio/filter/iir_filter_ffd.h>
#include <gnuradio/analog/agc3_cc.h>
#include <gnuradio/analog/sig_source.h>
#include <gnuradio/analog/rail_ff.h>
#include <gnuradio/analog/quadrature_demod_cf.h>
#include <gnuradio/analog/frequency_modulator_fc.h>
#include <gnuradio/blocks/add_blk.h>
#include <gnuradio/blocks/add_const_ff.h>
#include <gnuradio/blocks/multiply_const.h>
#include <gnuradio/blocks/mute.h>
#include <gnuradio/blocks/keep_one_in_n.h>
#include <gnuradio/blocks/float_to_complex.h>
#include <gnuradio/blocks/complex_to_real.h>
#include <gnuradio/blocks/complex_to_mag.h>
#include <gnuradio/fft/fft.h>
#include <gnuradio/fft/window.h>

#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdio>
#include <deque>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#define LOG_TAG "Langstone"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// libiio 0.x(libgnuradio-iio.soが公開している関数)。同梱のヘッダは1.x用のため、使う関数だけ宣言する。
extern "C" {
struct iio_context;
struct iio_device;
struct iio_channel;
struct iio_context *iio_create_network_context(const char *host);
void iio_context_destroy(struct iio_context *ctx);
int iio_context_set_timeout(struct iio_context *ctx, unsigned int timeout_ms);
struct iio_device *iio_context_find_device(const struct iio_context *ctx, const char *name);
struct iio_channel *iio_device_find_channel(const struct iio_device *dev, const char *name, bool output);
int iio_channel_attr_write_longlong(const struct iio_channel *chn, const char *attr, long long val);
int iio_channel_attr_write_double(const struct iio_channel *chn, const char *attr, double val);
int iio_channel_attr_write_bool(const struct iio_channel *chn, const char *attr, bool val);
ssize_t iio_channel_attr_write(const struct iio_channel *chn, const char *attr, const char *src);
int iio_channel_attr_read_double(const struct iio_channel *chn, const char *attr, double *val);
ssize_t iio_device_debug_attr_write(const struct iio_device *dev, const char *attr, const char *src);
}

using namespace gr;

namespace {

constexpr double kAudioRate = 48000.0;
constexpr unsigned long kPlutoRate = 528000;
// iPad版と同じ。Pi5版は0x800だが、Wi-Fi経由では遅れの揺らぎでPluto側のバッファが
// 溢れ/空になり音が途切れるため、受信は約62ms、送信は約31msにする。
constexpr unsigned long kRxIioBufferSize = 0x8000;
constexpr unsigned long kTxIioBufferSize = 0x4000;
constexpr int kFFTSize = 512;

/// 単一書き手・単一読み手のリングバッファ(音声の受け渡し用)。
class FloatRing {
public:
    explicit FloatRing(size_t capacity) : _buf(capacity), _cap(capacity) {}

    /// 書き手側。空きが足りない分は捨てる。
    size_t write(const float *p, size_t n) {
        size_t w = _w.load(std::memory_order_relaxed);
        size_t r = _r.load(std::memory_order_acquire);
        size_t space = _cap - (w - r);
        if (n > space) { n = space; }
        for (size_t i = 0; i < n; ++i) { _buf[(w + i) % _cap] = p[i]; }
        _w.store(w + n, std::memory_order_release);
        return n;
    }

    /// 読み手側。読めた数を返す。
    size_t read(float *p, size_t n) {
        size_t r = _r.load(std::memory_order_relaxed);
        size_t w = _w.load(std::memory_order_acquire);
        size_t avail = w - r;
        if (n > avail) { n = avail; }
        for (size_t i = 0; i < n; ++i) { p[i] = _buf[(r + i) % _cap]; }
        _r.store(r + n, std::memory_order_release);
        return n;
    }

    size_t available() const {
        return _w.load(std::memory_order_acquire) - _r.load(std::memory_order_acquire);
    }

    /// 読み手側。溜まり過ぎた古いデータを捨てて遅延を戻す(書き手とのクロック差対策)。
    size_t skipTo(size_t keep) {
        size_t r = _r.load(std::memory_order_relaxed);
        size_t w = _w.load(std::memory_order_acquire);
        if (w - r <= keep) { return 0; }
        _r.store(w - keep, std::memory_order_release);
        return w - r - keep;
    }

private:
    std::vector<float> _buf;
    size_t _cap;
    std::atomic<size_t> _w{0};
    std::atomic<size_t> _r{0};
};

/// 音声の受け渡しの統計(調査用、Kotlin側が5秒ごとにログへ出す)。
struct AudioStats {
    std::atomic<uint64_t> rxIn{0};
    std::atomic<uint64_t> rxOverflow{0};
    std::atomic<uint64_t> rxSkipped{0};
    std::atomic<uint64_t> rxUnderruns{0};
    std::atomic<uint64_t> micIn{0};
    std::atomic<uint64_t> micZeroFill{0};
};

/// gr-audioのaudio.sourceの代わり。AAudioの入力から渡されたマイク音声を出す。
/// 送信系はPlutoの送信バッファに引っ張られて一定速度で流れるため(受信中も無音を送り続ける
/// Pi5版と同じ)、マイク音声が来ないときは短く待ってから無音を出し、流れを止めない。
class MicSource : public sync_block {
public:
    static basic_block_sptr make(std::shared_ptr<FloatRing> ring, std::shared_ptr<std::atomic_bool> stopping,
                                 std::shared_ptr<AudioStats> stats) {
        return basic_block_sptr(new MicSource(std::move(ring), std::move(stopping), std::move(stats)));
    }

    int work(int noutput_items, gr_vector_const_void_star &, gr_vector_void_star &output_items) override {
        float *out = static_cast<float *>(output_items[0]);
        // マイク側が速い場合に遅延が積み上がらないよう、300ms以上溜まったら100msまで捨てる。
        if (_ring->available() > 14400) { _ring->skipTo(4800); }
        for (int wait = 0; wait < 4 && _ring->available() == 0 && !_stopping->load(); ++wait) {
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        }
        size_t got = _ring->read(out, (size_t)noutput_items);
        if (got > 0) { return (int)got; }
        _stats->micZeroFill++;
        int n = noutput_items < 480 ? noutput_items : 480; // 10ms分の無音
        std::fill(out, out + n, 0.0f);
        return n;
    }

private:
    MicSource(std::shared_ptr<FloatRing> ring, std::shared_ptr<std::atomic_bool> stopping,
              std::shared_ptr<AudioStats> stats)
        : sync_block("langstone_mic_source", io_signature::make(0, 0, 0), io_signature::make(1, 1, sizeof(float))),
          _ring(std::move(ring)), _stopping(std::move(stopping)), _stats(std::move(stats)) {}

    std::shared_ptr<FloatRing> _ring;
    std::shared_ptr<std::atomic_bool> _stopping;
    std::shared_ptr<AudioStats> _stats;
};

/// gr-audioのaudio.sinkの代わり。受信音声をリングバッファへ書き、AAudioの出力が取り出す。
class AudioRingSink : public sync_block {
public:
    static basic_block_sptr make(std::shared_ptr<FloatRing> ring, std::shared_ptr<AudioStats> stats) {
        return basic_block_sptr(new AudioRingSink(std::move(ring), std::move(stats)));
    }

    int work(int noutput_items, gr_vector_const_void_star &input_items, gr_vector_void_star &) override {
        size_t written = _ring->write(static_cast<const float *>(input_items[0]), (size_t)noutput_items);
        _stats->rxIn += (uint64_t)noutput_items;
        _stats->rxOverflow += (uint64_t)noutput_items - written;
        return noutput_items;
    }

private:
    AudioRingSink(std::shared_ptr<FloatRing> ring, std::shared_ptr<AudioStats> stats)
        : sync_block("langstone_audio_sink", io_signature::make(1, 1, sizeof(float)), io_signature::make(0, 0, 0)),
          _ring(std::move(ring)), _stats(std::move(stats)) {}

    std::shared_ptr<FloatRing> _ring;
    std::shared_ptr<AudioStats> _stats;
};

/// 最新のFFT1フレームを保持する(GNU Radioのスレッドが書き、Kotlinが取りに来る)。
struct FFTSlot {
    std::mutex lock;
    std::vector<float> frame = std::vector<float>(kFFTSize);
    bool fresh = false;

    void put(const float *bins, int n) {
        std::lock_guard<std::mutex> guard(lock);
        std::copy(bins, bins + std::min(n, kFFTSize), frame.begin());
        fresh = true;
    }

    bool take(float *out) {
        std::lock_guard<std::mutex> guard(lock);
        if (!fresh) { return false; }
        std::copy(frame.begin(), frame.end(), out);
        fresh = false;
        return true;
    }

    void clear() {
        std::lock_guard<std::mutex> guard(lock);
        fresh = false;
    }
};

/// logpwrfft.logpwrfft_c(fft_size=512, ref_scale=2, frame_rate=15, avg_alpha=0.9,
/// average=True, shift=False)と同じ計算をするシンク。Pi5版ではこの後
/// vector_to_stream→UDPでGUIへ送っていた。
class LogPowerFFTSink : public sync_block {
public:
    static std::shared_ptr<LogPowerFFTSink> make(std::shared_ptr<FFTSlot> slot) {
        return std::shared_ptr<LogPowerFFTSink>(new LogPowerFFTSink(std::move(slot)));
    }

    /// logpwrfftのset_sample_rate()相当(stream_to_vector_decimatorの間引き数を変える)。
    void setSampleRate(double rate) {
        int decim = (int)std::lround(rate / kFFTSize / 15.0);
        if (decim < 1) { decim = 1; }
        _stride.store(decim * kFFTSize);
    }

    int work(int noutput_items, gr_vector_const_void_star &input_items, gr_vector_void_star &) override {
        const gr_complex *in = static_cast<const gr_complex *>(input_items[0]);
        const int stride = _stride.load();
        for (int i = 0; i < noutput_items; ++i) {
            if (_pos < kFFTSize) { _frame[_pos] = in[i]; }
            ++_pos;
            if (_pos >= stride) {
                _pos = 0;
                emitFrame();
            }
        }
        return noutput_items;
    }

private:
    explicit LogPowerFFTSink(std::shared_ptr<FFTSlot> slot)
        : sync_block("langstone_logpwrfft", io_signature::make(1, 1, sizeof(gr_complex)), io_signature::make(0, 0, 0)),
          _slot(std::move(slot)), _fft(kFFTSize), _frame(kFFTSize), _avg(kFFTSize, 0.0f), _db(kFFTSize) {
        _window = fft::window::blackman_harris(kFFTSize);
        double windowPower = 0;
        for (float w : _window) { windowPower += (double)w * w; }
        // logpwrfftのnlog10_ffのオフセット(ref_scale=2なので最後の項は0)。
        _offset = (float)(-20.0 * std::log10((double)kFFTSize)
                          - 10.0 * std::log10(windowPower / kFFTSize)
                          - 20.0 * std::log10(2.0 / 2.0));
        setSampleRate(kAudioRate / 2.0);
    }

    void emitFrame() {
        gr_complex *fin = _fft.get_inbuf();
        for (int i = 0; i < kFFTSize; ++i) { fin[i] = _frame[i] * _window[i]; }
        _fft.execute();
        const gr_complex *fout = _fft.get_outbuf();
        const float alpha = 0.9f;
        for (int i = 0; i < kFFTSize; ++i) {
            float magSq = std::norm(fout[i]);
            _avg[i] = alpha * magSq + (1.0f - alpha) * _avg[i];
            _db[i] = 10.0f * std::log10(_avg[i] > 1e-20f ? _avg[i] : 1e-20f) + _offset;
        }
        _slot->put(_db.data(), kFFTSize);
    }

    std::shared_ptr<FFTSlot> _slot;
    fft::fft_complex_fwd _fft;
    std::vector<float> _window;
    std::vector<gr_complex> _frame;
    std::vector<float> _avg;
    std::vector<float> _db;
    float _offset = 0;
    int _pos = 0;
    std::atomic<int> _stride{kFFTSize};
};

/// gnuradio.analog.fm_deemph(fs, tau)の係数(Python版のhier blockをC++で組み直す)。
void FMDeemphTaps(double fs, double tau, std::vector<double> &b, std::vector<double> &a) {
    const double wc = 1.0 / tau;
    const double wca = 2.0 * fs * std::tan(wc / (2.0 * fs));
    const double k = -wca / (2.0 * fs);
    const double z1 = -1.0;
    const double p1 = (1.0 + k) / (1.0 - k);
    const double b0 = -k / (1.0 - k);
    b = {b0 * 1.0, b0 * -z1};
    a = {1.0, -p1};
}

/// gnuradio.analog.fm_preemph(fs, tau, fh=-1)の係数。
void FMPreemphTaps(double fs, double tau, std::vector<double> &b, std::vector<double> &a) {
    const double fh = 0.925 * fs / 2.0;
    const double wcl = 1.0 / tau;
    const double wch = 2.0 * M_PI * fh;
    const double wcla = 2.0 * fs * std::tan(wcl / (2.0 * fs));
    const double wcha = 2.0 * fs * std::tan(wch / (2.0 * fs));
    const double kl = -wcla / (2.0 * fs);
    const double kh = -wcha / (2.0 * fs);
    const double z1 = (1.0 + kl) / (1.0 - kl);
    const double p1 = (1.0 + kh) / (1.0 - kh);
    const double b0 = (1.0 - kl) / (1.0 - kh);
    const double g = std::fabs(1.0 - p1) / (b0 * std::fabs(1.0 - z1));
    b = {g * b0 * 1.0, g * b0 * -z1};
    a = {1.0, -p1};
}

/// Pi5版のUSBオーディオ(gr-audio)の代わり。マイク音声をGNU Radio側へ渡し、受信音声を
/// GNU Radio側から取り出してスピーカーへ出す(iPad版のLangstoneAudio.swift相当)。
class LangstoneAudio {
public:
    LangstoneAudio(std::shared_ptr<FloatRing> micRing, std::shared_ptr<FloatRing> rxRing,
                   std::shared_ptr<AudioStats> stats)
        : _micRing(std::move(micRing)), _rxRing(std::move(rxRing)), _stats(std::move(stats)) {}

    ~LangstoneAudio() { stop(); }

    /// 出力を開始し、withInputならマイク入力も開始する。失敗の説明(無ければ空)を返す。
    std::string start(bool withInput) {
        stop();
        std::string error;
        _rxPrimed = false;
        if (!openStream(AAUDIO_DIRECTION_OUTPUT, &_out)) {
            error = "音声出力を開始できません";
        }
        if (withInput && !openStream(AAUDIO_DIRECTION_INPUT, &_in)) {
            if (error.empty()) { error = "マイク入力を開始できません(送信音声は無音になります)"; }
        }
        return error;
    }

    void stop() {
        for (AAudioStream **s : {&_in, &_out}) {
            if (*s) {
                AAudioStream_requestStop(*s);
                AAudioStream_close(*s);
                *s = nullptr;
            }
        }
    }

private:
    bool openStream(aaudio_direction_t direction, AAudioStream **stream) {
        AAudioStreamBuilder *builder = nullptr;
        if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) { return false; }
        AAudioStreamBuilder_setDirection(builder, direction);
        AAudioStreamBuilder_setSampleRate(builder, (int32_t)kAudioRate);
        AAudioStreamBuilder_setChannelCount(builder, 1);
        AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
        AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
        AAudioStreamBuilder_setDataCallback(builder,
            direction == AAUDIO_DIRECTION_OUTPUT ? &LangstoneAudio::outputCallback : &LangstoneAudio::inputCallback,
            this);
        aaudio_result_t r = AAudioStreamBuilder_openStream(builder, stream);
        AAudioStreamBuilder_delete(builder);
        if (r != AAUDIO_OK) {
            LOGE("AAudio open failed dir=%d: %s", (int)direction, AAudio_convertResultToText(r));
            *stream = nullptr;
            return false;
        }
        // AAudioが48kHzを受け付けなかった場合(リサンプラが入らない機種)は使えない。
        if (AAudioStream_getSampleRate(*stream) != (int32_t)kAudioRate) {
            LOGE("AAudio rate mismatch dir=%d rate=%d", (int)direction, AAudioStream_getSampleRate(*stream));
            AAudioStream_close(*stream);
            *stream = nullptr;
            return false;
        }
        r = AAudioStream_requestStart(*stream);
        if (r != AAUDIO_OK) {
            LOGE("AAudio start failed dir=%d: %s", (int)direction, AAudio_convertResultToText(r));
            AAudioStream_close(*stream);
            *stream = nullptr;
            return false;
        }
        return true;
    }

    static aaudio_data_callback_result_t outputCallback(AAudioStream *, void *user, void *audioData, int32_t numFrames) {
        static_cast<LangstoneAudio *>(user)->pull(static_cast<float *>(audioData), (size_t)numFrames);
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    static aaudio_data_callback_result_t inputCallback(AAudioStream *, void *user, void *audioData, int32_t numFrames) {
        auto *self = static_cast<LangstoneAudio *>(user);
        self->_micRing->write(static_cast<const float *>(audioData), (size_t)numFrames);
        self->_stats->micIn += (uint64_t)numFrames;
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    /// iPad版のpullAudioSamplesと同じ。出力とPlutoの受信は別のクロックで動き、受信音声は
    /// iioバッファ1回分(約62ms)ずつまとめて届く。150ms分溜めてから再生し、500ms以上
    /// 溜まったら150msまで捨てる。途切れたら再び150ms溜まるまで無音にする。
    void pull(float *samples, size_t count) {
        auto &ring = *_rxRing;
        if (ring.available() > 24000) { _stats->rxSkipped += ring.skipTo(7200); }
        if (!_rxPrimed && ring.available() >= 7200) { _rxPrimed = true; }
        size_t got = _rxPrimed ? ring.read(samples, count) : 0;
        if (got < count) {
            std::fill(samples + got, samples + count, 0.0f);
            if (_rxPrimed) {
                _rxPrimed = false;
                _stats->rxUnderruns++;
            }
        }
    }

    std::shared_ptr<FloatRing> _micRing;
    std::shared_ptr<FloatRing> _rxRing;
    std::shared_ptr<AudioStats> _stats;
    AAudioStream *_out = nullptr;
    AAudioStream *_in = nullptr;
    bool _rxPrimed = false;
};

/// Lang_TRX_Pluto.pyのフローグラフとsetter(FIFOコマンド)。iPad版LangstoneTRxSessionと同じ。
class LangstoneSession {
public:
    LangstoneSession()
        : _micRing(std::make_shared<FloatRing>(48000)), _rxRing(std::make_shared<FloatRing>(48000)),
          _stopping(std::make_shared<std::atomic_bool>(false)), _stats(std::make_shared<AudioStats>()),
          _rxSlot(std::make_shared<FFTSlot>()), _txSlot(std::make_shared<FFTSlot>()),
          _audio(_micRing, _rxRing, _stats) {}

    ~LangstoneSession() {
        _audio.stop();
        stop();
    }

    /// Plutoへ接続してフローグラフを開始する。失敗の説明(成功なら空)を返す。呼び出し元を待たせる。
    std::string start(const std::string &host, double rxHz, double txHz) {
        std::lock_guard<std::mutex> startGuard(_startLock);
        stop();
        _stopping->store(false);
        const std::string uri = "ip:" + host;
        LOGI("trx start requested uri=%s rx=%.0f tx=%.0f", uri.c_str(), rxHz, txHz);
        bool connected = false;
        std::string error;
        try {
            buildGraph(uri, rxHz, txHz, &connected);
            _tb->start();
            _running.store(true);
            LOGI("tb->start() returned");
        } catch (const std::exception &e) {
            error = connected ? std::string("GNU Radioの送受信処理を開始できません: ") + e.what()
                              : "Plutoへ接続できません(" + uri + "): " + e.what();
        } catch (...) {
            error = connected ? std::string("GNU Radioの送受信処理を開始できません")
                              : "Plutoへ接続できません(" + uri + ")";
        }
        if (!error.empty()) {
            LOGE("trx start failed: %s", error.c_str());
            std::lock_guard<std::mutex> guard(_lock);
            _tb.reset();
        }
        return error;
    }

    void stop() {
        top_block_sptr tb;
        {
            std::lock_guard<std::mutex> guard(_lock);
            tb = _tb;
        }
        if (!tb) { return; }
        LOGI("trx stop");
        _stopping->store(true);
        tb->stop();
        tb->wait();
        LOGI("trx stopped");
        std::lock_guard<std::mutex> guard(_lock);
        _tb.reset();
        _xlate.reset(); _rxBandPass.reset(); _txBandPass.reset(); _txMute.reset();
        _mulFMTx.reset(); _mulSSBTx.reset(); _mulAMOut.reset(); _mulAMDet.reset();
        _mulFMRx.reset(); _mulSSBRx.reset(); _mulAF.reset(); _mulFMMic.reset(); _mulMic.reset();
        _addCarrier.reset(); _keepRx.reset(); _keepTx.reset();
        _ctcssSource.reset(); _burstSource.reset(); _rxFFT.reset(); _txFFT.reset();
        _running.store(false);
    }

    bool isRunning() const { return _running.load(); }

    std::string startAudio(bool withInput) { return _audio.start(withInput); }
    void stopAudio() { _audio.stop(); }

    bool takeFFT(bool tx, float *out) { return (tx ? _txSlot : _rxSlot)->take(out); }
    void clearFFT() { _rxSlot->clear(); _txSlot->clear(); }

    std::string takeAudioStats() {
        auto &st = *_stats;
        char buf[256];
        std::snprintf(buf, sizeof(buf),
                      "audio rxIn=%llu rxOverflow=%llu rxSkipped=%llu rxUnderruns=%llu rxLevel=%zu micIn=%llu micZeroFill=%llu",
                      (unsigned long long)st.rxIn.exchange(0), (unsigned long long)st.rxOverflow.exchange(0),
                      (unsigned long long)st.rxSkipped.exchange(0), (unsigned long long)st.rxUnderruns.exchange(0),
                      _rxRing->available(), (unsigned long long)st.micIn.exchange(0),
                      (unsigned long long)st.micZeroFill.exchange(0));
        return buf;
    }

    // --- Lang_TRX_Pluto.pyのsetter(FIFOコマンド)に対応 ---
    void setRxMute(bool mute) { std::lock_guard<std::mutex> g(_lock); _rxMute = mute; applyAFLocked(); }
    void setRxOffset(double hz) { std::lock_guard<std::mutex> g(_lock); _rxOffset = hz; if (_xlate) { _xlate->set_center_freq(hz); } }
    void setAFGain(int gain) { std::lock_guard<std::mutex> g(_lock); _afGain = gain; applyAFLocked(); }
    void setRxFilter(int low, int high) {
        std::lock_guard<std::mutex> g(_lock);
        if (low == _rxFiltLow && high == _rxFiltHigh) { return; }
        _rxFiltLow = low; _rxFiltHigh = high;
        if (_rxBandPass) { _rxBandPass->set_taps(rxBandPassTaps()); }
    }
    void setTxFilter(int low, int high) {
        std::lock_guard<std::mutex> g(_lock);
        if (low == _txFiltLow && high == _txFiltHigh) { return; }
        _txFiltLow = low; _txFiltHigh = high;
        if (_txBandPass) { _txBandPass->set_taps(txBandPassTaps()); }
    }
    void setMode(int mode) { std::lock_guard<std::mutex> g(_lock); _rxMode = mode; _txMode = mode; applyRxModeLocked(); applyTxModeLocked(); }
    void setPTT(bool ptt) { std::lock_guard<std::mutex> g(_lock); _ptt = ptt; applyMuteLocked(); }
    void setKey(bool key) { std::lock_guard<std::mutex> g(_lock); _key = key; applyMuteLocked(); }
    void setToneBurst(bool on) { std::lock_guard<std::mutex> g(_lock); _toneBurst = on; if (_burstSource) { _burstSource->set_amplitude(on ? 1.0 : 0.0); } }
    void setMicGain(int gain) { std::lock_guard<std::mutex> g(_lock); _micGain = gain; applyTxModeLocked(); }
    void setFMMic(int gain) { std::lock_guard<std::mutex> g(_lock); _fmMic = gain; if (_mulFMMic) { _mulFMMic->set_k((float)(gain / 5.0)); } }
    void setAMMic(int gain) { std::lock_guard<std::mutex> g(_lock); _amMic = gain; applyTxModeLocked(); }
    void setCTCSS(int tenthsHz) {
        std::lock_guard<std::mutex> g(_lock);
        _ctcss = tenthsHz;
        if (_ctcssSource) {
            _ctcssSource->set_frequency(tenthsHz / 10.0);
            _ctcssSource->set_amplitude(tenthsHz > 0 ? 0.15 : 0.0);
        }
    }
    void setFFTSel(int sel) { std::lock_guard<std::mutex> g(_lock); _fftSel = sel; applyFFTSelLocked(); }

private:
    void buildGraph(const std::string &uri, double rxHz, double txHz, bool *connected) {
        std::lock_guard<std::mutex> guard(_lock);
        auto tb = make_top_block("langstone_trx");

        // --- Pluto ---
        auto src = iio::fmcomms2_source_fc32::make(uri, {true, true, false, false}, kRxIioBufferSize);
        src->set_len_tag_key("");
        src->set_frequency(rxHz);
        src->set_samplerate(kPlutoRate);
        src->set_gain_mode(0, "slow_attack");
        src->set_gain(0, 64);
        src->set_quadrature(true);
        src->set_rfdc(true);
        src->set_bbdc(true);
        src->set_filter_params("Auto", "", 0, 0);
        // iPad版と同じ理由(iioバッファ1回分を必ず出力バッファへ収める)。
        src->set_output_multiple((int)kRxIioBufferSize);

        auto sink = iio::fmcomms2_sink_fc32::make(uri, {true, true, false, false}, kTxIioBufferSize, false);
        sink->set_len_tag_key("");
        sink->set_bandwidth(2000000);
        sink->set_frequency(txHz);
        sink->set_samplerate(kPlutoRate);
        sink->set_attenuation(0, 0);
        sink->set_filter_params("Auto", "", 0, 0);

        *connected = true;

        // --- 受信 ---
        _xlate = filter::freq_xlating_fir_filter_ccf::make(
            11, filter::firdes::low_pass(1, 529200, 23000, 2000), _rxOffset, (double)kPlutoRate);
        _rxBandPass = filter::fir_filter_ccc::make(1, rxBandPassTaps());
        auto nbfmDemod = analog::quadrature_demod_cf::make((float)(kAudioRate / (2.0 * M_PI * 5e3)));
        std::vector<double> deB, deA;
        FMDeemphTaps(kAudioRate, 75e-6, deB, deA);
        auto deemph = filter::iir_filter_ffd::make(deB, deA, false);
        // nbfm_rx内の音声フィルタ(firdes.low_pass(1, quad_rate, 2.7e3, 0.5e3, WIN_HAMMING))。
        auto nbfmAudio = filter::fir_filter_fff::make(1, filter::firdes::low_pass(1, kAudioRate, 2700, 500));
        _mulFMRx = blocks::multiply_const_ff::make(0);
        auto c2mag = blocks::complex_to_mag::make(1);
        _mulAMDet = blocks::multiply_const_ff::make(0);
        auto c2realRx = blocks::complex_to_real::make(1);
        _mulSSBRx = blocks::multiply_const_ff::make(0);
        auto addDet = blocks::add_ff::make(1);              // blocks_add_xx_1_0
        auto f2cRx = blocks::float_to_complex::make(1);     // blocks_float_to_complex_0
        auto agc = analog::agc3_cc::make(1e-2f, 5e-7f, 0.1f, 1.0f, 1, 1000.0f);
        auto c2realAgc = blocks::complex_to_real::make(1);
        _mulAMOut = blocks::multiply_const_ff::make(1);
        auto addAudio = blocks::add_ff::make(1);            // blocks_add_xx_1
        _mulAF = blocks::multiply_const_ff::make(1);
        auto audioLPF = filter::fir_filter_fff::make(1, filter::firdes::low_pass(1, kAudioRate, 3000, 1000));
        auto audioSink = AudioRingSink::make(_rxRing, _stats);
        _keepRx = blocks::keep_one_in_n::make(sizeof(gr_complex), 1 << _fftSel);
        _rxFFT = LogPowerFFTSink::make(_rxSlot);

        // --- 送信 ---
        auto mic = MicSource::make(_micRing, _stopping, _stats);
        _mulMic = blocks::multiply_const_ff::make(1);
        _mulFMMic = blocks::multiply_const_ff::make(1);
        _addCarrier = blocks::add_const_ff::make(0);
        auto railSSB = analog::rail_ff::make(-0.99f, 0.99f);   // analog_rail_ff_0_0
        auto zero = analog::sig_source_f::make(0, analog::GR_CONST_WAVE, 0, 0, 0);
        auto f2cTx = blocks::float_to_complex::make(1);     // blocks_float_to_complex_0_0
        // analog_sig_source_x_0(周波数0・振幅1の複素正弦波)との乗算は1倍なので省く。
        _txBandPass = filter::fir_filter_ccc::make(1, txBandPassTaps());
        _mulSSBTx = blocks::multiply_const_cc::make(gr_complex(1, 0));
        _burstSource = analog::sig_source_f::make(kAudioRate, analog::GR_COS_WAVE, 1750, 0, 0, 0);
        auto addBurst = blocks::add_ff::make(1);            // blocks_add_xx_0
        auto railFM = analog::rail_ff::make(-1, 1);         // analog_rail_ff_0
        auto fmBandPass = filter::fir_filter_fff::make(
            1, filter::firdes::band_pass(1, kAudioRate, 300, 3500, 100, fft::window::win_type::WIN_HAMMING, 6.76));
        _ctcssSource = analog::sig_source_f::make(kAudioRate, analog::GR_SIN_WAVE, _ctcss / 10.0, 0, 0, 0);
        auto addCTCSS = blocks::add_ff::make(1);            // blocks_add_xx_0_0
        std::vector<double> preB, preA;
        FMPreemphTaps(kAudioRate, 75e-6, preB, preA);
        auto preemph = filter::iir_filter_ffd::make(preB, preA, false);
        auto fmMod = analog::frequency_modulator_fc::make((float)(2.0 * M_PI * 5000.0 / kAudioRate));
        _mulFMTx = blocks::multiply_const_cc::make(gr_complex(0, 0));
        auto addTx = blocks::add_cc::make(1);               // blocks_add_xx_2
        _txMute = blocks::mute_cc::make(true);
        auto resampler = filter::rational_resampler_ccc::make(11, 1);
        _keepTx = blocks::keep_one_in_n::make(sizeof(gr_complex), 1 << _fftSel);
        _txFFT = LogPowerFFTSink::make(_txSlot);

        // 受信
        tb->connect(src, 0, _xlate, 0);
        tb->connect(_xlate, 0, _rxBandPass, 0);
        tb->connect(_xlate, 0, _keepRx, 0);
        tb->connect(_keepRx, 0, _rxFFT, 0);
        tb->connect(_rxBandPass, 0, nbfmDemod, 0);
        tb->connect(nbfmDemod, 0, deemph, 0);
        tb->connect(deemph, 0, nbfmAudio, 0);
        tb->connect(nbfmAudio, 0, _mulFMRx, 0);
        tb->connect(_rxBandPass, 0, c2mag, 0);
        tb->connect(c2mag, 0, _mulAMDet, 0);
        tb->connect(_rxBandPass, 0, c2realRx, 0);
        tb->connect(c2realRx, 0, _mulSSBRx, 0);
        tb->connect(_mulSSBRx, 0, addDet, 0);
        tb->connect(_mulAMDet, 0, addDet, 1);
        tb->connect(addDet, 0, f2cRx, 0);
        tb->connect(f2cRx, 0, agc, 0);
        tb->connect(agc, 0, c2realAgc, 0);
        tb->connect(c2realAgc, 0, _mulAMOut, 0);
        tb->connect(_mulAMOut, 0, addAudio, 0);
        tb->connect(_mulFMRx, 0, addAudio, 1);
        tb->connect(addAudio, 0, _mulAF, 0);
        tb->connect(_mulAF, 0, audioLPF, 0);
        tb->connect(audioLPF, 0, audioSink, 0);

        // 送信
        tb->connect(mic, 0, _mulMic, 0);
        tb->connect(mic, 0, _mulFMMic, 0);
        tb->connect(_mulMic, 0, _addCarrier, 0);
        tb->connect(_addCarrier, 0, railSSB, 0);
        tb->connect(railSSB, 0, f2cTx, 0);
        tb->connect(zero, 0, f2cTx, 1);
        tb->connect(f2cTx, 0, _txBandPass, 0);
        tb->connect(_txBandPass, 0, _mulSSBTx, 0);
        tb->connect(_burstSource, 0, addBurst, 0);
        tb->connect(_mulFMMic, 0, addBurst, 1);
        tb->connect(addBurst, 0, railFM, 0);
        tb->connect(railFM, 0, fmBandPass, 0);
        tb->connect(_ctcssSource, 0, addCTCSS, 0);
        tb->connect(fmBandPass, 0, addCTCSS, 1);
        tb->connect(addCTCSS, 0, preemph, 0);
        tb->connect(preemph, 0, fmMod, 0);
        tb->connect(fmMod, 0, _mulFMTx, 0);
        tb->connect(_mulFMTx, 0, addTx, 0);
        tb->connect(_mulSSBTx, 0, addTx, 1);
        tb->connect(addTx, 0, _txMute, 0);
        tb->connect(_txMute, 0, _keepTx, 0);
        tb->connect(_keepTx, 0, _txFFT, 0);
        tb->connect(_txMute, 0, resampler, 0);
        tb->connect(resampler, 0, sink, 0);

        _tb = tb;
        applyTxModeLocked();
        applyRxModeLocked();
        applyAFLocked();
        applyFFTSelLocked();
        _burstSource->set_amplitude(_toneBurst ? 1.0 : 0.0);
        _ctcssSource->set_frequency(_ctcss / 10.0);
        _ctcssSource->set_amplitude(_ctcss > 0 ? 0.15 : 0.0);
        _mulFMMic->set_k((float)(_fmMic / 5.0));
    }

    std::vector<gr_complex> rxBandPassTaps() const {
        return filter::firdes::complex_band_pass(1, kAudioRate, _rxFiltLow, _rxFiltHigh, 100,
                                                 fft::window::win_type::WIN_HAMMING, 6.76);
    }

    std::vector<gr_complex> txBandPassTaps() const {
        return filter::firdes::complex_band_pass(1, kAudioRate, _txFiltLow, _txFiltHigh, 100,
                                                 fft::window::win_type::WIN_HAMMING, 6.76);
    }

    // --- Lang_TRX_Pluto.pyのsetterの式 ---
    void applyTxModeLocked() {
        if (!_tb) { return; }
        const int m = _txMode;
        _addCarrier->set_k((float)((0.5 * (m == 5)) + (m == 2) + (m == 3)));
        _mulMic->set_k((float)(_micGain * (m == 0) + _micGain * (m == 1) + (_amMic / 10.0) * (m == 5)));
        _mulFMTx->set_k(gr_complex(m == 4 ? 1.0f : 0.0f, 0));
        _mulSSBTx->set_k(gr_complex(((m < 4) || (m == 5)) ? 1.0f : 0.0f, 0));
        applyMuteLocked();
    }

    void applyMuteLocked() {
        if (!_tb) { return; }
        const int m = _txMode;
        _txMute->set_mute((!_ptt) || (m == 2 && !_key) || (m == 3 && !_key));
    }

    void applyRxModeLocked() {
        if (!_tb) { return; }
        const int m = _rxMode;
        _mulSSBRx->set_k(m < 4 ? 1.0f : 0.0f);
        _mulFMRx->set_k((m == 4) * 0.2f);
        _mulAMDet->set_k(m == 5 ? 1.0f : 0.0f);
        _mulAMOut->set_k(1.0f + (m == 5));
    }

    void applyAFLocked() {
        if (!_tb) { return; }
        _mulAF->set_k((float)((_afGain / 100.0) * (!_rxMute)));
    }

    void applyFFTSelLocked() {
        if (!_tb) { return; }
        const int n = 1 << _fftSel;
        _keepRx->set_n(n);
        _keepTx->set_n(n);
        _rxFFT->setSampleRate(kAudioRate / n);
        _txFFT->setSampleRate(kAudioRate / n);
    }

    std::mutex _startLock;
    std::mutex _lock;
    top_block_sptr _tb;
    std::shared_ptr<FloatRing> _micRing;
    std::shared_ptr<FloatRing> _rxRing;
    std::shared_ptr<std::atomic_bool> _stopping;
    std::shared_ptr<AudioStats> _stats;
    std::shared_ptr<FFTSlot> _rxSlot;
    std::shared_ptr<FFTSlot> _txSlot;
    LangstoneAudio _audio;
    std::atomic_bool _running{false};

    // Lang_TRX_Pluto.pyの変数(同じ初期値)
    int _txMode = 0;
    int _rxMode = 0;
    bool _ptt = false;
    bool _key = false;
    bool _toneBurst = false;
    bool _rxMute = false;
    double _micGain = 5.0;
    int _fmMic = 50;
    int _amMic = 5;
    int _afGain = 100;
    int _rxFiltLow = 300, _rxFiltHigh = 3000;
    int _txFiltLow = 300, _txFiltHigh = 3000;
    double _rxOffset = 0;
    int _ctcss = 885;
    int _fftSel = 1;

    // setterで値を変えるブロック
    filter::freq_xlating_fir_filter_ccf::sptr _xlate;
    filter::fir_filter_ccc::sptr _rxBandPass;
    filter::fir_filter_ccc::sptr _txBandPass;
    blocks::mute_cc::sptr _txMute;
    blocks::multiply_const_cc::sptr _mulFMTx;      // blocks_multiply_const_vxx_3
    blocks::multiply_const_cc::sptr _mulSSBTx;     // blocks_multiply_const_vxx_4
    blocks::multiply_const_ff::sptr _mulAMOut;     // blocks_multiply_const_vxx_2_1_0
    blocks::multiply_const_ff::sptr _mulAMDet;     // blocks_multiply_const_vxx_2_1
    blocks::multiply_const_ff::sptr _mulFMRx;      // blocks_multiply_const_vxx_2_0
    blocks::multiply_const_ff::sptr _mulSSBRx;     // blocks_multiply_const_vxx_2
    blocks::multiply_const_ff::sptr _mulAF;        // blocks_multiply_const_vxx_1
    blocks::multiply_const_ff::sptr _mulFMMic;     // blocks_multiply_const_vxx_0_0
    blocks::multiply_const_ff::sptr _mulMic;       // blocks_multiply_const_vxx_0
    blocks::add_const_ff::sptr _addCarrier;        // blocks_add_const_vxx_0_0
    blocks::keep_one_in_n::sptr _keepRx;
    blocks::keep_one_in_n::sptr _keepTx;
    analog::sig_source_f::sptr _ctcssSource;
    analog::sig_source_f::sptr _burstSource;
    std::shared_ptr<LogPowerFFTSink> _rxFFT;
    std::shared_ptr<LogPowerFFTSink> _txFFT;
};

/// Langstone V3のGUI(LangstoneGUI_Pluto.c)がGNU Radioを通さずlibiioで直接行っていたPluto制御
/// (受信/送信LO、送信減衰、受信ゲイン、LOのpowerdown、GPO)。iPad版LangstonePlutoControl.swift
/// と同じく、GNU Radio(gr-iio)とは別のiio_contextを使い、すべて専用スレッドで順番に行う
/// (libiioの呼び出しはネットワーク往復で待たされるため、画面の操作を止めない)。
class PlutoControl {
public:
    PlutoControl() : _worker([this] { run(); }) {}

    ~PlutoControl() {
        {
            std::lock_guard<std::mutex> g(_qlock);
            _quit = true;
        }
        _cv.notify_all();
        _worker.join();
        disconnectLocked();
    }

    /// LangstoneGUI_Pluto.cのinitPlutoに相当。終わるまで待つ。
    bool connect(const std::string &host) {
        bool ok = false;
        runAndWait([&] {
            disconnectLocked();
            LOGI("libiio connect %s", host.c_str());
            _ctx = iio_create_network_context(host.c_str());
            if (_ctx) { _phy = iio_context_find_device(_ctx, "ad9361-phy"); }
            if (!_ctx || !_phy) {
                LOGE("libiio connect failed");
                disconnectLocked();
                return;
            }
            iio_context_set_timeout(_ctx, 3000);
            ok = true;
        });
        return ok;
    }

    void disconnect() { post([this] { disconnectLocked(); }); }

    /// キューに積んだ書き込みがすべて終わるのを待つ(終了処理用)。
    void flush() { runAndWait([] {}); }

    void setRxFrequency(long long hz) { writeLongLong("altvoltage0", true, "frequency", hz); }
    void setTxFrequency(long long hz) { writeLongLong("altvoltage1", true, "frequency", hz); }
    void setTxAttenuation(int db) { writeDouble("voltage0", true, "hardwaregain", (double)db); }
    void setTxEnabled(bool on) { writeBool("altvoltage1", true, "powerdown", !on); }
    void setRxEnabled(bool on) { writeBool("altvoltage0", true, "powerdown", !on); }

    /// `gain`が`maxGain`を超えていれば自動(slow_attack)、そうでなければ手動でそのゲイン。
    void setRxGain(int gain, int maxGain) {
        if (gain > maxGain) {
            writeString("voltage0", false, "gain_control_mode", "slow_attack");
        } else {
            writeString("voltage0", false, "gain_control_mode", "manual");
            writeDouble("voltage0", false, "hardwaregain", (double)gain);
        }
    }

    /// 受信ゲインの読み戻し(Sメーターの補正用、LangstoneGUI_Pluto.cのreadPlutoRxGain)。
    void refreshRxGain() {
        post([this] {
            iio_channel *chn = findChannel("voltage0", false);
            double value = 0;
            if (chn && iio_channel_attr_read_double(chn, "hardwaregain", &value) == 0) {
                _lastRxGain.store((int)value);
            }
        });
    }

    int currentRxGain() const { return _lastRxGain.load(); }

    /// PlutoのGPO(LangstoneGUI_Pluto.cのsetPlutoGpo)。
    void setGpo(int value) {
        post([this, value] {
            if (!_phy) { return; }
            char buf[32];
            std::snprintf(buf, sizeof(buf), "0x27 0x%x0", value);
            iio_device_debug_attr_write(_phy, "direct_reg_access", buf);
        });
    }

private:
    void post(std::function<void()> task) {
        {
            std::lock_guard<std::mutex> g(_qlock);
            _queue.push_back(std::move(task));
        }
        _cv.notify_all();
    }

    void runAndWait(std::function<void()> task) {
        std::mutex m;
        std::condition_variable cv;
        bool done = false;
        post([&] {
            task();
            std::lock_guard<std::mutex> g(m);
            done = true;
            cv.notify_all();
        });
        std::unique_lock<std::mutex> lk(m);
        cv.wait(lk, [&] { return done; });
    }

    void run() {
        for (;;) {
            std::function<void()> task;
            {
                std::unique_lock<std::mutex> lk(_qlock);
                _cv.wait(lk, [this] { return _quit || !_queue.empty(); });
                if (_queue.empty()) { return; }
                task = std::move(_queue.front());
                _queue.pop_front();
            }
            task();
        }
    }

    void disconnectLocked() {
        if (_ctx) { iio_context_destroy(_ctx); }
        _ctx = nullptr;
        _phy = nullptr;
    }

    iio_channel *findChannel(const char *name, bool output) {
        return _phy ? iio_device_find_channel(_phy, name, output) : nullptr;
    }

    void writeLongLong(std::string ch, bool out, std::string attr, long long v) {
        post([=] { if (auto *c = findChannel(ch.c_str(), out)) { iio_channel_attr_write_longlong(c, attr.c_str(), v); } });
    }
    void writeDouble(std::string ch, bool out, std::string attr, double v) {
        post([=] { if (auto *c = findChannel(ch.c_str(), out)) { iio_channel_attr_write_double(c, attr.c_str(), v); } });
    }
    void writeBool(std::string ch, bool out, std::string attr, bool v) {
        post([=] { if (auto *c = findChannel(ch.c_str(), out)) { iio_channel_attr_write_bool(c, attr.c_str(), v); } });
    }
    void writeString(std::string ch, bool out, std::string attr, std::string v) {
        post([=] { if (auto *c = findChannel(ch.c_str(), out)) { iio_channel_attr_write(c, attr.c_str(), v.c_str()); } });
    }

    iio_context *_ctx = nullptr;
    iio_device *_phy = nullptr;
    std::atomic<int> _lastRxGain{73}; // 未接続時はPi5版と同じ73
    std::mutex _qlock;
    std::condition_variable _cv;
    std::deque<std::function<void()>> _queue;
    bool _quit = false;
    std::thread _worker;
};

struct Native {
    LangstoneSession session;
    PlutoControl pluto;
};

Native *N(jlong h) { return reinterpret_cast<Native *>(h); }

std::string str(JNIEnv *env, jstring s) {
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) { env->ReleaseStringUTFChars(s, c); }
    return out;
}

jstring jstr(JNIEnv *env, const std::string &s) { return env->NewStringUTF(s.c_str()); }

} // namespace

#define LS_FN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_com_shinjo_shonanandroid_langstone_LangstoneNative_##name

LS_FN(jlong, nativeCreate)(JNIEnv *, jobject) { return reinterpret_cast<jlong>(new Native()); }
LS_FN(void, nativeDestroy)(JNIEnv *, jobject, jlong h) { delete N(h); }

LS_FN(jstring, nativeStart)(JNIEnv *env, jobject, jlong h, jstring host, jdouble rx, jdouble tx) {
    return jstr(env, N(h)->session.start(str(env, host), rx, tx));
}
LS_FN(void, nativeStop)(JNIEnv *, jobject, jlong h) { N(h)->session.stop(); }
LS_FN(jboolean, nativeIsRunning)(JNIEnv *, jobject, jlong h) { return N(h)->session.isRunning(); }
LS_FN(jstring, nativeStartAudio)(JNIEnv *env, jobject, jlong h, jboolean withInput) {
    return jstr(env, N(h)->session.startAudio(withInput));
}
LS_FN(void, nativeStopAudio)(JNIEnv *, jobject, jlong h) { N(h)->session.stopAudio(); }
LS_FN(jboolean, nativeTakeFFT)(JNIEnv *env, jobject, jlong h, jboolean tx, jfloatArray out) {
    float buf[kFFTSize];
    if (!N(h)->session.takeFFT(tx, buf)) { return JNI_FALSE; }
    env->SetFloatArrayRegion(out, 0, kFFTSize, buf);
    return JNI_TRUE;
}
LS_FN(void, nativeClearFFT)(JNIEnv *, jobject, jlong h) { N(h)->session.clearFFT(); }
LS_FN(jstring, nativeTakeAudioStats)(JNIEnv *env, jobject, jlong h) { return jstr(env, N(h)->session.takeAudioStats()); }

LS_FN(void, nativeSetRxMute)(JNIEnv *, jobject, jlong h, jboolean v) { N(h)->session.setRxMute(v); }
LS_FN(void, nativeSetRxOffset)(JNIEnv *, jobject, jlong h, jdouble v) { N(h)->session.setRxOffset(v); }
LS_FN(void, nativeSetAFGain)(JNIEnv *, jobject, jlong h, jint v) { N(h)->session.setAFGain(v); }
LS_FN(void, nativeSetRxFilter)(JNIEnv *, jobject, jlong h, jint lo, jint hi) { N(h)->session.setRxFilter(lo, hi); }
LS_FN(void, nativeSetTxFilter)(JNIEnv *, jobject, jlong h, jint lo, jint hi) { N(h)->session.setTxFilter(lo, hi); }
LS_FN(void, nativeSetMode)(JNIEnv *, jobject, jlong h, jint v) { N(h)->session.setMode(v); }
LS_FN(void, nativeSetPTT)(JNIEnv *, jobject, jlong h, jboolean v) { N(h)->session.setPTT(v); }
LS_FN(void, nativeSetKey)(JNIEnv *, jobject, jlong h, jboolean v) { N(h)->session.setKey(v); }
LS_FN(void, nativeSetToneBurst)(JNIEnv *, jobject, jlong h, jboolean v) { N(h)->session.setToneBurst(v); }
LS_FN(void, nativeSetMicGain)(JNIEnv *, jobject, jlong h, jint v) { N(h)->session.setMicGain(v); }
LS_FN(void, nativeSetFMMic)(JNIEnv *, jobject, jlong h, jint v) { N(h)->session.setFMMic(v); }
LS_FN(void, nativeSetAMMic)(JNIEnv *, jobject, jlong h, jint v) { N(h)->session.setAMMic(v); }
LS_FN(void, nativeSetCTCSS)(JNIEnv *, jobject, jlong h, jint v) { N(h)->session.setCTCSS(v); }
LS_FN(void, nativeSetFFTSel)(JNIEnv *, jobject, jlong h, jint v) { N(h)->session.setFFTSel(v); }

LS_FN(jboolean, nativePlutoConnect)(JNIEnv *env, jobject, jlong h, jstring host) { return N(h)->pluto.connect(str(env, host)); }
LS_FN(void, nativePlutoDisconnect)(JNIEnv *, jobject, jlong h) { N(h)->pluto.disconnect(); }
LS_FN(void, nativePlutoFlush)(JNIEnv *, jobject, jlong h) { N(h)->pluto.flush(); }
LS_FN(void, nativePlutoSetRxFrequency)(JNIEnv *, jobject, jlong h, jlong hz) { N(h)->pluto.setRxFrequency(hz); }
LS_FN(void, nativePlutoSetTxFrequency)(JNIEnv *, jobject, jlong h, jlong hz) { N(h)->pluto.setTxFrequency(hz); }
LS_FN(void, nativePlutoSetTxAttenuation)(JNIEnv *, jobject, jlong h, jint db) { N(h)->pluto.setTxAttenuation(db); }
LS_FN(void, nativePlutoSetRxGain)(JNIEnv *, jobject, jlong h, jint gain, jint maxGain) { N(h)->pluto.setRxGain(gain, maxGain); }
LS_FN(void, nativePlutoRefreshRxGain)(JNIEnv *, jobject, jlong h) { N(h)->pluto.refreshRxGain(); }
LS_FN(jint, nativePlutoCurrentRxGain)(JNIEnv *, jobject, jlong h) { return N(h)->pluto.currentRxGain(); }
LS_FN(void, nativePlutoSetTxEnabled)(JNIEnv *, jobject, jlong h, jboolean on) { N(h)->pluto.setTxEnabled(on); }
LS_FN(void, nativePlutoSetRxEnabled)(JNIEnv *, jobject, jlong h, jboolean on) { N(h)->pluto.setRxEnabled(on); }
LS_FN(void, nativePlutoSetGpo)(JNIEnv *, jobject, jlong h, jint v) { N(h)->pluto.setGpo(v); }
