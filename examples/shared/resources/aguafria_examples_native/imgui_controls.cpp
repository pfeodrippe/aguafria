#include "imgui_controls.h"
#include "imgui.h"
#include "backends/imgui_impl_glfw.h"
#include "backends/imgui_impl_vulkan.h"
extern "C" void glfwSetWindowTitle(GLFWwindow*, const char*);

// Link to the existing ImGui library, not another copy of imgui.cpp. This lets
// Aguafria add controls during development without replacing the live context.
extern "C" unsigned char aguafria_ui_window_begin_at(const char* title, float x, float y, float width, float height) {
    ImGui::SetNextWindowSize(ImVec2(width, height), ImGuiCond_FirstUseEver);
    ImGui::SetNextWindowPos(ImVec2(x, y), ImGuiCond_FirstUseEver);
    ImGui::SetNextWindowBgAlpha(1.0f);
    return ImGui::Begin(title);
}

extern "C" unsigned char aguafria_ui_window_begin(const char* title, float width, float height) {
    return aguafria_ui_window_begin_at(title, 40, 180, width, height);
}

extern "C" void aguafria_ui_window_end(void) { ImGui::End(); }

extern "C" unsigned char aguafria_ui_scroll_begin(const char* id, unsigned char follow_top) {
    const bool visible = ImGui::BeginChild(id, ImVec2(0, 0));
    if (follow_top) ImGui::SetScrollY(0);
    return visible;
}

extern "C" void aguafria_ui_scroll_end(void) { ImGui::EndChild(); }

extern "C" unsigned char aguafria_ui_captures_mouse(void) {
    return ImGui::GetCurrentContext() != nullptr && ImGui::GetIO().WantCaptureMouse;
}

extern "C" void aguafria_ui_collapse_window_once(const char* title) {
    ImGui::SetWindowCollapsed(title, true, ImGuiCond_Once);
}

extern "C" unsigned char aguafria_ui_button(const char* label) {
    return ImGui::Button(label);
}

extern "C" unsigned char aguafria_ui_checkbox(const char* label, unsigned char* value) {
    bool checked = *value != 0;
    const bool changed = ImGui::Checkbox(label, &checked);
    *value = checked ? 1 : 0;
    return changed;
}

extern "C" void aguafria_ui_same_line(void) { ImGui::SameLine(); }

extern "C" unsigned char aguafria_ui_slider_double(const char* label, double* value,
                                                   double minimum, double maximum, const char* format) {
    return ImGui::SliderScalar(label, ImGuiDataType_Double, value, &minimum, &maximum,
                               format, ImGuiSliderFlags_AlwaysClamp);
}

extern "C" void aguafria_ui_progress(float fraction, const char* label) {
    ImGui::ProgressBar(fraction, ImVec2(-1, 0), label);
}

extern "C" void aguafria_ui_separator(void) { ImGui::Separator(); }

extern "C" void aguafria_ui_wrapped_text(const char* text, size_t length, float r, float g, float b) {
    ImGui::PushStyleColor(ImGuiCol_Text, ImVec4(r, g, b, 1));
    ImGui::PushTextWrapPos(0.0f);
    ImGui::TextUnformatted(text, text + length); // Bounded model bytes, never a printf format.
    ImGui::PopTextWrapPos();
    ImGui::PopStyleColor();
}

extern "C" void aguafria_ui_input(AguafriaUIInput* out) {
    const auto& io = ImGui::GetIO();
    *out = {io.DisplaySize.x, io.DisplaySize.y, io.MousePos.x, io.MousePos.y,
            io.MouseDelta.x, io.MouseDelta.y, io.MouseWheel,
            static_cast<unsigned char>(io.WantTextInput),
            static_cast<unsigned char>(ImGui::IsMouseDragging(ImGuiMouseButton_Right))};
}

// Printable ASCII plus escape=27, home=256, left=257, right=258.
extern "C" unsigned char aguafria_ui_key(int key) {
    ImGuiKey code = ImGuiKey_None;
    if (key >= 'A' && key <= 'Z') code = static_cast<ImGuiKey>(ImGuiKey_A + key - 'A');
    if (key >= '0' && key <= '9') code = static_cast<ImGuiKey>(ImGuiKey_0 + key - '0');
    if (key == ' ') code = ImGuiKey_Space;
    if (key == 27) code = ImGuiKey_Escape;
    if (key == 256) code = ImGuiKey_Home;
    if (key == 257) code = ImGuiKey_LeftArrow;
    if (key == 258) code = ImGuiKey_RightArrow;
    return code != ImGuiKey_None && ImGui::IsKeyPressed(code);
}

extern "C" void aguafria_ui_style_vector(int slot, float x, float y) {
    auto& s = ImGui::GetStyle();
    switch (slot) {
    case 0: s.WindowPadding = {x, y}; break;
    case 1: s.ItemSpacing = {x, y}; break;
    case 2: s.FramePadding = {x, y}; break;
    }
}

extern "C" void aguafria_ui_style_scalar(int slot, float value) {
    auto& s = ImGui::GetStyle();
    switch (slot) {
    case 0: s.WindowRounding = value; break;
    case 1: s.FrameRounding = value; break;
    }
}

extern "C" void aguafria_ui_style_color(int slot, float r, float g, float b, float a) {
    const ImGuiCol colors[] = {ImGuiCol_WindowBg, ImGuiCol_Text, ImGuiCol_Button,
        ImGuiCol_ButtonHovered, ImGuiCol_FrameBg, ImGuiCol_SliderGrab};
    if (slot >= 0 && slot < 6) ImGui::GetStyle().Colors[colors[slot]] = {r, g, b, a};
}

extern "C" void aguafria_ui_disable_ini(void) { ImGui::GetIO().IniFilename = nullptr; }

extern "C" void aguafria_ui_panel_begin(const char* name, float x, float y, float w, float h) {
    ImGui::SetNextWindowPos({x, y});
    ImGui::SetNextWindowSize({w, h});
    ImGui::Begin(name, nullptr, ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_NoMove | ImGuiWindowFlags_NoSavedSettings);
}

extern "C" void aguafria_ui_background_alpha(float alpha) { ImGui::SetNextWindowBgAlpha(alpha); }

extern "C" void aguafria_ui_cursor(float x, float y, unsigned char screen) {
    if (screen) ImGui::SetCursorScreenPos({x, y}); else ImGui::SetCursorPos({x, y});
}

extern "C" float aguafria_ui_cursor_y(void) { return ImGui::GetCursorPosY(); }

extern "C" void aguafria_ui_cursor_y_set(float y) { ImGui::SetCursorPosY(y); }

extern "C" void aguafria_ui_item_width(float width) { ImGui::SetNextItemWidth(width); }

extern "C" void aguafria_ui_spacing(void) { ImGui::Spacing(); }

extern "C" void aguafria_ui_same_line_at(float x) { ImGui::SameLine(x); }

extern "C" void aguafria_ui_disabled_begin(unsigned char disabled) { ImGui::BeginDisabled(disabled != 0); }

extern "C" void aguafria_ui_disabled_end(void) { ImGui::EndDisabled(); }

extern "C" void aguafria_ui_text(const char* text, size_t length, float r, float g, float b) {
    ImGui::PushStyleColor(ImGuiCol_Text, ImVec4(r, g, b, 1));
    ImGui::TextUnformatted(text, text + length);
    ImGui::PopStyleColor();
}

extern "C" unsigned char aguafria_ui_button_size(const char* label, float w, float h) { return ImGui::Button(label, {w, h}); }

extern "C" unsigned char aguafria_ui_radio(const char* label, unsigned char selected) { return ImGui::RadioButton(label, selected != 0); }

extern "C" unsigned char aguafria_ui_selectable(const char* label, unsigned char selected) { return ImGui::Selectable(label, selected != 0); }

extern "C" unsigned char aguafria_ui_combo(const char* label, int* value, const char* items) { return ImGui::Combo(label, value, items); }

extern "C" unsigned char aguafria_ui_slider_int(const char* label, int* value, int low, int high, const char* format) { return ImGui::SliderInt(label, value, low, high, format); }

extern "C" unsigned char aguafria_ui_slider_float(const char* label, float* value, float low, float high, const char* format) { return ImGui::SliderFloat(label, value, low, high, format); }

extern "C" unsigned char aguafria_ui_hovered(void) { return ImGui::IsItemHovered(); }

extern "C" void aguafria_ui_tooltip(const char* text) { ImGui::SetTooltip("%s", text); }

extern "C" unsigned char aguafria_ui_invisible_button(const char* label, float w, float h) { return ImGui::InvisibleButton(label, {w, h}); }

extern "C" void aguafria_ui_draw_rect(float x, float y, float w, float h, unsigned int color, float rounding, float thickness, unsigned char filled) {
    auto* d = ImGui::GetWindowDrawList();
    if (filled) d->AddRectFilled({x, y}, {x + w, y + h}, color, rounding);
    else d->AddRect({x, y}, {x + w, y + h}, color, rounding, 0, thickness);
}

extern "C" void aguafria_ui_draw_text(float x, float y, unsigned int color, const char* text) { ImGui::GetWindowDrawList()->AddText({x, y}, color, text); }

extern "C" void aguafria_ui_draw_circle(float x, float y, float radius, unsigned int color) { ImGui::GetWindowDrawList()->AddCircleFilled({x, y}, radius, color); }

extern "C" void aguafria_ui_draw_bezier(float ax, float ay, float bx, float by, float cx, float cy, float dx, float dy, unsigned int color, float thickness) {
    ImGui::GetWindowDrawList()->AddBezierCubic({ax, ay}, {bx, by}, {cx, cy}, {dx, dy}, color, thickness);
}

extern "C" void aguafria_ui_frame_begin(void) {
    ImGui_ImplVulkan_NewFrame();
    ImGui_ImplGlfw_NewFrame();
    ImGui::NewFrame();
}

extern "C" void aguafria_ui_frame_render(unsigned long long command) {
    ImGui::Render();
    ImGui_ImplVulkan_RenderDrawData(ImGui::GetDrawData(), reinterpret_cast<VkCommandBuffer>(command));
}

extern "C" void aguafria_ui_window_title(const char* title) {
    auto* window = static_cast<GLFWwindow*>(ImGui::GetMainViewport()->PlatformHandle);
    if (window) glfwSetWindowTitle(window, title);
}
