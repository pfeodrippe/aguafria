#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <setjmp.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#if defined(__APPLE__) || defined(__GLIBC__)
#include <dlfcn.h>
#include <execinfo.h>
#define AGUAFRIA_NATIVE_BACKTRACE 1
#endif

/* The jump is wholly inside one native downcall, never across a JVM frame.
 * This contains Zig panics, not signals, exit(), or arbitrary memory damage.
 * Zig defers are not unwound; callers must treat failed native state as suspect.
 */
static _Thread_local jmp_buf *active_boundary;
static _Thread_local char panic_message[4096];
static _Thread_local uintptr_t panic_address;
struct aguafria_panic_frame {
    void *address;
    void *image_base;
    const char *image;
};
static _Thread_local struct aguafria_panic_frame panic_frames[64];
static _Thread_local size_t panic_frame_count;

int aguafria_guard_call(void (*invoke)(void *), void *context) {
    jmp_buf boundary;
    jmp_buf *previous = active_boundary;
    active_boundary = &boundary;
    panic_message[0] = 0;
    panic_address = 0;
    panic_frame_count = 0;
    if (setjmp(boundary) == 0) {
        invoke(context);
        active_boundary = previous;
        return 0;
    }
    active_boundary = previous;
    return 1;
}

void aguafria_guard_panic(const char *message, size_t length, uintptr_t address) {
    if (!active_boundary) return;
    size_t count = length < sizeof(panic_message) - 1 ? length : sizeof(panic_message) - 1;
    memcpy(panic_message, message, count);
    panic_message[count] = 0;
    panic_address = address;
#ifdef AGUAFRIA_NATIVE_BACKTRACE
    /* Capture while the failing native stack still exists. No signal handler
     * is involved; symbolication runs later, after returning to the JVM. */
    void *addresses[64];
    int frames = backtrace(addresses, 64);
    for (int i = 0; i < frames; ++i) {
        Dl_info image = {0};
        void *pc = (void *)((uintptr_t)addresses[i] - 1);
        if (!dladdr(pc, &image) || !image.dli_fname) continue;
        panic_frames[panic_frame_count++] = (struct aguafria_panic_frame){
            pc, image.dli_fbase, image.dli_fname
        };
    }
#endif
    longjmp(*active_boundary, 1);
}

const char *aguafria_guard_message(void) {
    return panic_message[0] ? panic_message : NULL;
}

uintptr_t aguafria_guard_address(void) { return panic_address; }
size_t aguafria_guard_frame_count(void) { return panic_frame_count; }
const struct aguafria_panic_frame *aguafria_guard_frames(void) { return panic_frames; }
