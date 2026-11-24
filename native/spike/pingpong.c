// Native (ARM64) side: acts as the audio engine, feeds blocks to the Wine host and measures round-trip time.
#include <stdio.h>
#include <stdlib.h>
#include <fcntl.h>
#include <unistd.h>
#include <time.h>
#include <sys/mman.h>
#include <math.h>
#include "shm.h"
static double now_us(void){ struct timespec t; clock_gettime(CLOCK_MONOTONIC,&t); return t.tv_sec*1e6+t.tv_nsec/1e3; }
int cmp(const void*a,const void*b){ double x=*(double*)a,y=*(double*)b; return x<y?-1:x>y; }
int main(int argc,char**argv){
    const char* path=argv[1]; int frames=argc>2?atoi(argv[2]):256; int iters=argc>3?atoi(argv[3]):5000;
    int fd=open(path,O_RDWR|O_CREAT,0666); ftruncate(fd,ZK_SHM_SIZE);
    struct ZkShm* s=mmap(0,ZK_SHM_SIZE,PROT_READ|PROT_WRITE,MAP_SHARED,fd,0);
    s->req_seq=s->ack_seq=s->quit=s->ready=0; s->frames=frames;
    printf("waiting for host...\n"); fflush(stdout);
    double t=now_us(); while(!__atomic_load_n(&s->ready,__ATOMIC_ACQUIRE)){ usleep(1000); if(now_us()-t>120e6){printf("TIMEOUT host\n");return 1;} }
    double* lat=malloc(sizeof(double)*iters); int bad=0;
    for(int i=1;i<=iters;i++){
        for(int k=0;k<frames;k++){ float v=sinf(k*0.05f+i); s->in[0][k]=v; s->in[1][k]=-v; }
        double a=now_us();
        __atomic_store_n(&s->req_seq,i,__ATOMIC_RELEASE);
        while(__atomic_load_n(&s->ack_seq,__ATOMIC_ACQUIRE)!=(uint32_t)i){ if(now_us()-a>2e6){printf("TIMEOUT block %d\n",i);return 1;} }
        lat[i-1]=now_us()-a;
        if(fabsf(s->out[0][10]-0.5f*s->in[0][10])>1e-6f) bad++;
    }
    qsort(lat,iters,sizeof(double),cmp);
    double budget=frames/48000.0*1e6;
    int over=0; for(int i=0;i<iters;i++) if(lat[i]>budget) over++;
    printf("frames=%d iters=%d budget=%.0fus  p50=%.1f p99=%.1f p999=%.1f max=%.1f us  over_budget=%d  wrong_samples=%d\n",
        frames,iters,budget,lat[iters/2],lat[(int)(iters*0.99)],lat[(int)(iters*0.999)],lat[iters-1],over,bad);
    s->quit=1; return 0;
}
