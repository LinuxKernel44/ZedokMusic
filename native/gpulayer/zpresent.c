// VK_LAYER_ZEDOK_present: a Vulkan layer that shows a GPU driver's frames on ZedokMusic's X screen.
//
// Phone GPU drivers (Turnip on Adreno) present through DRI3, which the app's software X server (Xvfb) does not have. Mesa can
// create "headless" swapchains (MESA_VK_WSI_HEADLESS_SWAPCHAIN=1) that render normally but show nothing. This layer sits on top:
// when a frame is presented it copies the swapchain image into host memory and draws it into the application's X window with
// plain PutImage requests, then lets the headless present go through. It also caps the frame rate (ZP_FPS, default 60) because a
// headless swapchain never waits for a display.
//
// Interface: Vulkan loader-layer interface v2 (vkNegotiateLoaderLayerInterfaceVersion). Only libc and libxcb (dlopen) are used.
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>
#include <vulkan/vk_layer.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define EXPORT __attribute__((visibility("default")))
#define MAXN 16

// ---- the few XCB entry points we need, loaded at run time ----
typedef struct { unsigned int sequence; } zcookie;
static void* (*x_connect)(const char*, int*);
static int (*x_has_error)(void*);
static uint32_t (*x_gen_id)(void*);
static zcookie (*x_create_gc)(void*, uint32_t, uint32_t, uint32_t, const void*);
static zcookie (*x_put_image)(void*, uint8_t, uint32_t, uint32_t, uint16_t, uint16_t, int16_t, int16_t, uint8_t, uint8_t, uint32_t, const uint8_t*);
static int (*x_flush)(void*);
static void* xconn; static int xtried;
static pthread_mutex_t lk = PTHREAD_MUTEX_INITIALIZER;
static int fps_cap = 60;
static void zlog(const char* fmt, ...) {  // optional trace to the file named by ZP_LOG
    static FILE* f; static int tried; if (!tried) { tried = 1; const char* p = getenv("ZP_LOG"); if (p) f = fopen(p, "a"); }
    if (!f) return;
    va_list ap; va_start(ap, fmt); vfprintf(f, fmt, ap); va_end(ap); fputc(10, f); fflush(f);
}

static int x_ready(void) {
    if (!xtried) {
        xtried = 1;
        void* h = dlopen("libxcb.so.1", RTLD_NOW);
        if (h) {
            x_connect = dlsym(h, "xcb_connect"); x_has_error = dlsym(h, "xcb_connection_has_error"); x_gen_id = dlsym(h, "xcb_generate_id");
            x_create_gc = dlsym(h, "xcb_create_gc"); x_put_image = dlsym(h, "xcb_put_image"); x_flush = dlsym(h, "xcb_flush");
            if (x_connect && x_has_error && x_gen_id && x_create_gc && x_put_image && x_flush) { xconn = x_connect(NULL, NULL); if (xconn && x_has_error(xconn)) xconn = NULL; }
        }
        const char* f = getenv("ZP_FPS"); if (f && *f >= '0' && *f <= '9') { int v = 0; while (*f >= '0' && *f <= '9') v = v * 10 + (*f++ - '0'); fps_cap = v; }  // 0 = uncapped
    }
    return xconn != NULL;
}

// ---- local copies of the two window-system structures we read (their layout is fixed by the Vulkan spec) ----
typedef struct { VkStructureType sType; const void* pNext; VkFlags flags; void* dpy; unsigned long window; } ZXlibCI;
typedef struct { VkStructureType sType; const void* pNext; VkFlags flags; void* connection; uint32_t window; } ZXcbCI;
typedef VkResult (VKAPI_PTR *PFN_xlibsurf)(VkInstance, const ZXlibCI*, const VkAllocationCallbacks*, VkSurfaceKHR*);
typedef VkResult (VKAPI_PTR *PFN_xcbsurf)(VkInstance, const ZXcbCI*, const VkAllocationCallbacks*, VkSurfaceKHR*);

// ---- bookkeeping (one entry per instance / device / surface / swapchain; a handful at most) ----
typedef struct { void* key; PFN_vkGetInstanceProcAddr gipa; PFN_vkCreateDevice createDevice; PFN_vkGetPhysicalDeviceMemoryProperties memProps; PFN_xlibsurf xlib; PFN_xcbsurf xcb; } Inst;
typedef struct {
    void* key; VkDevice dev; VkPhysicalDevice phys; PFN_vkGetDeviceProcAddr gdpa;
    PFN_vkGetDeviceQueue getQueue; PFN_vkCreateSwapchainKHR createSc; PFN_vkDestroySwapchainKHR destroySc; PFN_vkGetSwapchainImagesKHR getImages; PFN_vkQueuePresentKHR present;
    PFN_vkCreateBuffer createBuf; PFN_vkDestroyBuffer destroyBuf; PFN_vkGetBufferMemoryRequirements bufReq; PFN_vkAllocateMemory alloc; PFN_vkFreeMemory freeMem;
    PFN_vkBindBufferMemory bind; PFN_vkMapMemory map; PFN_vkCreateCommandPool createPool; PFN_vkAllocateCommandBuffers allocCb; PFN_vkBeginCommandBuffer begin;
    PFN_vkEndCommandBuffer end; PFN_vkCmdPipelineBarrier barrier; PFN_vkCmdCopyImageToBuffer copy; PFN_vkQueueSubmit submit; PFN_vkCreateFence createFence;
    PFN_vkDestroyFence destroyFence; PFN_vkWaitForFences wait; PFN_vkResetFences resetFences; PFN_vkResetCommandBuffer resetCb;
    VkPhysicalDeviceMemoryProperties mp;
} Dev;
typedef struct { VkQueue q; uint32_t fam; } Que;
typedef struct { VkSurfaceKHR s; uint32_t win; } Surf;
typedef struct {
    VkSwapchainKHR sc; Dev* d; uint32_t win, gc; VkFormat fmt; uint32_t w, h; int bgr, ok;
    VkImage* img; uint32_t n; VkBuffer buf; VkDeviceMemory mem; uint8_t* ptr; VkCommandPool pool; uint32_t poolFam; VkCommandBuffer cb; VkFence fence;
    struct timespec last;
} Sc;
static Inst insts[MAXN]; static Dev devs[MAXN]; static Que ques[MAXN * 4]; static Surf surfs[MAXN * 4]; static Sc scs[MAXN * 2];
static int ni, nd, nq, nsf;
static void* key_of(const void* h) { return h ? *(void* const*)h : NULL; }
static Inst* inst_of(const void* h) { for (int i = 0; i < ni; i++) if (insts[i].key == key_of(h)) return &insts[i]; return NULL; }
static Dev* dev_of(const void* h) { for (int i = 0; i < nd; i++) if (devs[i].key == key_of(h)) return &devs[i]; return NULL; }
static Sc* sc_of(VkSwapchainKHR s) { for (int i = 0; i < MAXN * 2; i++) if (scs[i].sc == s && s != VK_NULL_HANDLE) return &scs[i]; return NULL; }

// ---------------- chain plumbing ----------------
static VkLayerInstanceCreateInfo* find_ici(const VkInstanceCreateInfo* ci) {
    for (const VkBaseInStructure* p = ci->pNext; p; p = p->pNext) { const VkLayerInstanceCreateInfo* l = (const VkLayerInstanceCreateInfo*)p; if (p->sType == VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO && l->function == VK_LAYER_LINK_INFO) return (VkLayerInstanceCreateInfo*)l; }
    return NULL;
}
static VkLayerDeviceCreateInfo* find_dci(const VkDeviceCreateInfo* ci) {
    for (const VkBaseInStructure* p = ci->pNext; p; p = p->pNext) { const VkLayerDeviceCreateInfo* l = (const VkLayerDeviceCreateInfo*)p; if (p->sType == VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO && l->function == VK_LAYER_LINK_INFO) return (VkLayerDeviceCreateInfo*)l; }
    return NULL;
}

static VKAPI_ATTR VkResult VKAPI_CALL zp_CreateInstance(const VkInstanceCreateInfo* ci, const VkAllocationCallbacks* a, VkInstance* out) {
    VkLayerInstanceCreateInfo* li = find_ici(ci); if (!li || !li->u.pLayerInfo) return VK_ERROR_INITIALIZATION_FAILED;
    PFN_vkGetInstanceProcAddr gipa = li->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    PFN_vkCreateInstance next = (PFN_vkCreateInstance)gipa(NULL, "vkCreateInstance"); if (!next) return VK_ERROR_INITIALIZATION_FAILED;
    li->u.pLayerInfo = li->u.pLayerInfo->pNext;
    VkResult r = next(ci, a, out); if (r != VK_SUCCESS) return r;
    pthread_mutex_lock(&lk);
    if (ni < MAXN) {
        Inst* I = &insts[ni++]; I->key = key_of(*out); I->gipa = gipa;
        I->createDevice = (PFN_vkCreateDevice)gipa(*out, "vkCreateDevice");
        I->memProps = (PFN_vkGetPhysicalDeviceMemoryProperties)gipa(*out, "vkGetPhysicalDeviceMemoryProperties");
        I->xlib = (PFN_xlibsurf)gipa(*out, "vkCreateXlibSurfaceKHR"); I->xcb = (PFN_xcbsurf)gipa(*out, "vkCreateXcbSurfaceKHR");
    }
    pthread_mutex_unlock(&lk);
    return VK_SUCCESS;
}

static VKAPI_ATTR VkResult VKAPI_CALL zp_CreateXlibSurfaceKHR(VkInstance i, const ZXlibCI* ci, const VkAllocationCallbacks* a, VkSurfaceKHR* out) {
    Inst* I = inst_of(i); if (!I || !I->xlib) return VK_ERROR_INITIALIZATION_FAILED;
    VkResult r = I->xlib(i, ci, a, out);
    if (r == VK_SUCCESS) { pthread_mutex_lock(&lk); if (nsf < MAXN * 4) { surfs[nsf].s = *out; surfs[nsf].win = (uint32_t)ci->window; nsf++; } pthread_mutex_unlock(&lk); }
    return r;
}
static VKAPI_ATTR VkResult VKAPI_CALL zp_CreateXcbSurfaceKHR(VkInstance i, const ZXcbCI* ci, const VkAllocationCallbacks* a, VkSurfaceKHR* out) {
    Inst* I = inst_of(i); if (!I || !I->xcb) return VK_ERROR_INITIALIZATION_FAILED;
    VkResult r = I->xcb(i, ci, a, out);
    if (r == VK_SUCCESS) { pthread_mutex_lock(&lk); if (nsf < MAXN * 4) { surfs[nsf].s = *out; surfs[nsf].win = ci->window; nsf++; } pthread_mutex_unlock(&lk); }
    return r;
}

#define LOAD(field, name) d->field = (PFN_##name)d->gdpa(*out, #name)
static VKAPI_ATTR VkResult VKAPI_CALL zp_CreateDevice(VkPhysicalDevice pd, const VkDeviceCreateInfo* ci, const VkAllocationCallbacks* a, VkDevice* out) {
    VkLayerDeviceCreateInfo* li = find_dci(ci); Inst* I = inst_of(pd); if (!li || !li->u.pLayerInfo || !I) return VK_ERROR_INITIALIZATION_FAILED;
    PFN_vkGetInstanceProcAddr gipa = li->u.pLayerInfo->pfnNextGetInstanceProcAddr; PFN_vkGetDeviceProcAddr gdpa = li->u.pLayerInfo->pfnNextGetDeviceProcAddr;
    PFN_vkCreateDevice next = (PFN_vkCreateDevice)gipa(NULL, "vkCreateDevice"); if (!next) next = I->createDevice;
    li->u.pLayerInfo = li->u.pLayerInfo->pNext;
    VkResult r = next(pd, ci, a, out); if (r != VK_SUCCESS) return r;
    pthread_mutex_lock(&lk);
    if (nd < MAXN) {
        Dev* d = &devs[nd++]; memset(d, 0, sizeof *d); d->key = key_of(*out); d->dev = *out; d->phys = pd; d->gdpa = gdpa;
        LOAD(getQueue, vkGetDeviceQueue); LOAD(createSc, vkCreateSwapchainKHR); LOAD(destroySc, vkDestroySwapchainKHR); LOAD(getImages, vkGetSwapchainImagesKHR);
        LOAD(present, vkQueuePresentKHR); LOAD(createBuf, vkCreateBuffer); LOAD(destroyBuf, vkDestroyBuffer); LOAD(bufReq, vkGetBufferMemoryRequirements);
        LOAD(alloc, vkAllocateMemory); LOAD(freeMem, vkFreeMemory); LOAD(bind, vkBindBufferMemory); LOAD(map, vkMapMemory); LOAD(createPool, vkCreateCommandPool);
        LOAD(allocCb, vkAllocateCommandBuffers); LOAD(begin, vkBeginCommandBuffer); LOAD(end, vkEndCommandBuffer); LOAD(barrier, vkCmdPipelineBarrier);
        LOAD(copy, vkCmdCopyImageToBuffer); LOAD(submit, vkQueueSubmit); LOAD(createFence, vkCreateFence); LOAD(destroyFence, vkDestroyFence);
        LOAD(wait, vkWaitForFences); LOAD(resetFences, vkResetFences); LOAD(resetCb, vkResetCommandBuffer);
        if (I->memProps) I->memProps(pd, &d->mp);
    }
    pthread_mutex_unlock(&lk);
    return VK_SUCCESS;
}

static VKAPI_ATTR void VKAPI_CALL zp_GetDeviceQueue(VkDevice dev, uint32_t fam, uint32_t idx, VkQueue* q) {
    Dev* d = dev_of(dev); if (!d) return; d->getQueue(dev, fam, idx, q);
    pthread_mutex_lock(&lk); if (nq < MAXN * 4 && *q) { ques[nq].q = *q; ques[nq].fam = fam; nq++; } pthread_mutex_unlock(&lk);
}

// ---------------- swapchains ----------------
static uint32_t queue_family(VkQueue q) { for (int i = 0; i < nq; i++) if (ques[i].q == q) return ques[i].fam; return 0; }

static void sc_free(Sc* s) {
    if (!s->sc) return;
    Dev* d = s->d;
    if (s->fence) d->destroyFence(d->dev, s->fence, NULL);
    if (s->buf) d->destroyBuf(d->dev, s->buf, NULL);
    if (s->mem) d->freeMem(d->dev, s->mem, NULL);
    free(s->img); memset(s, 0, sizeof *s);
}

static VKAPI_ATTR VkResult VKAPI_CALL zp_CreateSwapchainKHR(VkDevice dev, const VkSwapchainCreateInfoKHR* ci, const VkAllocationCallbacks* a, VkSwapchainKHR* out) {
    Dev* d = dev_of(dev); if (!d) return VK_ERROR_INITIALIZATION_FAILED;
    VkSwapchainCreateInfoKHR c = *ci; c.imageUsage |= VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    VkResult r = d->createSc(dev, &c, a, out); if (r != VK_SUCCESS) return r;
    pthread_mutex_lock(&lk);
    Sc* s = NULL; for (int i = 0; i < MAXN * 2; i++) if (!scs[i].sc) { s = &scs[i]; break; }
    if (s) {
        memset(s, 0, sizeof *s); s->sc = *out; s->d = d; s->fmt = ci->imageFormat; s->w = ci->imageExtent.width; s->h = ci->imageExtent.height;
        for (int i = 0; i < nsf; i++) if (surfs[i].s == ci->surface) s->win = surfs[i].win;
        s->bgr = (s->fmt == VK_FORMAT_B8G8R8A8_UNORM || s->fmt == VK_FORMAT_B8G8R8A8_SRGB);
        int rgb = (s->fmt == VK_FORMAT_R8G8B8A8_UNORM || s->fmt == VK_FORMAT_R8G8B8A8_SRGB);
        uint32_t n = 0; d->getImages(dev, *out, &n, NULL);
        s->img = calloc(n ? n : 1, sizeof(VkImage)); s->n = n; if (n) d->getImages(dev, *out, &n, s->img);
        // host buffer the frame is copied into
        VkBufferCreateInfo bi = { VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, NULL, 0, (VkDeviceSize)s->w * s->h * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT, VK_SHARING_MODE_EXCLUSIVE, 0, NULL };
        VkMemoryRequirements mr; int good = (s->win != 0 && (s->bgr || rgb) && s->img && d->createBuf(dev, &bi, NULL, &s->buf) == VK_SUCCESS);
        if (good) {
            d->bufReq(dev, s->buf, &mr); int mt = -1;
            const VkMemoryPropertyFlags want[2] = { VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT | VK_MEMORY_PROPERTY_HOST_CACHED_BIT, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT };
            for (int w = 0; w < 2 && mt < 0; w++) for (uint32_t t = 0; t < d->mp.memoryTypeCount; t++) if ((mr.memoryTypeBits >> t & 1) && (d->mp.memoryTypes[t].propertyFlags & want[w]) == want[w]) { mt = (int)t; break; }
            VkMemoryAllocateInfo mi = { VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, NULL, mr.size, (uint32_t)(mt < 0 ? 0 : mt) };
            void* p = NULL;
            good = mt >= 0 && d->alloc(dev, &mi, NULL, &s->mem) == VK_SUCCESS && d->bind(dev, s->buf, s->mem, 0) == VK_SUCCESS && d->map(dev, s->mem, 0, VK_WHOLE_SIZE, 0, &p) == VK_SUCCESS;
            s->ptr = p;
        }
        VkFenceCreateInfo fi = { VK_STRUCTURE_TYPE_FENCE_CREATE_INFO, NULL, 0 };
        good = good && d->createFence(dev, &fi, NULL, &s->fence) == VK_SUCCESS;
        zlog("swapchain %p win=0x%x %ux%u fmt=%d images=%u buffer_ok=%d", (void*)(uintptr_t)s->sc, s->win, s->w, s->h, (int)s->fmt, s->n, good);
        s->ok = good; if (good && x_ready()) { s->gc = x_gen_id(xconn); x_create_gc(xconn, s->gc, s->win, 0, NULL); } else s->ok = 0;
        zlog("  x connection=%p ok=%d", xconn, s->ok);
        s->bgr = s->bgr ? 1 : 0;
    }
    pthread_mutex_unlock(&lk);
    return VK_SUCCESS;
}

static VKAPI_ATTR void VKAPI_CALL zp_DestroySwapchainKHR(VkDevice dev, VkSwapchainKHR sc, const VkAllocationCallbacks* a) {
    Dev* d = dev_of(dev); if (!d) return;
    pthread_mutex_lock(&lk); Sc* s = sc_of(sc); if (s) sc_free(s); pthread_mutex_unlock(&lk);
    d->destroySc(dev, sc, a);
}

static void sleep_to_cap(Sc* s) {
    if (fps_cap <= 0) return;
    struct timespec now; clock_gettime(CLOCK_MONOTONIC, &now);
    int64_t el = (now.tv_sec - s->last.tv_sec) * 1000000000LL + (now.tv_nsec - s->last.tv_nsec), per = 1000000000LL / fps_cap;
    if (s->last.tv_sec && el < per) { struct timespec t = { 0, (long)(per - el) }; nanosleep(&t, NULL); clock_gettime(CLOCK_MONOTONIC, &now); }
    s->last = now;
}

// Copies the presented image to the host buffer (waiting for the frame's semaphores) and draws it into the window.
static int blit_frame(Dev* d, Sc* s, VkQueue q, VkImage img, const VkSemaphore* waits, uint32_t nwaits) {
    uint32_t fam = queue_family(q);
    if (!s->pool || s->poolFam != fam) {
        VkCommandPoolCreateInfo pi = { VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, NULL, VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT, fam };
        if (d->createPool(d->dev, &pi, NULL, &s->pool) != VK_SUCCESS) return 0;
        VkCommandBufferAllocateInfo ai = { VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, NULL, s->pool, VK_COMMAND_BUFFER_LEVEL_PRIMARY, 1 };
        if (d->allocCb(d->dev, &ai, &s->cb) != VK_SUCCESS) return 0;
        *(void**)s->cb = *(void**)d->dev;  // a command buffer made below the loader needs the loader's dispatch pointer
        s->poolFam = fam;
    }
    d->resetCb(s->cb, 0);
    VkCommandBufferBeginInfo bi = { VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, NULL, VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT, NULL };
    d->begin(s->cb, &bi);
    VkImageMemoryBarrier b = { VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, NULL, VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, img, { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 } };
    d->barrier(s->cb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, NULL, 0, NULL, 1, &b);
    VkBufferImageCopy cp = { 0, 0, 0, { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 }, { 0, 0, 0 }, { s->w, s->h, 1 } };
    d->copy(s->cb, img, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, s->buf, 1, &cp);
    b.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT; b.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT; b.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL; b.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    d->barrier(s->cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, NULL, 0, NULL, 1, &b);
    d->end(s->cb);
    VkPipelineStageFlags stages[16]; uint32_t nw = nwaits > 16 ? 16 : nwaits; for (uint32_t i = 0; i < nw; i++) stages[i] = VK_PIPELINE_STAGE_TRANSFER_BIT;
    VkSubmitInfo si = { VK_STRUCTURE_TYPE_SUBMIT_INFO, NULL, nw, waits, stages, 1, &s->cb, 0, NULL };
    d->resetFences(d->dev, 1, &s->fence);
    if (d->submit(q, 1, &si, s->fence) != VK_SUCCESS) return 0;
    d->wait(d->dev, 1, &s->fence, VK_TRUE, UINT64_MAX);
    // draw in strips: a plain X request is limited to about 256 KB
    uint32_t stride = s->w * 4, rows = 250000 / stride; if (rows < 1) rows = 1;
    if (!s->bgr) for (uint32_t i = 0; i < s->w * s->h; i++) { uint8_t t = s->ptr[i * 4]; s->ptr[i * 4] = s->ptr[i * 4 + 2]; s->ptr[i * 4 + 2] = t; }  // RGBA -> BGRA
    for (uint32_t y = 0; y < s->h; y += rows) { uint32_t n = s->h - y < rows ? s->h - y : rows; x_put_image(xconn, 2, s->win, s->gc, (uint16_t)s->w, (uint16_t)n, 0, (int16_t)y, 0, 24, n * stride, s->ptr + (size_t)y * stride); }
    x_flush(xconn);
    return 1;
}

static VKAPI_ATTR VkResult VKAPI_CALL zp_QueuePresentKHR(VkQueue q, const VkPresentInfoKHR* pi) {
    Dev* d = dev_of(q); if (!d) return VK_ERROR_DEVICE_LOST;
    VkPresentInfoKHR p = *pi; int consumed = 0;
    for (uint32_t i = 0; i < pi->swapchainCount; i++) {
        pthread_mutex_lock(&lk); Sc* s = sc_of(pi->pSwapchains[i]); int ok = s && s->ok && pi->pImageIndices[i] < s->n; pthread_mutex_unlock(&lk);
        if (!ok) continue;
        sleep_to_cap(s);
        int okb = blit_frame(d, s, q, s->img[pi->pImageIndices[i]], consumed ? NULL : pi->pWaitSemaphores, consumed ? 0 : pi->waitSemaphoreCount);
        static int nlog; if (nlog < 5) { nlog++; zlog("present image %u blit=%d first_pixel=%02x%02x%02x%02x", pi->pImageIndices[i], okb, s->ptr[0], s->ptr[1], s->ptr[2], s->ptr[3]); }
        if (okb) consumed = 1;
    }
    if (consumed) { p.waitSemaphoreCount = 0; p.pWaitSemaphores = NULL; }  // the semaphores were waited on by the copy
    return d->present(q, &p);
}

// ---------------- entry points ----------------
static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL zp_GetDeviceProcAddr(VkDevice dev, const char* n);
static PFN_vkVoidFunction ours(const char* n, int deviceLevel) {
    if (!strcmp(n, "vkGetDeviceQueue")) return (PFN_vkVoidFunction)zp_GetDeviceQueue;
    if (!strcmp(n, "vkCreateSwapchainKHR")) return (PFN_vkVoidFunction)zp_CreateSwapchainKHR;
    if (!strcmp(n, "vkDestroySwapchainKHR")) return (PFN_vkVoidFunction)zp_DestroySwapchainKHR;
    if (!strcmp(n, "vkQueuePresentKHR")) return (PFN_vkVoidFunction)zp_QueuePresentKHR;
    if (!deviceLevel) {
        if (!strcmp(n, "vkCreateInstance")) return (PFN_vkVoidFunction)zp_CreateInstance;
        if (!strcmp(n, "vkCreateDevice")) return (PFN_vkVoidFunction)zp_CreateDevice;
        if (!strcmp(n, "vkCreateXlibSurfaceKHR")) return (PFN_vkVoidFunction)zp_CreateXlibSurfaceKHR;
        if (!strcmp(n, "vkCreateXcbSurfaceKHR")) return (PFN_vkVoidFunction)zp_CreateXcbSurfaceKHR;
        if (!strcmp(n, "vkGetDeviceProcAddr")) return (PFN_vkVoidFunction)zp_GetDeviceProcAddr;
    }
    return NULL;
}
static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL zp_GetDeviceProcAddr(VkDevice dev, const char* n) {
    PFN_vkVoidFunction f = ours(n, 1); if (f) return f;
    Dev* d = dev_of(dev); return d && d->gdpa ? d->gdpa(dev, n) : NULL;
}
static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL zp_GetInstanceProcAddr(VkInstance inst, const char* n) {
    PFN_vkVoidFunction f = ours(n, 0); if (f) return f;
    Inst* I = inst_of(inst); return I && I->gipa ? I->gipa(inst, n) : NULL;
}

EXPORT VKAPI_ATTR VkResult VKAPI_CALL zp_negotiate(VkNegotiateLayerInterface* s) {
    if (s->sType != LAYER_NEGOTIATE_INTERFACE_STRUCT) return VK_ERROR_INITIALIZATION_FAILED;
    if (s->loaderLayerInterfaceVersion > 2) s->loaderLayerInterfaceVersion = 2;
    s->pfnGetInstanceProcAddr = zp_GetInstanceProcAddr; s->pfnGetDeviceProcAddr = zp_GetDeviceProcAddr; s->pfnGetPhysicalDeviceProcAddr = NULL;
    return VK_SUCCESS;
}
