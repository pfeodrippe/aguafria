#ifndef AGUAFRIA_IMGUI_CONTROLS_H
#define AGUAFRIA_IMGUI_CONTROLS_H
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Generic immediate-mode controls. Borrow the existing ImGui context/frame;
   this adapter owns no windows, renderer, game state or ImGui context. */
unsigned char aguafria_ui_window_begin(const char* title, float width, float height);
void aguafria_ui_window_end(void);
unsigned char aguafria_ui_scroll_begin(const char* id, unsigned char follow_top);
void aguafria_ui_scroll_end(void);
unsigned char aguafria_ui_captures_mouse(void);
void aguafria_ui_collapse_window_once(const char* title);
unsigned char aguafria_ui_button(const char* label);
unsigned char aguafria_ui_checkbox(const char* label, unsigned char* value);
void aguafria_ui_same_line(void);
void aguafria_ui_separator(void);
void aguafria_ui_wrapped_text(const char* text, size_t length, float r, float g, float b);

#ifdef __cplusplus
}
#endif
#endif
