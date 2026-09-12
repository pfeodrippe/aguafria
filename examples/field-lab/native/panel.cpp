#include "panel.h"
#include "backends/imgui_impl_glfw.h"
#include "backends/imgui_impl_vulkan.h"
#include "imgui.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <sys/stat.h>

extern "C" void glfwSetWindowTitle(GLFWwindow *, const char *);

namespace {
struct Export {
  FILE *trajectory;
  FILE *particles;
  bool deform;
  FILE *reference = nullptr;
  FILE *cells = nullptr;
};
int refined_nodes = 0, refined_tetrahedra = 0;
int frame_capture_status = -1;
const ImVec4 teal(.24f, .84f, .72f, 1), dim(.47f, .54f, .61f, 1);
void label(const char *s) { ImGui::TextColored(dim, "%s", s); }
void panel(const char *name, float x, float y, float w, float h) {
  ImGui::SetNextWindowPos({x, y});
  ImGui::SetNextWindowSize({w, h});
  ImGui::Begin(name, nullptr,
               ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_NoMove |
                   ImGuiWindowFlags_NoSavedSettings);
}
void number(const char *name, double *v, double lo, double hi,
            const char *format) {
  ImGui::SetNextItemWidth(142);
  ImGui::SliderScalar(name, ImGuiDataType_Double, v, &lo, &hi, format,
                      ImGuiSliderFlags_AlwaysClamp);
}
void metric(const char *title, double value, const char *unit) {
  label(title);
  ImGui::Text("%.3f %s", value, unit);
  ImGui::Spacing();
}
void node(const char *title, const char *subtitle, int id, LabPanel *p, float x,
          float y) {
  auto *d = ImGui::GetWindowDrawList();
  ImVec2 a(x, y), b(x + 182, y + 66);
  bool selected = p->selected == id;
  d->AddRectFilled(a, b, IM_COL32(30, 39, 48, 255), 6);
  d->AddRect(a, b,
             selected ? IM_COL32(65, 213, 180, 255) : IM_COL32(62, 75, 86, 255),
             6, 0, selected ? 2 : 1);
  d->AddRectFilled(a, {x + 4, y + 66}, IM_COL32(65, 213, 180, 255), 3);
  d->AddText({x + 15, y + 12}, IM_COL32(224, 233, 240, 255), title);
  d->AddText({x + 15, y + 36}, IM_COL32(135, 157, 173, 255), subtitle);
  d->AddCircleFilled({x, y + 33}, 4, IM_COL32(65, 213, 180, 255));
  d->AddCircleFilled({x + 182, y + 33}, 4, IM_COL32(65, 213, 180, 255));
  ImGui::SetCursorScreenPos(a);
  if (ImGui::InvisibleButton(title, {182, 66}))
    p->selected = id;
}
} // namespace
extern "C" void lab_render_fem(unsigned long long command, LabPanel *p) {
  static bool titled = false;
  if (!titled) {
    auto *window = static_cast<GLFWwindow *>(ImGui::GetMainViewport()->PlatformHandle);
    if (window) { glfwSetWindowTitle(window, "Pitoco - AguaFria"); titled = true; }
  }
  ImGui_ImplVulkan_NewFrame();
  ImGui_ImplGlfw_NewFrame();
  ImGui::NewFrame();
  auto &io = ImGui::GetIO();
  io.IniFilename = nullptr;
  auto &style = ImGui::GetStyle();
  style.WindowPadding = {18, 16};
  style.WindowRounding = 0;
  style.FrameRounding = 4;
  style.ItemSpacing = {10, 9};
  style.FramePadding = {8, 5};
  style.Colors[ImGuiCol_WindowBg] = {.065f, .083f, .105f, 1};
  style.Colors[ImGuiCol_Text] = {.87f, .91f, .94f, 1};
  style.Colors[ImGuiCol_Button] = {.12f, .23f, .25f, 1};
  style.Colors[ImGuiCol_ButtonHovered] = {.16f, .37f, .36f, 1};
  style.Colors[ImGuiCol_FrameBg] = {.12f, .16f, .20f, 1};
  style.Colors[ImGuiCol_SliderGrab] = teal;
  float w = io.DisplaySize.x, h = io.DisplaySize.y, left = 225, right = 305,
        top = 66, bottom = 226;
  p->action = 0;
  if (ImGui::IsKeyPressed(ImGuiKey_Space) && !io.WantTextInput && !p->baking)
    p->paused = !p->paused;
  if (ImGui::IsKeyPressed(ImGuiKey_R) && !io.WantTextInput)
    p->action = 1;
  if (!io.WantTextInput) {
    if (ImGui::IsKeyPressed(ImGuiKey_1) || ImGui::IsKeyPressed(ImGuiKey_3)) {
      p->mode = ImGui::IsKeyPressed(ImGuiKey_3) ? 3 : 1;
      p->action = 5;
    }
    if (ImGui::IsKeyPressed(ImGuiKey_D)) {
      p->deform = (p->deform + 1) % 3;
      p->action = 6;
    }
    if (ImGui::IsKeyPressed(ImGuiKey_Escape) && p->baking) p->action = 8;
    if (ImGui::IsKeyPressed(ImGuiKey_E) && !p->baking) p->action = 4;
    if (!p->baking && (ImGui::IsKeyPressed(ImGuiKey_LeftArrow) ||
                       ImGui::IsKeyPressed(ImGuiKey_RightArrow))) {
      int direction = ImGui::IsKeyPressed(ImGuiKey_RightArrow) ? 1 : -1;
      p->cursor = std::clamp(p->cursor + direction, 0, std::max(0, p->count - 1));
      p->paused = 1;
      p->action = 3;
    }
    if (!p->baking && ImGui::IsKeyPressed(ImGuiKey_Home)) {
      p->cursor = 0;
      p->paused = 1;
      p->action = 3;
    }
  }
  if (io.MousePos.x > left && io.MousePos.x < w - right &&
      io.MousePos.y > top && io.MousePos.y < h - bottom) {
    if (ImGui::IsMouseDragging(ImGuiMouseButton_Right)) {
      p->yaw -= io.MouseDelta.x * .006f;
      p->pitch = std::clamp(p->pitch + io.MouseDelta.y * .005f, .08f, 1.3f);
    }
    p->distance = std::clamp(p->distance - io.MouseWheel * .6f, 4.f, 24.f);
  }
  panel("brand", 0, 0, w, top);
  ImGui::TextColored(teal, "P / T");
  ImGui::SameLine();
  ImGui::Text("PITOCO");
  ImGui::SameLine(235);
  label("/ sphere-impact / experiment_001");
  ImGui::SameLine(w - 278);
  ImGui::TextColored(teal, "FLECS");
  ImGui::SameLine();
  label(" |  VULKAN  |  SI");
  // Keep selectors in the header window so clicking its background cannot
  // raise an overlapping window above them.
  ImGui::SetCursorPos({498, 24});
  if (ImGui::RadioButton("Single ball", p->mode == 1)) {
    p->mode = 1;
    p->action = 5;
  }
  ImGui::SameLine();
  if (ImGui::RadioButton("Three balls", p->mode == 3)) {
    p->mode = 3;
    p->action = 5;
  }
  ImGui::End();
  panel("scene", 0, top, left, h - top - bottom);
  label("SCENE EXPLORER");
  ImGui::Separator();
  ImGui::Spacing();
  ImGui::Text("v  sphere-impact");
  const char *names[] = {"   sphere_source", "   mechanical_solver",
                         "   telemetry_output"};
  for (int i = 0; i < 3; i++)
    if (ImGui::Selectable(names[i], p->selected == i))
      p->selected = i;
  ImGui::Spacing();
  ImGui::Separator();
  label("WORLD");
  ImGui::Text("Y up / metres");
  ImGui::Text("Infinite ground plane");
  ImGui::Spacing();
  label("SOLVER");
  ImGui::Text(p->deform == 2 ? "Continuum FEM / f64" :
              p->deform ? "Deformable XPBD / f64" : "Rigid body / f64");
  ImGui::Text("240 Hz / offline bake");
  if (refined_nodes) {
    ImGui::Text("%d nodes / %d tets", refined_nodes, refined_tetrahedra);
    ImGui::TextWrapped("Refined cache / Clojure job");
  } else {
    ImGui::Text(p->deform ? "43 nodes / 80 tetrahedra" : "Sphere-plane CCD");
  }
  ImGui::Spacing();
  const char *models[] = {"Rigid", "XPBD", "Continuum FEM"};
  ImGui::SetNextItemWidth(190);
  if (ImGui::Combo("##material_solver", &p->deform, models, 3)) {
    p->action = 6;
  }
  label("AUTHORITATIVE STORE");
  ImGui::Text("Flecs components");
  ImGui::Text("BallConfig / BallState");
  ImGui::Text("Revision %d", p->revision);
  if (!refined_nodes) {
    ImGui::SetCursorPosY(std::max(ImGui::GetCursorPosY() + 8, h - top - bottom - 72));
    label("VIEWPORT");
    ImGui::Text("Drag R: orbit / Wheel: zoom");
    ImGui::Text("1 / 3: source  D: material");
  }
  ImGui::End();
  panel("parameters", w - right, top, right, h - top - bottom);
  label(p->selected == 0   ? "SPHERE SOURCE"
        : p->selected == 1 ? "MECHANICAL SOLVER"
                           : "TELEMETRY OUTPUT");
  ImGui::Separator();
  ImGui::BeginDisabled(refined_nodes != 0);
  if (p->selected == 0) {
    number("Radius", &p->radius, .1, 1.0, "%.2f m");
    number("Mass", &p->mass, .05, 20, "%.2f kg");
    number("Drop gap", &p->height, .1, 8, "%.2f m");
    number("Launch X", &p->vx, -4, 4, "%.2f m/s");
    number("Launch Z", &p->vz, -4, 4, "%.2f m/s");
    number("Spin Y", &p->spin, -20, 20, "%.2f rad/s");
  } else if (p->selected == 1) {
    number("Gravity", &p->gravity, 0, 20, "%.3f m/s2");
    number("Friction", &p->friction, 0, 1, "%.3f");
    if (p->deform) {
      number(p->deform == 2 ? "Young modulus" : "Stiffness", &p->stiffness, 1000, 100000, "%.0f Pa");
      ImGui::TextWrapped(p->deform == 2
          ? "Solid continuum, Poisson ratio 0.4; not calibrated."
          : "Elastic network + volume constraints. Effective stiffness; not calibrated.");
    } else {
      number("Restitution", &p->restitution, 0, 1, "%.3f");
      number("Rolling loss", &p->rolling, 0, .2, "%.3f");
      ImGui::TextWrapped("Solid sphere: I = 2/5 mr^2. Coulomb contact impulses "
                         "couple translation and spin.");
    }
  } else {
    metric("SYSTEM MECHANICAL ENERGY", p->energy, "J");
    metric("KINETIC ENERGY", p->kinetic, "J");
    if (p->deform)
      metric("VOLUME / REST VOLUME", p->volume_ratio, "");
    else
      metric("NORMAL IMPULSE / TICK", p->impulse, "N s");
    ImGui::Text("Position [m]\n%.3f, %.3f, %.3f", p->px, p->py, p->pz);
  }
  ImGui::EndDisabled();
  ImGui::Spacing();
  if (refined_nodes) {
    ImGui::TextWrapped("Refined job cache. Source and material selectors return to coarse experiments.");
  } else {
    if (ImGui::Button("Apply & bake experiment", {-1, 30}))
      p->action = 1;
    label("Edits apply when baking.");
    ImGui::Separator();
    number("Duration", &p->duration, .25, 60, "%.2f s");
  }
  metric(p->baking ? "BAKING SIMULATION TIME" : "CACHED SIMULATION TIME", p->time, "s");
  ImGui::Text("Speed %.3f m/s", p->speed);
  if (p->deform)
    ImGui::Text("Compression %.1f%% / gap %.3f m", p->compression * 100, p->clearance);
  else
    ImGui::Text("Impacts  %d", p->impacts);
  ImGui::Text("Cache    %d / 14401", p->count);
  if (ImGui::Button("Export cached run (.csv)", {-1, 28}))
    p->action = 4;
  if (p->exported)
    ImGui::TextColored(p->exported > 0 ? teal : ImVec4(1, .4, .3, 1),
                       p->exported > 0 ? "Saved exports/trajectory.csv"
                       : p->exported == -2 ? "FEM solve stopped; partial cache retained"
                                          : "Export failed");
  if (frame_capture_status >= 0) {
    ImGui::BeginDisabled(frame_capture_status > 0 && frame_capture_status < 4);
    if (ImGui::Button("Capture rendered frame", {-1, 28})) {
      mkdir("exports", 0755);
      p->action = 9;
    }
    ImGui::EndDisabled();
    if (frame_capture_status == 4)
      ImGui::TextColored(teal, "Saved exports/frame.ppm");
    else if (frame_capture_status >= 5)
      ImGui::TextColored(ImVec4(1, .4, .3, 1), "%s",
                         frame_capture_status == 6 ? "Frame file could not be saved"
                                                   : "Frame readback unavailable");
  }
  ImGui::End();
  // Small overlay remains transparent, keeping the 3D scene unobstructed.
  ImGui::SetNextWindowBgAlpha(0);
  panel("view label", left + 14, top + 8, 440, 68);
  ImGui::TextColored(p->baking ? teal : ImVec4(1.f, .72f, .25f, 1.f),
    p->exported == -2 ? "SOLVER FAILED / PARTIAL CACHE"
    : p->baking ? "BAKING / SOLVER TIME INDEPENDENT OF DISPLAY"
              : (p->paused ? "CACHE PAUSED / SPACE TO PLAY" : "CACHE PLAYBACK / MEASURED SIMULATION"));
  label(p->deform ? "01 / Simulated elastic tetrahedral surface" : "01 / Rigid sphere contact study");
  ImGui::End();
  panel("graph", 0, h - bottom, w, bottom - 64);
  label("PROCEDURAL NETWORK");
  ImGui::SameLine();
  ImGui::TextColored(teal, "  /  live Flecs dependencies");
  float y = h - bottom + 57, x = 32;
  auto *d = ImGui::GetWindowDrawList();
  for (int i = 0; i < 2; i++) {
    float a = x + i * 242 + 182;
    d->AddBezierCubic({a, y + 33}, {a + 28, y + 33}, {a + 30, y + 33},
                      {a + 60, y + 33}, IM_COL32(65, 150, 141, 255), 2);
  }
  node("sphere_source", "Geometry + initial state", 0, p, x, y);
  node("mechanical_solver", p->deform == 2 ? "FEM / Neo-Hookean" : p->deform ? "XPBD / elastic / volume" : "CCD / friction / spin", 1, p, x + 242, y);
  node("telemetry_output", "Cache + SI observables", 2, p, x + 484, y);
  ImGui::SetCursorScreenPos({w - 330, y + 8});
  label("NUMERICAL CONTRACT");
  ImGui::SetCursorScreenPos({w - 330, y + 31});
  ImGui::Text("Bake first. Play measured cache.");
  ImGui::SetCursorScreenPos({w - 330, y + 53});
  label(refined_nodes ? (p->mode == 3 ? "FEM / triangle contact + friction" : "FEM / nodal plane contact") : p->deform == 2 ? "FEM / coarse convex contact" :
        p->deform ? "XPBD elastic network" : "Rigid contact dynamics");
  ImGui::End();
  panel("transport", 0, h - 64, w, 64);
  ImGui::BeginDisabled(refined_nodes != 0);
  if (ImGui::Button(p->baking ? "STOP BAKE" : "BAKE"))
    p->action = p->baking ? 8 : 1;
  ImGui::EndDisabled();
  ImGui::SameLine();
  ImGui::BeginDisabled(p->baking || p->count <= 1);
  if (ImGui::Button(p->paused ? "PLAY CACHE" : "PAUSE")) {
    if (p->paused && p->cursor >= p->count - 1) {
      p->cursor = 0;
      p->action = 3;
    }
    p->paused = !p->paused;
  }
  ImGui::SameLine();
  if (ImGui::Button("STEP")) {
    p->paused = 1;
    p->action = 2;
  }
  ImGui::SameLine();
  if (ImGui::Button("REWIND")) {
    p->paused = 1;
    p->cursor = 0;
    p->action = 3;
  }
  ImGui::SameLine();
  ImGui::SetNextItemWidth(w - 700);
  int tick = p->cursor;
  if (ImGui::SliderInt("##cache", &tick, 0, std::max(0, p->count - 1), "Tick %d")) {
    p->cursor = tick;
    p->paused = 1;
    p->action = 3;
  }
  ImGui::SameLine();
  ImGui::SetNextItemWidth(105);
  ImGui::SliderFloat("Rate", &p->rate, .1f, 2.f, "%.2fx");
  ImGui::SameLine();
  bool loop = p->loop != 0;
  if (ImGui::Checkbox("Loop", &loop)) p->loop = loop;
  ImGui::EndDisabled();
  ImGui::End();
  ImGui::Render();
  ImGui_ImplVulkan_RenderDrawData(ImGui::GetDrawData(),
                                  reinterpret_cast<VkCommandBuffer>(command));
}
extern "C" void *lab_export_begin_fem(double radius, double mass, double height,
                                  double gravity, double restitution,
                                  double friction, double rolling, double vx,
                                  double vz, double spin, int bodies,
                                  double dt, int deform, double stiffness) {
  mkdir("exports", 0755);
  auto *metadata = fopen("exports/experiment.json", "w");
  if (!metadata)
    return nullptr;
  int written = fprintf(
      metadata,
      "{\n  \"solver\": \"%s\",\n  \"%s\": %.17g,\n  \"poisson_ratio\": %s,\n  \"body_count\": %d,\n"
      "  \"dt_s\": %.17g,\n  \"radius_m\": %.17g,\n  \"mass_kg\": %.17g,\n"
      "  \"drop_gap_m\": %.17g,\n  \"gravity_m_s2\": %.17g,\n"
      "  \"restitution\": %.17g,\n  \"friction\": %.17g,\n  \"rolling\": "
      "%.17g,\n"
      "  \"launch_x_m_s\": %.17g,\n  \"launch_z_m_s\": %.17g,\n  "
      "\"spin_y_rad_s\": %.17g\n}\n",
      deform == 2 ? "field-lab-stable-neo-hookean-v1" :
          deform ? "field-lab-xpbd-v1" : "field-lab-rigid-v1",
      deform == 2 ? "young_modulus_Pa" : "effective_stiffness_Pa", stiffness,
      deform == 2 ? "0.4" : "null",
      bodies, dt, radius, mass, height, gravity, restitution, friction, rolling,
      vx, vz, spin);
  int closed = fclose(metadata);
  if (written < 0 || closed != 0)
    return nullptr;
  auto *file = fopen("exports/trajectory.csv", "w");
  if (file &&
      fprintf(file,
              "body,time_s,x_m,y_m,z_m,vx_m_s,vy_m_s,vz_m_s,wx_rad_s,wy_rad_s,"
              "wz_rad_s,qx,qy,qz,qw,energy_J,normal_impulse_N_s\n") < 0) {
    fclose(file);
    return nullptr;
  }
  if (!file) return nullptr;
  FILE *particles = nullptr;
  if (deform) {
    particles = fopen("exports/particles.csv", "w");
    if (!particles || fprintf(particles, "body,particle,time_s,x_m,y_m,z_m,vx_m_s,vy_m_s,vz_m_s\n") < 0) {
      if (particles) fclose(particles);
      fclose(file);
      return nullptr;
    }
  }
  return new Export{file, particles, deform != 0};
}
extern "C" int lab_export_sample(void *f, int body, double t, double x,
                                 double y, double z, double vx, double vy,
                                 double vz, double wx, double wy, double wz,
                                 double qx, double qy, double qz, double qw,
                                 double e, double j) {
  auto *run = static_cast<Export *>(f);
  if (run->deform) wx = wy = wz = qx = qy = qz = qw = j = NAN;
  return fprintf(
             run->trajectory,
             "%d,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%"
             ".17g,%.17g,%.17g,%.17g,%.17g,%.17g\n",
             body, t, x, y, z, vx, vy, vz, wx, wy, wz, qx, qy, qz, qw, e,
             j) >= 0;
}
extern "C" int lab_export_particle(void *f, int body, int particle, double t,
                                    double x, double y, double z,
                                    double vx, double vy, double vz) {
  auto *run = static_cast<Export *>(f);
  return run->particles && fprintf(run->particles,
      "%d,%d,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g\n",
      body, particle, t, x, y, z, vx, vy, vz) >= 0;
}
extern "C" int lab_export_end(void *f) {
  auto *run = static_cast<Export *>(f);
  bool ok = fclose(run->trajectory) == 0;
  if (run->particles && fclose(run->particles) != 0) ok = false;
  if (run->reference && fclose(run->reference) != 0) ok = false;
  if (run->cells && fclose(run->cells) != 0) ok = false;
  delete run;
  return ok;
}

extern "C" int lab_export_reference(void *f, int node, double x, double y, double z) {
  auto *run = static_cast<Export *>(f);
  if (!run->reference) {
    run->reference = fopen("exports/reference.csv", "w");
    if (!run->reference || fprintf(run->reference, "node,x_m,y_m,z_m\n") < 0) return 0;
  }
  return fprintf(run->reference, "%d,%.17g,%.17g,%.17g\n", node, x, y, z) >= 0;
}

extern "C" int lab_export_cell(void *f, int cell, int a, int b, int c, int d) {
  auto *run = static_cast<Export *>(f);
  if (!run->cells) {
    run->cells = fopen("exports/cells.csv", "w");
    if (!run->cells || fprintf(run->cells, "cell,a,b,c,d\n") < 0) return 0;
  }
  return fprintf(run->cells, "%d,%d,%d,%d,%d\n", cell, a, b, c, d) >= 0;
}

extern "C" int lab_export_reference_body(void *f, int body, int node, double x, double y, double z) {
  auto *run = static_cast<Export *>(f);
  if (!run->reference) {
    run->reference = fopen("exports/reference.csv", "w");
    if (!run->reference || fprintf(run->reference, "body,node,x_m,y_m,z_m\n") < 0) return 0;
  }
  return fprintf(run->reference, "%d,%d,%.17g,%.17g,%.17g\n", body, node, x, y, z) >= 0;
}

extern "C" int lab_export_cell_body(void *f, int body, int cell, int a, int b, int c, int d) {
  auto *run = static_cast<Export *>(f);
  if (!run->cells) {
    run->cells = fopen("exports/cells.csv", "w");
    if (!run->cells || fprintf(run->cells, "body,cell,a,b,c,d\n") < 0) return 0;
  }
  return fprintf(run->cells, "%d,%d,%d,%d,%d,%d\n", body, cell, a, b, c, d) >= 0;
}

extern "C" void lab_render_mesh(unsigned long long command, LabPanel *panel, int nodes, int tetrahedra) {
  refined_nodes = nodes;
  refined_tetrahedra = tetrahedra;
  lab_render_fem(command, panel);
  if (nodes && panel->action == 1) panel->action = 0;
}

extern "C" void lab_render_capture(unsigned long long command, LabPanel *panel,
                                    int nodes, int tetrahedra, int capture_status) {
  frame_capture_status = capture_status;
  lab_render_mesh(command, panel, nodes, tetrahedra);
  frame_capture_status = -1;
}

// Preserve the original C entry points for standalone callers. New names let
// the running native development generation bind the updated adapter explicitly.
extern "C" void lab_render(unsigned long long command, LabPanel *panel) {
  lab_render_fem(command, panel);
}

extern "C" void *lab_export_begin(double radius, double mass, double height,
                                  double gravity, double restitution, double friction,
                                  double rolling, double vx, double vz, double spin,
                                  int bodies, double dt, int deform, double stiffness) {
  return lab_export_begin_fem(radius, mass, height, gravity, restitution, friction,
                              rolling, vx, vz, spin, bodies, dt, deform, stiffness);
}

extern "C" void *lab_export_begin_mesh(double radius, double mass, double height,
                                       double gravity, double restitution, double friction,
                                       double rolling, double vx, double vz, double spin,
                                       int bodies, double dt, int deform, double stiffness) {
  // A legacy export must not retain topology from an earlier refined job.
  std::remove("exports/reference.csv");
  std::remove("exports/cells.csv");
  return lab_export_begin_fem(radius, mass, height, gravity, restitution, friction,
                              rolling, vx, vz, spin, bodies, dt, deform, stiffness);
}

extern "C" int lab_export_end_mesh(void *file) {
  return lab_export_end(file);
}
