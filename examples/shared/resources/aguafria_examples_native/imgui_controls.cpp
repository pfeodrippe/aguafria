#include "imgui_controls.h"
#include "imgui.h"

// Link to the existing ImGui library, not another copy of imgui.cpp. This lets
// Aguafria add controls during development without replacing the live context.
extern "C" unsigned char aguafria_ui_window_begin(const char* title, float width, float height) {
    ImGui::SetNextWindowSize(ImVec2(width, height), ImGuiCond_FirstUseEver);
    ImGui::SetNextWindowPos(ImVec2(40, 180), ImGuiCond_FirstUseEver);
    ImGui::SetNextWindowBgAlpha(1.0f);
    return ImGui::Begin(title);
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

extern "C" void aguafria_ui_separator(void) { ImGui::Separator(); }

extern "C" void aguafria_ui_wrapped_text(const char* text, size_t length, float r, float g, float b) {
    ImGui::PushStyleColor(ImGuiCol_Text, ImVec4(r, g, b, 1));
    ImGui::PushTextWrapPos(0.0f);
    ImGui::TextUnformatted(text, text + length); // Bounded model bytes, never a printf format.
    ImGui::PopTextWrapPos();
    ImGui::PopStyleColor();
}
