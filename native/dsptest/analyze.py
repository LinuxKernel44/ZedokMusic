#!/usr/bin/env python3
"""Analysis helpers for rendered WAVs. usage: analyze.py file.wav [f0]  -> peak envelope, spectrum summary, alias ratio (if f0 given)."""
import sys, numpy as np, scipy.io.wavfile as w
sr, x = w.read(sys.argv[1]); x = x.astype(np.float64)
if x.ndim > 1: m = x.mean(axis=1)
else: m = x
print(f"len {len(m)/sr:.2f}s  peak {np.abs(x).max():.3f}")
seg = int(sr*0.25)
print("peak per 250ms:", " ".join(f"{np.abs(m[i:i+seg]).max():.2f}" for i in range(0, len(m)-seg+1, seg)))
if len(sys.argv) > 2:
    f0 = float(sys.argv[2]); a = int(0.3*sr); b = min(len(m), a+sr)
    win = np.hanning(b-a); X = np.abs(np.fft.rfft(m[a:b]*win)); f = np.fft.rfftfreq(b-a, 1/sr)
    # harmonic energy: bins within 1.5% of k*f0 (unison detune widens this, so also report a coarse alias view)
    tot = (X**2).sum(); har = 0
    for k in range(1, int(sr/2/f0)+1):
        har += (X[(abs(f-k*f0) < f0*0.03)]**2).sum()
    print(f"energy in +-3% bands around harmonics: {100*har/tot:.2f}%  (rest = detune/alias/noise)")
    top = np.argsort(X)[-8:][::-1]; print("strongest bins Hz:", sorted(set(int(f[i]) for i in top)))
    print("band energy dB: " + " ".join(f"{lo}-{hi}:{10*np.log10((X[(f>=lo)&(f<hi)]**2).sum()/tot+1e-12):.0f}" for lo,hi in [(0,200),(200,1000),(1000,4000),(4000,10000),(10000,16000),(16000,24000)]))
