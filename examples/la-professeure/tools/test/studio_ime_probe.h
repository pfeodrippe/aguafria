#ifndef LP_STUDIO_IME_PROBE_H
#define LP_STUDIO_IME_PROBE_H
#include <stdbool.h>
/* Explicit QA only: stage/commit marked text on the actual GLFW content view. */
bool lp_studio_ime_probe_mark(void *cocoa_window, const char *text);
bool lp_studio_ime_probe_commit(void *cocoa_window, const char *text);
/* Inspect actual NSTextInputClient substring/geometry and render its native view. */
bool lp_studio_ime_probe_presentation(void *cocoa_window, const char *text,
                                    double x, double y, const char *png_path);
bool lp_studio_ime_probe_key(void *cocoa_window, unsigned short code, const char *text);
#endif
