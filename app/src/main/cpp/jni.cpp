#include <jni.h>
#include <cmath>
#include "engine.h"
#include "extract.h"
#include "recorder.h"
using namespace zedok;
static Engine& eng() { static Engine e; return e; }
#define J(ret, name, ...) extern "C" JNIEXPORT ret JNICALL Java_com_zedokmusic_engine_NativeEngine_##name(JNIEnv* env, jobject, ##__VA_ARGS__)
static std::string str(JNIEnv* env, jstring s) { const char* c = env->GetStringUTFChars(s, nullptr); std::string r(c); env->ReleaseStringUTFChars(s, c); return r; }

J(jboolean, start) { return eng().start(); }
J(void, stop) { eng().stop(); }
J(jint, addTrack) { return eng().addTrack(); }
J(jboolean, removeTrack, jint id) { return eng().removeTrack(id); }
// Returns [clipId, frames] or null when the file cannot be read.
J(jlongArray, loadClip, jint track, jstring path, jlong start) {
    int64_t frames = 0; int id = eng().loadClip(track, str(env, path), start, &frames);
    if (!id) return nullptr;
    jlong v[2] = {id, frames}; jlongArray a = env->NewLongArray(2); env->SetLongArrayRegion(a, 0, 2, v); return a;
}
J(jboolean, removeClip, jint id) { return eng().removeClip(id); }
J(jboolean, moveClip, jint id, jlong start) { return eng().moveClip(id, start); }
J(jboolean, setClipGain, jint id, jfloat g) { return eng().setClipGain(id, g); }
// Test helper: generates a sine clip (stereo) of `seconds` at `freq` Hz.
J(jint, addToneClip, jint track, jlong start, jdouble seconds, jdouble freq, jdouble amp) {
    int64_t n = (int64_t)(seconds * kProjectRate);
    auto v = std::make_shared<std::vector<float>>(n * 2);
    for (int64_t i = 0; i < n; i++) { float s = (float)(amp * sin(2 * M_PI * freq * i / kProjectRate)); (*v)[i * 2] = s; (*v)[i * 2 + 1] = s; }
    return eng().addClip(track, v, start, 0, n, 1.f);
}
J(void, setTrackGain, jint id, jfloat g) { eng().setTrackGain(id, g); }
J(void, setTrackPan, jint id, jfloat p) { eng().setTrackPan(id, p); }
J(void, setTrackMute, jint id, jboolean m) { eng().setTrackMute(id, m); }
J(void, setTrackSolo, jint id, jboolean s) { eng().setTrackSolo(id, s); }
J(void, setMasterGain, jfloat g) { eng().setMasterGain(g); }
J(void, setLoop, jboolean on, jlong s, jlong e) { eng().setLoop(on, s, e); }
J(void, play) { eng().play(); }
J(void, pause) { eng().pause(); }
J(void, seek, jlong f) { eng().seek(f); }
J(jboolean, isPlaying) { return eng().playing(); }
J(jlong, position) { return eng().position(); }
J(jlong, projectLength) { return eng().projectLength(); }
J(jfloat, peak, jint track) { return eng().peak(track); }
// Renders one track on its own (a stem).
J(jboolean, renderTrackToWav, jstring path, jlong from, jlong to, jint fmt, jint track) {
    return eng().renderToWav(str(env, path), from, to, fmt == 0 ? WavFormat::Pcm16 : fmt == 1 ? WavFormat::Pcm24 : WavFormat::Float32, track);
}
J(jboolean, renderToWav, jstring path, jlong from, jlong to, jint fmt) {
    return eng().renderToWav(str(env, path), from, to, fmt == 0 ? WavFormat::Pcm16 : fmt == 1 ? WavFormat::Pcm24 : WavFormat::Float32);
}
J(jfloatArray, stats) {
    auto s = eng().stats();
    float v[8] = {(float)s.sampleRate, (float)s.burst, (float)s.bufferSize, (float)s.xruns, s.avgUs, s.maxUs, s.latencyMs, s.exclusive ? 1.f : 0.f};
    jfloatArray a = env->NewFloatArray(8); env->SetFloatArrayRegion(a, 0, 8, v); return a;
}

J(jint, callbackFrames) { return kCallbackFrames; }
J(jboolean, addNote, jint track, jlong start, jlong len, jint pitch, jint vel) { return eng().addNote(track, start, len, pitch, vel); }
J(void, clearNotes, jint track) { eng().clearNotes(track); }
J(jboolean, createPluginBridge, jint track, jstring path) { return eng().createPluginBridge(track, str(env, path), kCallbackFrames); }
J(jboolean, detachPlugin, jint track) { return eng().detachPlugin(track); }
J(jint, pluginState, jint track) { return eng().pluginState(track); }
J(jstring, pluginName, jint track) { return env->NewStringUTF(eng().pluginName(track).c_str()); }
J(void, liveNote, jint track, jboolean on, jint pitch, jint vel) { eng().liveNote(track, on, pitch, vel); }
J(jlongArray, pluginStats, jint track) {
    auto s = eng().pluginStats(track); jlong v[5] = {s.late, s.blocks, s.waited, s.waitNsTotal, s.waitNsMax};
    jlongArray a = env->NewLongArray(5); env->SetLongArrayRegion(a, 0, 5, v); return a;
}

static std::atomic<int64_t> g_extractProgress{0};
// Extracts an uncompressed .tar.zst asset (given as fd/offset/length) into destDir. Returns "" on success or an error message.
J(jstring, extractTarZst, jint fd, jlong offset, jlong length, jstring dest) {
    g_extractProgress.store(0);
    return env->NewStringUTF(extractTarZst(fd, offset, length, str(env, dest), &g_extractProgress).c_str());
}
J(jlong, extractProgress) { return g_extractProgress.load(); }

// data = 4 longs per note: startFrame, lengthFrames, pitch, velocity
J(void, setNotes, jint track, jlongArray data) {
    jsize n = env->GetArrayLength(data) / 4; std::vector<jlong> raw(n * 4);
    env->GetLongArrayRegion(data, 0, n * 4, raw.data());
    std::vector<Note> notes(n);
    for (jsize i = 0; i < n; i++) {
        notes[i].start = raw[i * 4]; notes[i].length = std::max<jlong>(1, raw[i * 4 + 1]);
        notes[i].pitch = (uint8_t)std::clamp<jlong>(raw[i * 4 + 2], 0, 127); notes[i].velocity = (uint8_t)std::clamp<jlong>(raw[i * 4 + 3], 1, 127);
    }
    eng().setNotes(track, std::move(notes));
}

J(jint, paramCount, jint track) { return eng().paramCount(track); }
// Returns [name, units] or null; def/steps go to the out array [default, steps].
J(jobjectArray, paramInfo, jint track, jint index, jfloatArray out) {
    std::string name, units; float def = 0; int steps = 0;
    if (!eng().paramInfo(track, index, &name, &units, &def, &steps)) return nullptr;
    jfloat v[2] = {def, (jfloat)steps}; env->SetFloatArrayRegion(out, 0, 2, v);
    jclass sc = env->FindClass("java/lang/String"); jobjectArray a = env->NewObjectArray(2, sc, nullptr);
    env->SetObjectArrayElement(a, 0, env->NewStringUTF(name.c_str())); env->SetObjectArrayElement(a, 1, env->NewStringUTF(units.c_str())); return a;
}
J(jfloat, paramValue, jint track, jint index) { return eng().paramValue(track, index); }
J(void, guiCommand, jint track, jint cmd, jint x, jint y) { eng().guiCommand(track, cmd, x, y); }
J(jintArray, guiState, jint track) {  // [state, width, height, x, y]
    int w, h, x, y; int st = eng().guiState(track, &w, &h, &x, &y); jint v[5] = {st, w, h, x, y};
    jintArray a = env->NewIntArray(5); env->SetIntArrayRegion(a, 0, 5, v); return a;
}
// meta = [param, pointCount] per lane; frames/values are the concatenated points.
J(void, setAutomation, jint track, jlongArray meta, jlongArray frames, jfloatArray values, jbyteArray shapes) {
    const int nm = env->GetArrayLength(meta) / 2;
    std::vector<jlong> m(nm * 2), f(env->GetArrayLength(frames)); std::vector<jfloat> v(env->GetArrayLength(values)); std::vector<jbyte> sh(env->GetArrayLength(shapes));
    env->GetLongArrayRegion(meta, 0, nm * 2, m.data()); env->GetLongArrayRegion(frames, 0, (jsize)f.size(), f.data()); env->GetFloatArrayRegion(values, 0, (jsize)v.size(), v.data()); env->GetByteArrayRegion(shapes, 0, (jsize)sh.size(), sh.data());
    std::vector<AutoLane> lanes; size_t o = 0;
    for (int i = 0; i < nm && i < kMaxLanes; i++) {
        AutoLane l; l.param = (int)m[i * 2]; const size_t c = (size_t)m[i * 2 + 1];
        for (size_t k = 0; k < c && o + k < f.size(); k++) { l.frames.push_back(f[o + k]); l.values.push_back(v[o + k]); l.shapes.push_back(o + k < sh.size() ? (uint8_t)sh[o + k] : 0); }
        o += c; if (!l.frames.empty()) lanes.push_back(std::move(l));
    }
    eng().setAutomation(track, std::move(lanes));
}
// Edits made in the plugin editor since the last call: [index, value, index, value, ...].
J(jfloatArray, pollEdits, jint track) {
    ZkParamChange ch[ZK_EDIT_RING]; int n = eng().pollEdits(track, ch, ZK_EDIT_RING);
    std::vector<jfloat> v(n * 2); for (int i = 0; i < n; i++) { v[i * 2] = (float)ch[i].index; v[i * 2 + 1] = ch[i].value; }
    jfloatArray a = env->NewFloatArray(n * 2); if (n) env->SetFloatArrayRegion(a, 0, n * 2, v.data()); return a;
}
J(jstring, paramText, jint track, jint index) { return env->NewStringUTF(eng().paramText(track, index).c_str()); }
J(void, setParam, jint track, jint index, jfloat v) { eng().setParam(track, index, v); }

static Recorder& rec() { static Recorder r; return r; }
// Returns "" on success or an error message.
J(jstring, recStart, jstring path, jint deviceId) { return env->NewStringUTF(rec().start(str(env, path), kProjectRate, deviceId).c_str()); }
// Returns [frames, dropped, inputBufferFrames, channels, ok].
J(jlongArray, recStop) {
    auto r = rec().stop(); jlong v[5] = {r.frames, r.dropped, r.inputBufferFrames, r.channels, r.ok ? 1 : 0};
    jlongArray a = env->NewLongArray(5); env->SetLongArrayRegion(a, 0, 5, v); return a;
}
J(jboolean, recActive) { return rec().active(); }
J(jfloat, recLevel) { return rec().level(); }
