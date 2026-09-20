#include <setjmp.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

/* The jump is wholly inside one native downcall, never across a JVM frame.
 * This contains Zig panics, not signals, exit(), or arbitrary memory damage.
 * Zig defers are not unwound; callers must treat failed native state as suspect.
 */
static _Thread_local jmp_buf *active_boundary;
static _Thread_local char panic_message[4096];
static _Thread_local uintptr_t panic_address;

int aguafria_guard_call(void (*invoke)(void *), void *context) {
    jmp_buf boundary;
    jmp_buf *previous = active_boundary;
    active_boundary = &boundary;
    panic_message[0] = 0;
    panic_address = 0;
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
    longjmp(*active_boundary, 1);
}

const char *aguafria_guard_message(void) {
    return panic_message[0] ? panic_message : NULL;
}

uintptr_t aguafria_guard_address(void) { return panic_address; }
