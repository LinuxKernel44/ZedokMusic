#!/usr/bin/env python3
"""Builds app/src/main/assets/piano.zpk from the Salamander Grand Piano V3 (48 kHz, 24 bit) WAV files.
Salamander Grand Piano: Yamaha C5 recorded by Alexander Holm, licence CC BY 3.0 (https://creativecommons.org/licenses/by/3.0/).
usage: build-piano-pack.py <SalamanderGrandPianoV3_48khz24bit/48khz24bit> <out.zpk> [quality]
Every sound is trimmed (the tail of a long note is faded out: the longer the lower the note), encoded to Ogg Vorbis with ffmpeg and
written into one pack: header, index, then the Ogg files. Entry kinds: 0 note (key = pitch of the sample, layer 1..16), 1 key-off noise
(key = MIDI note), 2 pedal down, 3 pedal up (key = variant)."""
import os, re, struct, subprocess, sys, tempfile, wave
from concurrent.futures import ThreadPoolExecutor

SRC, OUT = sys.argv[1], sys.argv[2]
Q = sys.argv[3] if len(sys.argv) > 3 else '4'
NAMES = {'C': 0, 'C#': 1, 'D': 2, 'D#': 3, 'E': 4, 'F': 5, 'F#': 6, 'G': 7, 'G#': 8, 'A': 9, 'A#': 10, 'B': 11}

def midi(name, octv): return 12 * (octv + 1) + NAMES[name]
def cap_seconds(key):   # seconds kept for a note of this pitch
    return max(4.0, 16.0 - 11.5 * (key - 21) / 87.0)

jobs = []
for f in sorted(os.listdir(SRC)):
    m = re.fullmatch(r'([A-G]#?)(\d)v(\d+)\.wav', f)
    if m: k = midi(m.group(1), int(m.group(2))); jobs.append((0, k, int(m.group(3)), f, cap_seconds(k), 1.8)); continue
    m = re.fullmatch(r'rel(\d+)\.wav', f)
    if m: jobs.append((1, 20 + int(m.group(1)), 0, f, 2.5, 0.4)); continue
    m = re.fullmatch(r'pedal([DU])(\d)\.wav', f)
    if m: jobs.append((2 if m.group(1) == 'D' else 3, int(m.group(2)), 0, f, 2.5, 0.3)); continue

tmp = tempfile.mkdtemp(prefix='zpk')
def enc(j):
    kind, key, layer, f, cap, fade = j
    src = os.path.join(SRC, f)
    with wave.open(src) as w: rate, n = w.getframerate(), w.getnframes()
    keep = min(n / rate, cap)
    out = os.path.join(tmp, f.replace('.wav', '.ogg'))
    af = f'afade=t=out:st={max(0.0, keep - fade):.3f}:d={fade:.3f}' if n / rate > cap else 'anull'
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', src, '-t', f'{keep:.3f}', '-af', af, '-ac', '2', '-c:a', 'libvorbis', '-q:a', Q, out], check=True)
    return (kind, key, layer, out, int(keep * rate), rate)

with ThreadPoolExecutor(max_workers=os.cpu_count()) as ex: res = list(ex.map(enc, jobs))
res.sort(key=lambda r: (r[0], r[1], r[2]))
blobs = [open(r[3], 'rb').read() for r in res]
HEAD = 8; ENT = 4 + 4 * 4  # kind,key,layer,pad + offset,size,frames,rate
base = HEAD + ENT * len(res); off = base; ents = b''
for r, b in zip(res, blobs):
    ents += struct.pack('<BBBBIIII', r[0], r[1], r[2], 0, off, len(b), r[4], r[5]); off += len(b)
with open(OUT, 'wb') as fo:
    fo.write(b'ZPK1' + struct.pack('<I', len(res))); fo.write(ents)
    for b in blobs: fo.write(b)
print(len(res), 'sounds,', round(off / 1e6, 1), 'MB')
