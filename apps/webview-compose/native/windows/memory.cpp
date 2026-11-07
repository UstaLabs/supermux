// The low-memory watcher (D21): a thread of the glue's own, attached to the JVM once, that tells the engine (kMemory,
// page 0) once each time the system says memory is low.
#include "wv2.h"
#include "ui_thread.h"
#include <thread>

namespace wv {
void startMemoryWatch() {
    static std::once_flag once;
    std::call_once(once, [] {
        HANDLE low = CreateMemoryResourceNotification(LowMemoryResourceNotification);
        if (!low) return;
        std::thread([low] {
            UiThread::markGlueThread(L"webview2-memory");
            for (;;) {
                if (WaitForSingleObject(low, INFINITE) != WAIT_OBJECT_0) return;
                emit(0, kMemory, 0, 0, 0);
                // The notification stays signalled while memory is low: once per episode, then wait for it to clear.
                BOOL state = TRUE;
                do {
                    Sleep(10000);
                } while (QueryMemoryResourceNotification(low, &state) && state);
            }
        }).detach();
    });
}
}  // namespace wv
