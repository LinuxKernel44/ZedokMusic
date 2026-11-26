#!/usr/bin/env python3
import sys, numpy as np, scipy.io.wavfile as w
sr,x=w.read(sys.argv[1]); m=np.abs(x).max(axis=1) if x.ndim>1 else np.abs(x)
hop=int(sr*0.005); env=np.array([m[i:i+hop].max() for i in range(0,len(m)-hop,hop)])
on=[i*5 for i in range(1,len(env)) if env[i]>0.02 and env[i-1]<=0.02*0.5+0.002]
print("onsets (ms):", on[:24])
