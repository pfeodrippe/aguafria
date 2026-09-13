#ifndef LP_STUDIO_GESTURES_H
#define LP_STUDIO_GESTURES_H

#include <stdbool.h>

/* GLFW's Cocoa accessor has pointer ABI; keep AppKit out of Zig's C importer. */
void *glfwGetCocoaWindow(void *window);

typedef struct {
    double magnification;
    double x;
    double y;
} lp_studio_pinch;

bool lp_studio_gestures_attach(void *cocoa_window);
void lp_studio_gestures_detach(void);
bool lp_studio_gestures_poll(lp_studio_pinch *event);
/* Same bounded input path used by AppKit; useful for native/API interaction QA. */
bool lp_studio_gestures_submit(void *cocoa_window, double magnification, double x, double y);

/* GLFW calls its key callback before interpretKeyEvents. Leave composition
 * commands with AppKit until the marked text has been committed/cancelled. */
bool lp_studio_ime_active(void *cocoa_window);
void lp_studio_ime_cancel(void *cocoa_window);
/* Logical top-left caret coordinates, not framebuffer pixels. No activation. */
bool lp_studio_ime_focus(void *cocoa_window, double x, double y, double height);

/* Read-only master-output mute by stable Core Audio UID: -1 unknown, 0 off, 1 on. */
int lp_studio_output_mute(const char *device_uid);

#endif
