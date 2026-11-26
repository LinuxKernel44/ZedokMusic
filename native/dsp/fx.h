// Effect building blocks used by the Zedok effect plugins and by the instruments' built-in effects.
// Every module works in place on stereo buffers; parameters are plain real-unit setters (called once per block).
#pragma once
#include "zdsp.h"

namespace zdsp {

// ============================================================================ LFO
struct Lfo {
    float ph = 0.f;
    void reset(float p = 0.f) { ph = p; }
    inline float tickSine(float inc) { ph += inc; ph -= std::floor(ph); return std::sin(ph * kTwoPi); }
    inline float tickTri(float inc) { ph += inc; ph -= std::floor(ph); return 4.f * std::fabs(ph - 0.5f) - 1.f; }
};

// ============================================================================ Pitch shifter (dual crossfading delay taps)
struct PitchShifter {
    DelayLine dl; float ph = 0.f; int win = 2048;
    void init(double sr) { win = (int)(0.06 * sr); dl.init(win * 2 + 16); ph = 0.f; }
    void clear() { dl.clear(); ph = 0.f; }
    // ratio > 1 shifts up. Returns the shifted sample for input x.
    inline float tick(float x, float ratio) {
        dl.push(x);
        const float inc = (1.f - ratio) / (float)win;   // delay grows when ratio < 1
        ph += inc; ph -= std::floor(ph);
        const float p2 = ph + 0.5f - std::floor(ph + 0.5f);
        const float d1 = 2.f + ph * win, d2 = 2.f + p2 * win;
        const float g1 = std::sin(kPi * ph), g2 = std::sin(kPi * p2);   // sin^2 + cos^2 = 1 crossfade (power complementary: use squares)
        return dl.read(d1) * g1 * g1 + dl.read(d2) * g2 * g2;
    }
};

// ============================================================================ Reverb (feedback delay network)
class Reverb {
public:
    enum Mode { Room, Plate, Hall, Shimmer };
    static constexpr int N = 8;
    void prepare(double sr) {
        sr_ = sr;
        for (int i = 0; i < N; i++) { line_[i].init((int)(0.35 * sr)); lp_[i] = 0.f; }
        pre_.init((int)(0.5 * sr));
        for (int i = 0; i < 4; i++) { apL_[i].init((int)(0.02 * sr)); apR_[i].init((int)(0.02 * sr)); }
        shL_.init(sr); shR_.init(sr);
        lowS_.setup(sr, 30.f); highS_.setup(sr, 30.f);
        sizeS_.setup(sr, 200.f); sizeS_.snap(1.f);
        reset();
    }
    void reset() {
        for (int i = 0; i < N; i++) { line_[i].clear(); lp_[i] = 0.f; }
        pre_.clear(); for (int i = 0; i < 4; i++) { apL_[i].clear(); apR_[i].clear(); }
        shL_.clear(); shR_.clear(); hpL_ = hpR_ = lpL_ = lpR_ = 0.f; shFb_[0] = shFb_[1] = 0.f; modPh_ = 0.f;
    }
    // size 0..1, decay seconds (RT60 at low freq), damping 0..1 (HF absorption), diffusion 0..1, modulation 0..1, preDelay ms,
    // lowCut/highCut Hz (on the wet signal), width 0..1, shimmerAmt 0..1 (Shimmer mode: octave-up level fed back), mix 0..1
    void set(Mode mode, float size, float decay, float damping, float diffusion, float modulation, float preDelayMs, float lowCut, float highCut, float width, float shimmer, float mix) {
        mode_ = mode; size_ = size; decay_ = std::max(decay, 0.1f); damp_ = damping; diff_ = diffusion; mod_ = modulation;
        pre_ms_ = preDelayMs; lowCut_ = lowCut; highCut_ = highCut; width_ = width; shim_ = mode == Shimmer ? shimmer : 0.f; mix_ = mix;
        sizeS_.set(0.35f + size * 1.65f);
    }
    void process(float* L, float* R, int n) {
        static const float baseMs[4][N] = {
            {19.1f, 23.3f, 27.7f, 31.9f, 37.3f, 41.3f, 47.9f, 53.1f},       // Room: short, tight
            {23.9f, 29.3f, 33.7f, 38.9f, 44.3f, 51.1f, 57.7f, 63.7f},       // Plate: dense
            {41.7f, 49.3f, 57.1f, 67.9f, 79.3f, 91.1f, 103.7f, 117.1f},     // Hall: long
            {44.3f, 53.9f, 63.1f, 74.3f, 86.9f, 99.7f, 113.3f, 127.1f}};    // Shimmer: long
        const int m = (int)mode_;
        const float modeScale = mode_ == Room ? 0.7f : mode_ == Plate ? 0.85f : 1.f;
        const float hfAbs = (mode_ == Plate ? 0.25f : 1.f) * damp_;
        const float dampA = clampf(0.05f + hfAbs * 0.9f, 0.f, 0.97f);          // line low-pass coefficient (higher = darker)
        const float apG = 0.35f + diff_ * 0.4f;
        const float lcA = std::exp(-kTwoPi * lowCut_ / (float)sr_), hcA = std::exp(-kTwoPi * highCut_ / (float)sr_);
        const float preS = std::min(pre_ms_ * 0.001f * (float)sr_, (float)(0.45 * sr_));
        const float modDepth = mod_ * (mode_ == Plate ? 5.f : 3.f);
        const float modInc = (0.35f + 0.6f * mod_) / (float)sr_;
        const float wetGain = mix_, dryGain = 1.f - 0.5f * mix_ * mix_ - 0.5f * mix_;   // approx equal power-ish blend
        const float dryG = std::sqrt(std::max(0.f, 1.f - mix_)), wetG = std::sqrt(mix_);
        (void)wetGain; (void)dryGain;
        for (int i = 0; i < n; i++) {
            const float sz = sizeS_.next();
            const float inL = L[i], inR = R[i];
            // pre-delay + input diffusion
            pre_.push(0.5f * (inL + inR));
            float x = preS < 1.f ? pre_.tap(0) : pre_.read(preS);
            float xl = x, xr = x;
            for (int a = 0; a < 4; a++) {   // two decorrelated allpass chains (different lengths) -> stereo diffusion
                const float dl = (5.1f + 2.7f * a) * 0.001f * (float)sr_ * (0.8f + 0.4f * sz), dr = (6.3f + 3.1f * a) * 0.001f * (float)sr_ * (0.8f + 0.4f * sz);
                const float bl = apL_[a].read(dl), br = apR_[a].read(dr);
                const float yl = -apG * xl + bl, yr = -apG * xr + br;
                apL_[a].push(xl + apG * yl); apR_[a].push(xr + apG * yr);
                xl = yl; xr = yr;
            }
            xl = lerp(x, xl, 0.4f + 0.6f * diff_); xr = lerp(x, xr, 0.4f + 0.6f * diff_);
            // shimmer: octave-up of the tail fed back into the network
            float sh = 0.f;
            if (shim_ > 0.f) sh = shim_ * 0.6f * (shFb_[0] + shFb_[1]);
            // FDN read
            float v[N], sum = 0.f;
            modPh_ += modInc; modPh_ -= std::floor(modPh_);
            for (int k = 0; k < N; k++) {
                const float lm = std::sin(kTwoPi * (modPh_ * (1.f + 0.13f * k) + 0.17f * k)) * modDepth * 0.001f * (float)sr_ * 0.15f;
                float d = baseMs[m][k] * modeScale * sz * 0.001f * (float)sr_ + lm;
                d = clampf(d, 4.f, (float)(0.34 * sr_));
                float y = line_[k].read(d);
                // per-line feedback gain from RT60: g = 10^(-3 d / T)
                const float g = std::pow(10.f, -3.f * (d / (float)sr_) / decay_);
                lp_[k] = y * (1.f - dampA) + lp_[k] * dampA; lp_[k] = denorm(lp_[k]);
                v[k] = lp_[k] * g; sum += v[k];
            }
            // Householder mixing and re-injection
            const float h = sum * (2.f / N);
            float outL = 0.f, outR = 0.f;
            for (int k = 0; k < N; k++) {
                const float inj = ((k & 1) ? xr : xl) * (k & 2 ? 1.f : -1.f) + sh * ((k & 4) ? 1.f : -1.f) * 0.5f;
                line_[k].push(v[k] - h + inj * 0.5f);
                const float w = (k & 1) ? 1.f : -1.f;
                if ((k & 1) == 0) outL += (k & 2 ? 1.f : -1.f) * v[k]; else outR += (k & 2 ? 1.f : -1.f) * v[k];
                (void)w;
            }
            outL *= 0.5f; outR *= 0.5f;
            // octave-up on the tail for next iteration (shimmer)
            if (shim_ > 0.f) { shFb_[0] = shL_.tick(outL, 2.f); shFb_[1] = shR_.tick(outR, 2.f); }
            // stereo width and wet filtering
            const float mid = 0.5f * (outL + outR), side = 0.5f * (outL - outR) * width_;
            float wl = mid + side, wr = mid - side;
            hpL_ = lcA * hpL_ + (1.f - lcA) * wl; wl -= hpL_; hpR_ = lcA * hpR_ + (1.f - lcA) * wr; wr -= hpR_;
            lpL_ = (1.f - hcA) * wl + hcA * lpL_; lpR_ = (1.f - hcA) * wr + hcA * lpR_;
            L[i] = inL * dryG + lpL_ * wetG * 1.6f;
            R[i] = inR * dryG + lpR_ * wetG * 1.6f;
        }
    }
private:
    struct AP : DelayLine {};
    double sr_ = 48000.0; Mode mode_ = Hall;
    float size_ = .5f, decay_ = 2.f, damp_ = .4f, diff_ = .7f, mod_ = .3f, pre_ms_ = 0.f, lowCut_ = 80.f, highCut_ = 9000.f, width_ = 1.f, shim_ = 0.f, mix_ = .3f;
    DelayLine line_[N], pre_; AP apL_[4], apR_[4]; float lp_[N] = {};
    PitchShifter shL_, shR_; float shFb_[2] = {};
    Smooth lowS_, highS_, sizeS_;
    float hpL_ = 0, hpR_ = 0, lpL_ = 0, lpR_ = 0, modPh_ = 0;
};

// ============================================================================ Stereo delay
class Delay {
public:
    void prepare(double sr) {
        sr_ = sr; L_.init((int)(4.2 * sr)); R_.init((int)(4.2 * sr));
        tL_.setup(sr, 120.f); tR_.setup(sr, 120.f); tL_.snap(0.3f * (float)sr); tR_.snap(0.3f * (float)sr);
        reset();
    }
    void reset() { L_.clear(); R_.clear(); hpL_ = hpR_ = lpL_ = lpR_ = 0.f; mph_ = 0.f; duck_ = 0.f; }
    // timeL/timeR in ms; feedback 0..1.05; cross 0..1 (0 = stereo, 1 = ping-pong); hp/lp in the feedback loop; drive (tape-ish saturation) 0..1;
    // modRate Hz, modDepth ms; width of the wet signal; duck 0..1 (wet is lowered while the input is loud); mix 0..1
    void set(float timeL, float timeR, float feedback, float cross, float hp, float lp, float drive, float modRate, float modDepth, float duck, float mix) {
        tL_.set(timeL * 0.001f * (float)sr_); tR_.set(timeR * 0.001f * (float)sr_);
        fb_ = feedback; cross_ = cross; drive_ = drive; modRate_ = modRate; modDepth_ = modDepth * 0.001f * (float)sr_; duck_a_ = duck; mix_ = mix;
        hpA_ = std::exp(-kTwoPi * hp / (float)sr_); lpA_ = std::exp(-kTwoPi * lp / (float)sr_);
    }
    void process(float* l, float* r, int n) {
        const float dryG = 1.f - 0.4f * mix_, wetG = mix_;
        const float envA = std::exp(-1.f / (0.005f * (float)sr_)), envR = std::exp(-1.f / (0.25f * (float)sr_));
        for (int i = 0; i < n; i++) {
            mph_ += modRate_ / (float)sr_; mph_ -= std::floor(mph_);
            const float m = std::sin(kTwoPi * mph_) * modDepth_;
            const float dl = clampf(tL_.next() + m, 2.f, (float)(4.1 * sr_)), dr = clampf(tR_.next() - m, 2.f, (float)(4.1 * sr_));
            const float yl = L_.read(dl), yr = R_.read(dr);
            // feedback path: cross-feed, filters, saturation
            float fl = lerp(yl, yr, cross_), fr = lerp(yr, yl, cross_);
            hpL_ = hpA_ * hpL_ + (1.f - hpA_) * fl; fl -= hpL_; hpR_ = hpA_ * hpR_ + (1.f - hpA_) * fr; fr -= hpR_;
            lpL_ = (1.f - lpA_) * fl + lpA_ * lpL_; lpR_ = (1.f - lpA_) * fr + lpA_ * lpR_; fl = lpL_; fr = lpR_;
            if (drive_ > 0.001f) { const float d = 1.f + drive_ * 4.f; fl = fastTanh(fl * d) / d * (1.f + drive_); fr = fastTanh(fr * d) / d * (1.f + drive_); }
            // ping-pong: the dry mono-ish input goes to the left line only when cross is high
            const float inL = l[i], inR = r[i];
            const float feedL = lerp(inL, 0.5f * (inL + inR), cross_ * 0.0f), feedR = inR;
            L_.push(feedL + fl * fb_); R_.push(feedR + fr * fb_);
            const float lvl = std::fabs(inL) + std::fabs(inR);
            env_ = lvl > env_ ? lvl + (env_ - lvl) * envA : lvl + (env_ - lvl) * envR;
            const float dg = 1.f - duck_a_ * clampf(env_ * 2.f, 0.f, 1.f) * 0.85f;
            l[i] = inL * dryG + yl * wetG * dg; r[i] = inR * dryG + yr * wetG * dg;
        }
    }
private:
    double sr_ = 48000.0; DelayLine L_, R_; Smooth tL_, tR_;
    float fb_ = .4f, cross_ = 0.f, drive_ = 0.f, modRate_ = .5f, modDepth_ = 0.f, duck_a_ = 0.f, mix_ = .3f, hpA_ = 0.f, lpA_ = 0.f;
    float hpL_ = 0, hpR_ = 0, lpL_ = 0, lpR_ = 0, mph_ = 0, duck_ = 0, env_ = 0;
};

// ============================================================================ Chorus / Flanger / Phaser
class Chorus {
public:
    void prepare(double sr) { sr_ = sr; for (int c = 0; c < 2; c++) dl_[c].init((int)(0.06 * sr)); reset(); }
    void reset() { dl_[0].clear(); dl_[1].clear(); for (int v = 0; v < 4; v++) ph_[v] = v * 0.25f; fb_[0] = fb_[1] = 0.f; }
    // rate Hz, depth 0..1, voices 1..4, delay ms (centre), feedback -0.9..0.9 (flanger use), spread 0..1, mix 0..1; flanger = shorter delay + feedback
    void set(float rate, float depth, int voices, float delayMs, float feedback, float spread, float mix, float tone) {
        rate_ = rate; depth_ = depth; voices_ = std::max(1, std::min(4, voices)); delay_ = delayMs * 0.001f * (float)sr_; fb_amt_ = feedback; spread_ = spread; mix_ = mix;
        toneA_ = std::exp(-kTwoPi * tone / (float)sr_);
    }
    void process(float* L, float* R, int n) {
        const float inc = rate_ / (float)sr_;
        const float dry = 1.f - 0.5f * mix_, wet = mix_;
        const float g = 1.f / std::sqrt((float)voices_);
        for (int i = 0; i < n; i++) {
            const float in[2] = {L[i], R[i]};
            float acc[2] = {0.f, 0.f};
            for (int v = 0; v < voices_; v++) {
                ph_[v] += inc * (1.f + 0.07f * v); ph_[v] -= std::floor(ph_[v]);
                const float lfo = std::sin(kTwoPi * ph_[v]);
                const float d = clampf(delay_ * (1.f + depth_ * 0.85f * lfo * (v & 1 ? -1.f : 1.f) * 0.5f) + depth_ * delay_ * 0.5f * 0.f, 2.f, (float)(0.055 * sr_));
                const float pan = voices_ == 1 ? 0.5f : (float)v / (voices_ - 1);      // spread voices across the stereo field
                const float pl = lerp(0.5f, 1.f - pan, spread_) , pr = lerp(0.5f, pan, spread_);
                for (int c = 0; c < 2; c++) {
                    const float y = dl_[c].read(d);
                    acc[c] += y * g * (c == 0 ? pl : pr) * 2.f;
                }
            }
            for (int c = 0; c < 2; c++) {
                float w = acc[c];
                lp_[c] = (1.f - toneA_) * w + toneA_ * lp_[c]; w = lp_[c];
                dl_[c].push(in[c] + fb_[c] * fb_amt_);
                fb_[c] = denorm(w);
            }
            L[i] = in[0] * dry + acc_lp(0) * wet; R[i] = in[1] * dry + acc_lp(1) * wet;
        }
    }
private:
    inline float acc_lp(int c) const { return fb_[c]; }
    double sr_ = 48000.0; DelayLine dl_[2]; float ph_[4] = {}, fb_[2] = {}, lp_[2] = {};
    float rate_ = .5f, depth_ = .5f, delay_ = 400.f, fb_amt_ = 0.f, spread_ = 1.f, mix_ = .5f, toneA_ = 0.f; int voices_ = 2;
};

class Phaser {
public:
    static constexpr int MAXST = 12;
    void prepare(double sr) { sr_ = sr; reset(); }
    void reset() { for (int c = 0; c < 2; c++) { for (int s = 0; s < MAXST; s++) z_[c][s] = 0.f; fb_[c] = 0.f; } ph_ = 0.f; }
    // stages 2..12 (even), rate Hz, depth 0..1, centre Hz, feedback -0.95..0.95, spread (stereo LFO offset) 0..1, mix
    void set(int stages, float rate, float depth, float centerHz, float feedback, float spread, float mix) {
        st_ = std::max(2, std::min(MAXST, stages)); rate_ = rate; depth_ = depth; centre_ = centerHz; fb_amt_ = feedback; spread_ = spread; mix_ = mix;
    }
    void process(float* L, float* R, int n) {
        float* ch[2] = {L, R};
        const float inc = rate_ / (float)sr_;
        for (int i = 0; i < n; i++) {
            ph_ += inc; ph_ -= std::floor(ph_);
            for (int c = 0; c < 2; c++) {
                const float p = ph_ + (c ? 0.25f * spread_ : 0.f);
                const float lfo = 0.5f - 0.5f * std::cos(kTwoPi * p);    // 0..1
                const float f = centre_ * std::pow(2.f, (lfo - 0.5f) * 2.f * 3.f * depth_);   // +-3 octaves at full depth
                const float t = std::tan(kPi * clampf(f, 20.f, (float)sr_ * 0.45f) / (float)sr_);
                const float a = (t - 1.f) / (t + 1.f);          // first-order allpass coefficient
                float x = ch[c][i];
                const float in = x;
                x += fb_[c] * fb_amt_;
                for (int s = 0; s < st_; s++) {
                    const float y = a * x + z_[c][s];
                    z_[c][s] = denorm(x - a * y);
                    x = y;
                }
                fb_[c] = fastTanh(x);
                ch[c][i] = in * (1.f - 0.5f * mix_) + x * mix_ * (1.f);
            }
        }
    }
private:
    double sr_ = 48000.0; float z_[2][MAXST] = {}, fb_[2] = {}, ph_ = 0.f; int st_ = 4;
    float rate_ = .4f, depth_ = .7f, centre_ = 800.f, fb_amt_ = .4f, spread_ = .5f, mix_ = .5f;
};

// ============================================================================ Saturation / distortion
class Saturator {
public:
    enum Type { Soft, Hard, Tube, Tape, Fold, Rectify, Sine };
    void prepare(double sr) { sr_ = sr; reset(); }
    void reset() { for (int c = 0; c < 2; c++) { os_[c].reset(); dc_[c].reset(); preS_[c] = 0.f; lpS_[c] = 0.f; } }
    // driveDb 0..48, bias -1..1 (asymmetry -> even harmonics), toneHz post low-pass (20000 = off), type, oversample 1 or 2, mix, outputDb
    void set(Type t, float driveDb, float bias, float toneHz, bool oversample, float mix, float outDb, float preHp) {
        type_ = t; drive_ = dbToLin(driveDb); bias_ = bias; os_on_ = oversample; mix_ = mix; out_ = dbToLin(outDb);
        toneA_ = std::exp(-kTwoPi * std::min(toneHz, (float)sr_ * 0.45f) / (float)sr_);
        preHpA_ = std::exp(-kTwoPi * preHp / (float)sr_);
        // makeup so loud drive does not simply get louder: compensate roughly by the drive amount
        comp_ = 1.f / std::pow(drive_, 0.6f);
    }
    void process(float* L, float* R, int n) {
        float* ch[2] = {L, R};
        for (int c = 0; c < 2; c++) for (int i = 0; i < n; i++) {
            const float dry = ch[c][i];
            float x = dry;
            preS_[c] = preHpA_ * preS_[c] + (1.f - preHpA_) * x; x -= preS_[c];          // pre high-pass (keeps lows out of the nonlinearity)
            float y;
            if (os_on_) y = os_[c].process(x, [&](float s) { return shape(s); });
            else y = shape(x);
            y = dc_[c].tick(y);
            lpS_[c] = (1.f - toneA_) * y + toneA_ * lpS_[c]; y = lpS_[c];
            y *= comp_ * out_;
            ch[c][i] = dry * (1.f - mix_) + y * mix_;
        }
    }
    int latency() const { return os_on_ ? (int)(HalfBand::latency() * 2) : 0; }
private:
    inline float shape(float s) const {
        const float x = s * drive_ + bias_ * 0.5f;
        switch (type_) {
        case Soft:    return fastTanh(x);
        case Hard:    return clampf(x, -1.f, 1.f) * 0.9f;
        case Tube:    { const float p = x > 0 ? fastTanh(x * 0.9f) : fastTanh(x * 1.6f) * 0.75f; return p * 1.05f; }
        case Tape:    { const float a = fastTanh(x * 0.8f); return a - 0.1f * a * a * a + 0.04f * std::sin(x * 2.f); }
        case Fold:    { float p = x * 0.25f + 0.25f; p -= std::floor(p); return 1.f - 4.f * std::fabs(p - 0.5f); }
        case Rectify: return fastTanh(std::fabs(x) * 1.2f - 0.4f);
        case Sine:    return std::sin(clampf(x, -3.f, 3.f) * 0.5f * kPi);
        }
        return x;
    }
    double sr_ = 48000.0; Type type_ = Soft; float drive_ = 1.f, bias_ = 0.f, mix_ = 1.f, out_ = 1.f, toneA_ = 0.f, preHpA_ = 0.f, comp_ = 1.f; bool os_on_ = true;
    Oversampler2 os_[2]; DcBlock dc_[2]; float preS_[2] = {}, lpS_[2] = {};
};

class BitCrusher {
public:
    void prepare(double sr) { sr_ = sr; reset(); }
    void reset() { hold_[0] = hold_[1] = 0.f; cnt_ = 0.f; }
    // bits 1..16 (16 = off), downsample factor 1..64 (1 = off), jitter 0..1, mix
    void set(float bits, float down, float jitter, float mix) { bits_ = bits; down_ = std::max(1.f, down); jit_ = jitter; mix_ = mix; levels_ = std::pow(2.f, std::max(1.f, bits) - 1.f); }
    void process(float* L, float* R, int n) {
        for (int i = 0; i < n; i++) {
            cnt_ += 1.f;
            if (cnt_ >= down_) { cnt_ -= down_; if (jit_ > 0.f) cnt_ += rng_.uni() * jit_ * down_ * 0.5f; hold_[0] = q(L[i]); hold_[1] = q(R[i]); }
            L[i] = L[i] * (1.f - mix_) + hold_[0] * mix_; R[i] = R[i] * (1.f - mix_) + hold_[1] * mix_;
        }
    }
private:
    inline float q(float x) const { if (bits_ >= 15.99f) return x; return std::floor(x * levels_ + 0.5f) / levels_; }
    double sr_ = 48000.0; float hold_[2] = {}, cnt_ = 0.f, bits_ = 16.f, down_ = 1.f, jit_ = 0.f, mix_ = 1.f, levels_ = 32768.f; Rng rng_;
};

// ============================================================================ Parametric EQ
struct EqBand {
    enum Type { Bell, LowShelf, HighShelf, LowCut, HighCut, Notch, BandPass, TiltShelf };
    bool on = false; Type type = Bell; float freq = 1000.f, gainDb = 0.f, q = 1.f; int slope = 12;   // slope dB/oct for cuts: 12, 24, 36, 48
};

class ParametricEq {
public:
    static constexpr int MAXBANDS = 8;
    void prepare(double sr) { sr_ = sr; reset(); }
    void reset() { for (auto& c : bq_) for (auto& s : c) for (auto& b : s) b.reset(); }
    void setBand(int i, const EqBand& b) {
        if (i < 0 || i >= MAXBANDS) return;
        band_[i] = b; design(i);
    }
    // Magnitude response in dB at f, sum of the active bands (for drawing).
    float responseDb(float f) const {
        double m = 1.0;
        for (int i = 0; i < MAXBANDS; i++) if (band_[i].on) for (int s = 0; s < nsec_[i]; s++) m *= biquadMag(coef_[i][s], sr_, f);
        return (float)(20.0 * std::log10(std::max(m, 1e-9)));
    }
    float bandResponseDb(int i, float f) const {
        if (!band_[i].on) return 0.f;
        double m = 1.0; for (int s = 0; s < nsec_[i]; s++) m *= biquadMag(coef_[i][s], sr_, f);
        return (float)(20.0 * std::log10(std::max(m, 1e-9)));
    }
    // Processing mode: 0 stereo, 1 mid/side (bands with the same settings), 2 left only, 3 right only
    void process(float* L, float* R, int n, int mode = 0) {
        for (int i = 0; i < n; i++) {
            float a = L[i], b = R[i];
            if (mode == 1) { const float m = 0.5f * (a + b), s = 0.5f * (a - b); a = m; b = s; }
            for (int k = 0; k < MAXBANDS; k++) {
                if (!band_[k].on) continue;
                for (int s = 0; s < nsec_[k]; s++) {
                    if (mode != 3) a = bq_[0][k][s].process(a);
                    if (mode != 2) b = bq_[1][k][s].process(b);
                }
            }
            if (mode == 1) { const float m = a, s = b; a = m + s; b = m - s; }
            L[i] = (mode == 3) ? L[i] : a; R[i] = (mode == 2) ? R[i] : b;
        }
    }
private:
    void design(int i) {
        const EqBand& b = band_[i]; nsec_[i] = 1;
        switch (b.type) {
        case EqBand::Bell:      coef_[i][0] = biquadDesign(BQ::Peak, sr_, b.freq, b.q, b.gainDb); break;
        case EqBand::LowShelf:  coef_[i][0] = biquadDesign(BQ::LowShelf, sr_, b.freq, std::max(0.3f, b.q * 0.7071f), b.gainDb); break;
        case EqBand::HighShelf: coef_[i][0] = biquadDesign(BQ::HighShelf, sr_, b.freq, std::max(0.3f, b.q * 0.7071f), b.gainDb); break;
        case EqBand::TiltShelf: coef_[i][0] = biquadDesign(BQ::LowShelf, sr_, b.freq, 0.5, b.gainDb * 0.5); coef_[i][1] = biquadDesign(BQ::HighShelf, sr_, b.freq, 0.5, -b.gainDb * 0.5); nsec_[i] = 2; break;
        case EqBand::Notch:     coef_[i][0] = biquadDesign(BQ::Notch, sr_, b.freq, b.q); break;
        case EqBand::BandPass:  coef_[i][0] = biquadDesign(BQ::BandPass, sr_, b.freq, b.q); break;
        case EqBand::LowCut: case EqBand::HighCut: {
            const int sections = std::max(1, std::min(4, b.slope / 12)) ;
            // Butterworth cascade: first-order sections are approximated by pairing; use 12 dB per biquad with staggered Q
            nsec_[i] = sections;
            for (int s = 0; s < sections; s++) {
                const double q = 1.0 / (2.0 * std::cos(3.14159265358979323846 * (2 * s + 1) / (4.0 * sections)));   // Butterworth poles
                coef_[i][s] = biquadDesign(b.type == EqBand::LowCut ? BQ::HighPass : BQ::LowPass, sr_, b.freq, q);
            }
            break;
        }
        }
        for (int c = 0; c < 2; c++) for (int s = 0; s < nsec_[i]; s++) bq_[c][i][s].set(coef_[i][s]);
    }
    double sr_ = 48000.0; EqBand band_[MAXBANDS]; BiquadCoef coef_[MAXBANDS][4]; int nsec_[MAXBANDS] = {1, 1, 1, 1, 1, 1, 1, 1}; Biquad bq_[2][MAXBANDS][4];
};

// ============================================================================ Compressor + lookahead limiter
class Compressor {
public:
    void prepare(double sr) {
        sr_ = sr; for (int c = 0; c < 2; c++) la_[c].init((int)(0.012 * sr)); win_.assign((size_t)(0.01 * sr) + 8, 0.f); reset();
    }
    void reset() { for (auto& d : la_) d.clear(); env_ = 0.f; gr_ = 0.f; rms_ = 0.f; sc_[0] = sc_[1] = 0.f; std::fill(win_.begin(), win_.end(), 0.f); wpos_ = 0; limGain_ = 1.f; grMeter_ = 0.f; autoRel_ = 0.f; }
    // thresholdDb, ratio (>= 1; 100 = limit), kneeDb, attackMs, releaseMs, rmsMix 0..1 (0 = peak detect), lookaheadMs 0..5, makeupDb, mix 0..1,
    // scHpHz sidechain high-pass, link 0..1 (stereo link), autoRelease, limiterOn + ceilingDb + limiterReleaseMs
    struct Params { float thresh = -18, ratio = 4, knee = 6, attack = 10, release = 120, rms = 0, lookahead = 0, makeup = 0, mix = 1, scHp = 20, link = 1; bool autoRelease = false, autoMakeup = false;
                    bool limiter = false; float ceiling = -0.3f, limRelease = 60.f; };
    void set(const Params& p) {
        p_ = p;
        atk_ = std::exp(-1.f / (std::max(p.attack, 0.05f) * 0.001f * (float)sr_));
        rel_ = std::exp(-1.f / (std::max(p.release, 1.f) * 0.001f * (float)sr_));
        hp_ = std::exp(-kTwoPi * p.scHp / (float)sr_);
        laN_ = (int)(p.lookahead * 0.001f * (float)sr_);
        mk_ = dbToLin(p.makeup + (p.autoMakeup ? autoMakeupDb(p) : 0.f));
        limRel_ = std::exp(-1.f / (std::max(p.limRelease, 1.f) * 0.001f * (float)sr_));
        ceil_ = dbToLin(p.ceiling);
        limLaN_ = (int)(0.0015f * (float)sr_);
    }
    // Static curve: output level (dB) for an input level (dB) -- drawn by the UI.
    static float curveDb(float inDb, float thresh, float ratio, float knee) {
        const float over = inDb - thresh;
        if (knee > 0.01f && 2.f * over > -knee && 2.f * over < knee) { const float t = over + knee * 0.5f; return inDb + (1.f / ratio - 1.f) * t * t / (2.f * knee); }
        return over > 0.f ? thresh + over / ratio : inDb;
    }
    int latency() const { return laN_ + (p_.limiter ? limLaN_ : 0); }
    void process(float* L, float* R, int n) {
        for (int i = 0; i < n; i++) {
            const float in[2] = {L[i], R[i]};
            // sidechain: high-passed input level
            float sc[2];
            for (int c = 0; c < 2; c++) { sc_[c] = hp_ * sc_[c] + (1.f - hp_) * in[c]; sc[c] = in[c] - sc_[c]; }
            float lvlP = std::max(std::fabs(sc[0]), std::fabs(sc[1]));
            const float lvlStereo = lerp(std::max(std::fabs(sc[0]), std::fabs(sc[1])), 0.5f * (std::fabs(sc[0]) + std::fabs(sc[1])), p_.link);
            lvlP = lvlStereo;
            const float sq = 0.5f * (sc[0] * sc[0] + sc[1] * sc[1]);
            rms_ = rms_ * 0.995f + sq * 0.005f;
            const float lvl = lerp(lvlP, std::sqrt(rms_), p_.rms);
            const float db = linToDb(lvl);
            const float target = std::max(0.f, db - curveDb(db, p_.thresh, std::max(1.f, p_.ratio), p_.knee));   // gain reduction wanted (dB, >= 0)
            // ballistics on gain reduction (log domain)
            float a = atk_, r = rel_;
            if (p_.autoRelease) { autoRel_ += (target - autoRel_) * 0.0005f; r = std::exp(-1.f / ((0.04f + 0.4f * clampf(1.f - autoRel_ / 12.f, 0.f, 1.f)) * (float)sr_)); }
            if (target > gr_) gr_ = a * gr_ + (1.f - a) * target; else gr_ = r * gr_ + (1.f - r) * target;
            gr_ = denorm(gr_);
            const float g = dbToLin(-gr_) * mk_;
            // lookahead: delay the audio so the gain computer sees the transient early
            float o[2];
            for (int c = 0; c < 2; c++) { la_[c].push(in[c]); o[c] = laN_ > 0 ? la_[c].tap(laN_ - 1) : in[c]; }
            float y[2] = {o[0] * g, o[1] * g};
            // optional brickwall limiter stage
            if (p_.limiter) {
                const int N = limLaN_;
                const float pk = std::max(std::fabs(y[0]), std::fabs(y[1]));
                win_[wpos_] = pk; wpos_ = (wpos_ + 1) % (int)win_.size();
                float mx = 0.f; for (int k = 0; k < N + 1; k++) mx = std::max(mx, win_[(wpos_ - 1 - k + (int)win_.size() * 2) % (int)win_.size()]);
                const float need = mx > ceil_ ? ceil_ / mx : 1.f;
                if (need < limGain_) limGain_ += (need - limGain_) * 0.5f; else limGain_ = limRel_ * limGain_ + (1.f - limRel_) * need;
                // delayed copy of y for the limiter lookahead
                limBuf_[0][limW_] = y[0]; limBuf_[1][limW_] = y[1];
                const int ri = (limW_ - N + 4096) & 4095; limW_ = (limW_ + 1) & 4095;
                y[0] = limBuf_[0][ri] * limGain_; y[1] = limBuf_[1][ri] * limGain_;
                y[0] = clampf(y[0], -ceil_, ceil_); y[1] = clampf(y[1], -ceil_, ceil_);
            }
            L[i] = o[0] * (1.f - p_.mix) + y[0] * p_.mix;   // the dry part is delayed like the wet one (lookahead)
            R[i] = o[1] * (1.f - p_.mix) + y[1] * p_.mix;
            grMeter_ = std::max(grMeter_ * 0.9997f, gr_ + (p_.limiter ? -linToDb(limGain_) : 0.f));
        }
    }
    float grDb() const { return gr_ + (p_.limiter ? -linToDb(limGain_) : 0.f); }
    float grPeak() { float v = grMeter_; grMeter_ = 0.f; return v; }
private:
    static float autoMakeupDb(const Params& p) { return std::max(0.f, -p.thresh * (1.f - 1.f / std::max(1.f, p.ratio)) * 0.5f); }
    double sr_ = 48000.0; Params p_; DelayLine la_[2]; std::vector<float> win_; int wpos_ = 0; float limBuf_[2][4096] = {}; int limW_ = 0;
    float atk_ = .9f, rel_ = .999f, hp_ = 0.f, env_ = 0.f, gr_ = 0.f, rms_ = 0.f, sc_[2] = {}, mk_ = 1.f, limRel_ = .999f, ceil_ = .97f, limGain_ = 1.f, grMeter_ = 0.f, autoRel_ = 0.f; int laN_ = 0, limLaN_ = 72;
};

}  // namespace zdsp
