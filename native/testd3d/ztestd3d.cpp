// A tiny Direct3D 11 program for testing the graphics path (DXVK -> Vulkan driver -> X screen): opens a window, clears it to a
// moving color and presents. Writes what it found (adapter name, frame count, speed) to E:\ztestd3d.log.
#include <windows.h>
#include <d3d11.h>
#include <dxgi.h>
#include <cstdio>
#include <cmath>
static FILE* lg;
static LRESULT CALLBACK proc(HWND h, UINT m, WPARAM w, LPARAM l) { if (m == WM_DESTROY) PostQuitMessage(0); return DefWindowProcA(h, m, w, l); }
int WINAPI WinMain(HINSTANCE hi, HINSTANCE, LPSTR, int) {
    lg = fopen("E:\\ztestd3d.log", "w");
    WNDCLASSA wc{}; wc.lpfnWndProc = proc; wc.hInstance = hi; wc.lpszClassName = "ztestd3d"; wc.hCursor = LoadCursor(nullptr, IDC_ARROW); RegisterClassA(&wc);
    HWND hw = CreateWindowA("ztestd3d", "Direct3D 11 test", WS_OVERLAPPEDWINDOW | WS_VISIBLE, 40, 40, 640, 400, nullptr, nullptr, hi, nullptr);
    DXGI_SWAP_CHAIN_DESC sd{}; sd.BufferCount = 2; sd.BufferDesc.Width = 640; sd.BufferDesc.Height = 400; sd.BufferDesc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    sd.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT; sd.OutputWindow = hw; sd.SampleDesc.Count = 1; sd.Windowed = TRUE; sd.SwapEffect = DXGI_SWAP_EFFECT_DISCARD;
    ID3D11Device* dev = nullptr; ID3D11DeviceContext* ctx = nullptr; IDXGISwapChain* sc = nullptr; D3D_FEATURE_LEVEL fl;
    HRESULT hr = D3D11CreateDeviceAndSwapChain(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, 0, nullptr, 0, D3D11_SDK_VERSION, &sd, &sc, &dev, &fl, &ctx);
    fprintf(lg, "D3D11CreateDeviceAndSwapChain hr=0x%08lx feature_level=0x%x\n", (unsigned long)hr, (unsigned)fl); fflush(lg);
    if (FAILED(hr)) return 1;
    IDXGIDevice* dd; IDXGIAdapter* ad; dev->QueryInterface(__uuidof(IDXGIDevice), (void**)&dd); dd->GetAdapter(&ad); DXGI_ADAPTER_DESC desc; ad->GetDesc(&desc);
    fprintf(lg, "adapter: %ls vram=%llu MB\n", desc.Description, (unsigned long long)(desc.DedicatedVideoMemory >> 20)); fflush(lg);
    ID3D11Texture2D* bb; sc->GetBuffer(0, __uuidof(ID3D11Texture2D), (void**)&bb); ID3D11RenderTargetView* rtv; dev->CreateRenderTargetView(bb, nullptr, &rtv);
    {   // GPU-only check, no presentation: clear, copy to a CPU-readable texture and read a pixel back.
        float red[4] = { 1, 0.5f, 0.25f, 1 }; ctx->OMSetRenderTargets(1, &rtv, nullptr); ctx->ClearRenderTargetView(rtv, red);
        D3D11_TEXTURE2D_DESC td; bb->GetDesc(&td); td.Usage = D3D11_USAGE_STAGING; td.BindFlags = 0; td.CPUAccessFlags = D3D11_CPU_ACCESS_READ; td.MiscFlags = 0;
        ID3D11Texture2D* st = nullptr; HRESULT h2 = dev->CreateTexture2D(&td, nullptr, &st);
        fprintf(lg, "staging hr=0x%08lx\n", (unsigned long)h2); fflush(lg);
        ctx->CopyResource(st, bb); ctx->Flush(); fprintf(lg, "flushed\n"); fflush(lg);
        D3D11_MAPPED_SUBRESOURCE mp; h2 = ctx->Map(st, 0, D3D11_MAP_READ, 0, &mp);
        if (SUCCEEDED(h2)) { unsigned char* px = (unsigned char*)mp.pData; fprintf(lg, "GPU readback pixel RGBA = %d %d %d %d (expect ~255 128 64 255)\n", px[0], px[1], px[2], px[3]); ctx->Unmap(st, 0); }
        else fprintf(lg, "Map hr=0x%08lx\n", (unsigned long)h2);
        fflush(lg);
    }
    fprintf(lg, "presenting\n"); fflush(lg);
    DWORD t0 = GetTickCount(); int frames = 0; MSG msg;
    while (GetTickCount() - t0 < 20000) {
        while (PeekMessage(&msg, nullptr, 0, 0, PM_REMOVE)) { if (msg.message == WM_QUIT) goto done; TranslateMessage(&msg); DispatchMessage(&msg); }
        float t = (GetTickCount() - t0) / 1000.f; float c[4] = { 0.5f + 0.5f * sinf(t), 0.5f + 0.5f * sinf(t * 1.7f), 0.5f + 0.5f * sinf(t * 2.3f), 1 };
        ctx->OMSetRenderTargets(1, &rtv, nullptr); ctx->ClearRenderTargetView(rtv, c); sc->Present(1, 0); frames++; if (frames <= 3) { fprintf(lg, "present %d done\n", frames); fflush(lg); }
    }
done:
    fprintf(lg, "frames=%d in %lu ms (%.1f fps)\n", frames, (unsigned long)(GetTickCount() - t0), frames * 1000.0 / (GetTickCount() - t0)); fclose(lg);
    return 0;
}
