// Wine-side plugin host: loads a VST2 DLL and processes audio blocks requested through a shared-memory file.
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <windows.h>
#include <emmintrin.h>
#include "shm.h"
typedef struct AEffect AEffect;
struct AEffect { int32_t magic; void* d; void (*process)(AEffect*, float**, float**, int32_t); char rest[200]; };
typedef AEffect* (*Entry)(void*);
static intptr_t master(void*a,int32_t b,int32_t c,intptr_t d,void*e,float f){(void)a;(void)b;(void)c;(void)d;(void)e;(void)f;return 2400;}
int main(int argc, char** argv){
    if(argc<3){ fprintf(stderr,"usage: zhost plugin.dll shmfile\n"); return 2; }
    LARGE_INTEGER t0,t1,fq; QueryPerformanceFrequency(&fq); QueryPerformanceCounter(&t0);
    HMODULE m = LoadLibraryA(argv[1]);
    if(!m){ fprintf(stderr,"LoadLibrary failed %lu\n", GetLastError()); return 3; }
    QueryPerformanceCounter(&t1);
    printf("plugin loaded in %.1f ms\n", (t1.QuadPart-t0.QuadPart)*1000.0/fq.QuadPart);
    Entry ent = (Entry)GetProcAddress(m,"VSTPluginMain"); if(!ent) ent=(Entry)GetProcAddress(m,"main");
    AEffect* fx = ent ? ent(master) : NULL;
    if(!fx || fx->magic!=0x56737450){ fprintf(stderr,"bad plugin\n"); return 4; }
    HANDLE f = CreateFileA(argv[2], GENERIC_READ|GENERIC_WRITE, FILE_SHARE_READ|FILE_SHARE_WRITE, NULL, OPEN_EXISTING, 0, NULL);
    if(f==INVALID_HANDLE_VALUE){ fprintf(stderr,"open shm failed %lu\n", GetLastError()); return 5; }
    HANDLE mp = CreateFileMappingA(f, NULL, PAGE_READWRITE, 0, ZK_SHM_SIZE, NULL);
    struct ZkShm* s = mp ? (struct ZkShm*)MapViewOfFile(mp, FILE_MAP_ALL_ACCESS, 0,0, ZK_SHM_SIZE) : NULL;
    if(!s){ fprintf(stderr,"map failed %lu\n", GetLastError()); return 6; }
    InterlockedExchange((volatile LONG*)&s->ready, 1);
    printf("host ready\n"); fflush(stdout);
    uint32_t last = 0;
    while(!s->quit){
        uint32_t r = s->req_seq;
        if(r==last){ _mm_pause(); continue; }
        float* in[2]={s->in[0],s->in[1]}; float* out[2]={s->out[0],s->out[1]};
        fx->process(fx,in,out,(int32_t)s->frames);
        last = r; InterlockedExchange((volatile LONG*)&s->ack_seq,(LONG)r);
    }
    return 0;
}
