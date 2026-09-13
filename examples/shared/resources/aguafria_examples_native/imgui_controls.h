#ifndef AGUAFRIA_IMGUI_CONTROLS_H
#define AGUAFRIA_IMGUI_CONTROLS_H
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Generic immediate-mode controls. Borrow the existing ImGui context/frame;
   this adapter owns no windows, renderer, game state or ImGui context. */
unsigned char aguafria_ui_window_begin(const char* title, float width, float height);
unsigned char aguafria_ui_window_begin_at(const char* title, float x, float y, float width, float height);
void aguafria_ui_window_end(void);
unsigned char aguafria_ui_scroll_begin(const char* id, unsigned char follow_top);
void aguafria_ui_scroll_end(void);
unsigned char aguafria_ui_captures_mouse(void);
void aguafria_ui_collapse_window_once(const char* title);
unsigned char aguafria_ui_button(const char* label);
unsigned char aguafria_ui_checkbox(const char* label, unsigned char* value);
unsigned char aguafria_ui_slider_double(const char* label, double* value, double minimum, double maximum, const char* format);
void aguafria_ui_progress(float fraction, const char* label);
void aguafria_ui_same_line(void);
void aguafria_ui_separator(void);
void aguafria_ui_wrapped_text(const char* text, size_t length, float r, float g, float b);

/* Flat C access to ImGui values. Application layout and actions belong in Zig. */
typedef struct AguafriaUIInput {
    float width, height, mouse_x, mouse_y, delta_x, delta_y, wheel;
    unsigned char text_input, right_drag;
} AguafriaUIInput;
void aguafria_ui_frame_begin(void);
void aguafria_ui_frame_render(unsigned long long command);
void aguafria_ui_window_title(const char* title);
void aguafria_ui_input(AguafriaUIInput* input);
unsigned char aguafria_ui_key(int key);
void aguafria_ui_style_vector(int slot, float x, float y);
void aguafria_ui_style_scalar(int slot, float value);
void aguafria_ui_style_color(int slot, float r, float g, float b, float a);
void aguafria_ui_disable_ini(void);
void aguafria_ui_panel_begin(const char* name, float x, float y, float w, float h);
void aguafria_ui_background_alpha(float alpha);
void aguafria_ui_cursor(float x, float y, unsigned char screen);
float aguafria_ui_cursor_y(void);
void aguafria_ui_cursor_y_set(float y);
void aguafria_ui_item_width(float width);
void aguafria_ui_spacing(void);
void aguafria_ui_same_line_at(float x);
void aguafria_ui_disabled_begin(unsigned char disabled);
void aguafria_ui_disabled_end(void);
void aguafria_ui_text(const char* text, size_t length, float r, float g, float b);
unsigned char aguafria_ui_button_size(const char* label, float w, float h);
unsigned char aguafria_ui_radio(const char* label, unsigned char selected);
unsigned char aguafria_ui_selectable(const char* label, unsigned char selected);
unsigned char aguafria_ui_combo(const char* label, int* value, const char* items);
unsigned char aguafria_ui_slider_int(const char* label, int* value, int low, int high, const char* format);
unsigned char aguafria_ui_slider_float(const char* label, float* value, float low, float high, const char* format);
unsigned char aguafria_ui_hovered(void);
void aguafria_ui_tooltip(const char* text);
unsigned char aguafria_ui_invisible_button(const char* label, float w, float h);
void aguafria_ui_draw_rect(float x, float y, float w, float h, unsigned int color, float rounding, float thickness, unsigned char filled);
void aguafria_ui_draw_text(float x, float y, unsigned int color, const char* text);
void aguafria_ui_draw_circle(float x, float y, float radius, unsigned int color);
void aguafria_ui_draw_bezier(float ax, float ay, float bx, float by, float cx, float cy, float dx, float dy, unsigned int color, float thickness);

#ifdef __cplusplus
}
#endif
#endif
