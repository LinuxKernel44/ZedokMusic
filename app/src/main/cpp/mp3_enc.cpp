#include "mp3_enc.h"
#include <lame.h>
#include <algorithm>
#include <cstdio>
namespace zedok {
bool encodeMp3(const std::vector<float>& st, int rate, int kbps, const std::string& path) {
    lame_t g = lame_init(); if (!g) return false;
    lame_set_in_samplerate(g, rate); lame_set_out_samplerate(g, rate); lame_set_num_channels(g, 2);
    lame_set_mode(g, JOINT_STEREO); lame_set_brate(g, kbps); lame_set_VBR(g, vbr_off); lame_set_quality(g, 2);
    if (lame_init_params(g) < 0) { lame_close(g); return false; }
    FILE* f = fopen(path.c_str(), "wb"); if (!f) { lame_close(g); return false; }
    const size_t frames = st.size() / 2, chunk = 1 << 16;
    std::vector<unsigned char> out(chunk * 5 / 4 + 7200);
    bool ok = true;
    for (size_t pos = 0; pos < frames && ok; pos += chunk) {
        const int n = (int)std::min(chunk, frames - pos);
        const int w = lame_encode_buffer_interleaved_ieee_float(g, st.data() + pos * 2, n, out.data(), (int)out.size());
        if (w < 0 || (w > 0 && fwrite(out.data(), 1, (size_t)w, f) != (size_t)w)) ok = false;
    }
    if (ok) { const int w = lame_encode_flush(g, out.data(), (int)out.size()); if (w > 0) fwrite(out.data(), 1, (size_t)w, f); }
    if (ok) {  // Xing/Info frame with the real length, at the start of the file
        std::vector<unsigned char> tag(8192); const size_t n = lame_get_lametag_frame(g, tag.data(), tag.size());
        if (n > 0 && n <= tag.size()) { fseek(f, 0, SEEK_SET); fwrite(tag.data(), 1, n, f); }
    }
    fclose(f); lame_close(g);
    return ok;
}
}  // namespace zedok
