// Minimal VST2-ABI plugin used to validate the Wine bridge (x64). Applies gain 0.5 and adds nothing else.
#include <stdint.h>
#include <string.h>
#include <windows.h>
typedef struct AEffect AEffect;
typedef intptr_t (*Dispatch)(AEffect*, int32_t, int32_t, intptr_t, void*, float);
typedef void (*Proc)(AEffect*, float**, float**, int32_t);
typedef void (*SetP)(AEffect*, int32_t, float);
typedef float (*GetP)(AEffect*, int32_t);
struct AEffect {
    int32_t magic; Dispatch dispatcher; Proc process; SetP setParameter; GetP getParameter;
    int32_t numPrograms, numParams, numInputs, numOutputs, flags;
    intptr_t resvd1, resvd2; int32_t initialDelay, realQ, offQ; float ioRatio;
    void *object, *user; int32_t uniqueID, version; Proc processReplacing; Proc processDouble; char future[56];
};
typedef intptr_t (*Master)(AEffect*, int32_t, int32_t, intptr_t, void*, float);
static float g_gain = 0.5f;
static intptr_t disp(AEffect* e, int32_t op, int32_t i, intptr_t v, void* p, float o){ (void)e;(void)i;(void)v;(void)p;(void)o; return op==33 ? 0 : 0; }
static void proc(AEffect* e, float** in, float** out, int32_t n){ (void)e; for(int c=0;c<2;c++) for(int i=0;i<n;i++) out[c][i]=in[c][i]*g_gain; }
static void setp(AEffect* e,int32_t i,float v){(void)e;(void)i;g_gain=v;}
static float getp(AEffect* e,int32_t i){(void)e;(void)i;return g_gain;}
__declspec(dllexport) AEffect* VSTPluginMain(Master m){
    static AEffect fx; (void)m; memset(&fx,0,sizeof fx);
    fx.magic=0x56737450; fx.dispatcher=disp; fx.process=proc; fx.processReplacing=proc;
    fx.setParameter=setp; fx.getParameter=getp; fx.numParams=1; fx.numInputs=2; fx.numOutputs=2; fx.flags=1|16; fx.uniqueID=0x5A4B3031;
    return &fx;
}
BOOL WINAPI DllMain(HINSTANCE h, DWORD r, LPVOID x){ (void)h;(void)r;(void)x; return TRUE; }
