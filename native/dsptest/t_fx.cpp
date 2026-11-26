#include "../dsp/fx.h"
#include <cstdio>
int main(){ using namespace zdsp; Reverb r; r.prepare(48000); r.set(Reverb::Hall,.6f,2.5f,.4f,.7f,.3f,10,80,9000,1,0,.3f);
 std::vector<float> L(48000*4,0.f),R=L; L[0]=R[0]=1; r.process(L.data(),R.data(),(int)L.size()); double e=0; for(float x:L)e+=x*x; printf("reverb energy %g tail %g\n",e,L[48000*3]);
 Delay d; d.prepare(48000); Chorus c; c.prepare(48000); Phaser p; p.prepare(48000); Saturator s; s.prepare(48000); BitCrusher b; b.prepare(48000); ParametricEq q; q.prepare(48000); Compressor k; k.prepare(48000);
 printf("ok\n"); }
