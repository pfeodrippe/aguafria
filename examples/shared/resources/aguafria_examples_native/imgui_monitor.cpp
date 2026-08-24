#include "imgui_monitor.h"

#include "imgui.h"
#include "backends/imgui_impl_glfw.h"
#include "backends/imgui_impl_vulkan.h"

#define GLFW_INCLUDE_NONE
#define GLFW_INCLUDE_VULKAN
#include <GLFW/glfw3.h>

#include <algorithm>
#include <cstdlib>
#include <cstdio>
#include <cstring>

namespace {

bool initialized = false;
bool visible = true;
bool show_raw_protocol = false;
bool show_only_granite = false;
VkDevice monitor_device = VK_NULL_HANDLE;
GLFWwindow* monitor_window = nullptr;
bool previous_f2 = false;
int selected_racer = 0;
int selected_history_offset = 0;
AguafriaImguiSnapshot current = {};
uint64_t latency_revision[AGUAFRIA_IMGUI_RACER_COUNT] = {};
float latency_history[AGUAFRIA_IMGUI_RACER_COUNT][120] = {};
int latency_cursor[AGUAFRIA_IMGUI_RACER_COUNT] = {};

ImVec4 racer_color(uint8_t racer) {
    static const ImVec4 colors[AGUAFRIA_IMGUI_RACER_COUNT] = {
        ImVec4(0.05f, 0.82f, 1.00f, 1.00f),
        ImVec4(1.00f, 0.25f, 0.66f, 1.00f),
        ImVec4(0.38f, 1.00f, 0.28f, 1.00f),
        ImVec4(1.00f, 0.42f, 0.08f, 1.00f),
        ImVec4(0.62f, 0.38f, 1.00f, 1.00f),
        ImVec4(1.00f, 0.90f, 0.22f, 1.00f),
        ImVec4(0.18f, 1.00f, 0.72f, 1.00f),
        ImVec4(1.00f, 0.30f, 0.30f, 1.00f),
    };
    return colors[racer < AGUAFRIA_IMGUI_RACER_COUNT ? racer : 0];
}

const char* ordinal(uint8_t rank) {
    switch (rank) {
        case 1: return "1st";
        case 2: return "2nd";
        case 3: return "3rd";
        case 4: return "4th";
        case 5: return "5th";
        case 6: return "6th";
        case 7: return "7th";
        case 8: return "8th";
        default: return "unranked";
    }
}

const char* source_name(uint8_t source) {
    switch (source) {
        case 1: return "Granite";
        case 2: return "replay";
        case 3: return "human";
        default: return "fallback";
    }
}

const char* team_name(uint8_t team) {
    static const char* names[] = {"Aurora", "Vortex", "Atlas", "Nova"};
    return team < 4 ? names[team] : "Unknown";
}

const char* team_short_name(uint8_t team) {
    static const char* names[] = {"AUR", "VTX", "ATL", "NVA"};
    return team < 4 ? names[team] : "UNK";
}

const char* pit_state_name(uint8_t state) {
    static const char* names[] = {"on track", "pit called", "servicing", "rejoining"};
    return state < 4 ? names[state] : "unknown pit state";
}

const char* radio_name(uint8_t code) {
    static const char* names[] = {
        "no radio yet", "tires losing grip", "pit stop confirmed",
        "box occupied; hold", "boxing now", "fresh tires; rejoining",
        "collision damage reported", "pit stop confirmed for repairs",
        "repairs complete; rejoining", "stay out; continue racing"
    };
    return code < 10 ? names[code] : "unknown radio message";
}

const char* team_action_name(uint8_t action) {
    static const char* names[] = {"hold both cars", "call driver A", "call driver B"};
    return action < 3 ? names[action] : "unknown instruction";
}

const char* item_name(uint8_t item) {
    static const char* names[] = {
        "no item", "bolt", "trap", "boost", "shield", "pulse", "surge"
    };
    return item < 7 ? names[item] : "unknown item";
}

const char* persona_name(uint8_t persona) {
    static const char* names[] = {"cautious", "balanced", "bold"};
    return persona < 3 ? names[persona] : "unknown";
}

const char* relative_lane_name(uint8_t lane) {
    static const char* names[] = {"on the left", "in the same lane", "on the right"};
    return lane < 3 ? names[lane] : "at an unknown relative position";
}

const char* status_name(uint8_t status) {
    static const char* names[] = {
        "clear", "hazard nearby", "recovering from a hit", "shield active"
    };
    return status < 4 ? names[status] : "unknown local condition";
}

const char* chosen_lane_name(uint8_t lane) {
    static const char* names[] = {"move left", "stay centered", "move right"};
    return lane < 3 ? names[lane] : "use an unknown lane";
}

const char* pace_name(uint8_t pace) {
    static const char* names[] = {"steady pace", "attack pace", "maximum pace"};
    return pace < 3 ? names[pace] : "use an unknown pace";
}

void append_latency(const AguafriaImguiRacer& racer) {
    if (!racer.valid || racer.revision == 0 ||
        latency_revision[racer.id] == racer.revision) {
        return;
    }
    latency_revision[racer.id] = racer.revision;
    const int cursor = latency_cursor[racer.id];
    latency_history[racer.id][cursor] = static_cast<float>(racer.total_us) / 1000.0f;
    latency_cursor[racer.id] = (cursor + 1) % 120;
}

void draw_summary(const AguafriaImguiRacer& racer) {
    char line[768];
    if (racer.source == 1) {
        std::snprintf(line, sizeof(line),
                      "Racer %u · decision %llu · Granite AI · %.1f ms · %.2f model steps/s · %s",
                      racer.id,
                      static_cast<unsigned long long>(racer.revision),
                      static_cast<double>(racer.total_us) / 1000.0,
                      racer.steps_per_second,
                      racer.deadline_status == 0 ? "on time" : "late");
    } else if (racer.source == 2) {
        std::snprintf(line, sizeof(line),
                      "Racer %u · decision %llu · recorded replay · immediate",
                      racer.id, static_cast<unsigned long long>(racer.revision));
    } else if (racer.source == 3) {
        std::snprintf(line, sizeof(line),
                      "Racer %u · decision %llu · human input · immediate",
                      racer.id, static_cast<unsigned long long>(racer.revision));
    } else {
        const char* reason = racer.deadline_status == 1
            ? "Granite missed its deadline" : "Granite had not finished";
        std::snprintf(line, sizeof(line),
                      "Racer %u · decision %llu · safe fallback · %s",
                      racer.id,
                      static_cast<unsigned long long>(racer.revision), reason);
    }
    ImGui::TextWrapped("%s", line);

    if (racer.detailed_observation) {
        std::snprintf(line, sizeof(line),
                      "Saw: %s of 8 · lap %u · %u-%u%% through the lap · speed %u-%u%% of a lap/s. Driving style: %s.",
                      ordinal(racer.rank), racer.lap + 1,
                      racer.progress_bin * 10, (racer.progress_bin + 1) * 10,
                      racer.speed_bin, racer.speed_bin + 1,
                      persona_name(racer.persona));
        ImGui::TextWrapped("%s", line);
        if (racer.target == racer.id) {
            ImGui::TextWrapped("No opponent was ahead.");
        } else if (racer.target_distance_bin == 9) {
            std::snprintf(line, sizeof(line),
                          "Racer %u was at least 9%% of a lap ahead, %s.",
                          racer.target, relative_lane_name(racer.target_lane));
            ImGui::TextWrapped("%s", line);
        } else {
            std::snprintf(line, sizeof(line),
                          "Racer %u was %u-%u%% of a lap ahead, %s.",
                          racer.target, racer.target_distance_bin,
                          racer.target_distance_bin + 1,
                          relative_lane_name(racer.target_lane));
            ImGui::TextWrapped("%s", line);
        }
        std::snprintf(line, sizeof(line),
                      "Inventory: %s. Track: %s. Decision: %s.",
                      item_name(racer.item), status_name(racer.tactical_status),
                      racer.urgent ? "urgent" : "routine");
        ImGui::TextWrapped("%s", line);
    } else {
        std::snprintf(line, sizeof(line),
                      "State: %s of 8 · lap %u · %u-%u%% through the lap · speed %u-%u%% of a lap/s · inventory: %s · %s.",
                      ordinal(racer.rank), racer.lap + 1,
                      racer.progress_bin * 10, (racer.progress_bin + 1) * 10,
                      racer.speed_bin, racer.speed_bin + 1, item_name(racer.item),
                      racer.urgent ? "urgent" : "routine");
        ImGui::TextWrapped("%s", line);
    }

    std::snprintf(line, sizeof(line),
                  "Team: %s with racer %u. Tires: %.0f%%. Damage: %.0f%%. Pit: %s; %u stops. Latest radio: %s.",
                  team_name(racer.team), racer.teammate,
                  std::clamp(racer.tire_condition, 0.0f, 1.0f) * 100.0f,
                  std::clamp(racer.damage, 0.0f, 1.0f) * 100.0f,
                  pit_state_name(racer.pit_state), racer.pit_stops,
                  radio_name(racer.radio_code));
    ImGui::TextWrapped("%s", line);
    std::snprintf(line, sizeof(line),
                  "%s strategist AI: %s · decision %llu · %.1f ms · %s.",
                  team_name(racer.team), team_action_name(racer.team_instruction),
                  static_cast<unsigned long long>(racer.team_decision_revision),
                  static_cast<double>(racer.team_last_latency_us) / 1000.0,
                  racer.team_pending ? "thinking" : "ready");
    ImGui::TextWrapped("%s", line);

    char item_choice[128];
    if (racer.item_choice == 1 && racer.item == 0) {
        std::snprintf(item_choice, sizeof(item_choice),
                      "item requested, but inventory empty");
    } else if (racer.item_choice == 1) {
        std::snprintf(item_choice, sizeof(item_choice), "use %s", item_name(racer.item));
    } else if (racer.item == 0) {
        std::snprintf(item_choice, sizeof(item_choice), "no item");
    } else {
        std::snprintf(item_choice, sizeof(item_choice), "save %s", item_name(racer.item));
    }
    const char* action_label = racer.source == 1 ? "Chose"
        : racer.source == 2 ? "Replay action"
        : racer.source == 3 ? "Human action" : "Fallback action";
    if (racer.target == racer.id) {
        std::snprintf(line, sizeof(line), "%s: %s · %s · %s · no target.",
                      action_label,
                      chosen_lane_name(racer.lane_choice), pace_name(racer.pace_choice),
                      item_choice);
    } else {
        std::snprintf(line, sizeof(line), "%s: %s · %s · %s · target racer %u.",
                  action_label,
                  chosen_lane_name(racer.lane_choice), pace_name(racer.pace_choice),
                  item_choice, racer.target);
    }
    ImGui::TextWrapped("%s", line);

    if (racer.outcome_resolved) {
        std::snprintf(line, sizeof(line),
                      "Result after 1 second: +%.2f%% of a lap · rank %s to %s · %u %s · %s.",
                      racer.progress_gain * 100.0f,
                      ordinal(racer.start_rank), ordinal(racer.end_rank),
                      racer.hits_dealt, racer.hits_dealt == 1 ? "hit" : "hits",
                      racer.outcome_item_used ? "item used" : "item not used");
    } else {
        std::snprintf(line, sizeof(line), "Result after 1 second: not measured yet.");
    }
    ImGui::TextWrapped("%s", line);
}

void draw_track_hud() {
    constexpr ImGuiWindowFlags flags =
        ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_NoInputs |
        ImGuiWindowFlags_NoNav | ImGuiWindowFlags_NoSavedSettings |
        ImGuiWindowFlags_AlwaysAutoResize;
    const ImVec4 yellow(1.0f, 0.78f, 0.0f, 1.0f);
    const ImVec4 muted(0.78f, 0.68f, 0.38f, 1.0f);

    ImGui::SetNextWindowPos(ImVec2(14.0f, 14.0f), ImGuiCond_Always);
    ImGui::SetNextWindowBgAlpha(0.78f);
    if (ImGui::Begin("##aguafria-racer-hud", nullptr, flags)) {
        ImGui::TextColored(yellow, "RACERS");
        for (int index = 0; index < AGUAFRIA_IMGUI_RACER_COUNT; ++index) {
            const AguafriaImguiRacer& racer = current.racers[index];
            if (!racer.valid) {
                ImGui::TextColored(muted, "R%d | waiting for race state", index);
                continue;
            }
            ImGui::TextColored(
                racer_color(racer.id),
                "R%u %s | %s | tire %.0f%% | dmg %.0f%% | %s | %.1f ms | %s",
                racer.id, team_short_name(racer.team), ordinal(racer.rank),
                std::clamp(racer.tire_condition, 0.0f, 1.0f) * 100.0f,
                std::clamp(racer.damage, 0.0f, 1.0f) * 100.0f,
                pit_state_name(racer.pit_state),
                static_cast<double>(racer.total_us) / 1000.0,
                racer.pending ? "thinking" : "ready");
        }
    }
    ImGui::End();

    ImGui::SetNextWindowPos(
        ImVec2(14.0f, ImGui::GetIO().DisplaySize.y - 14.0f),
        ImGuiCond_Always, ImVec2(0.0f, 1.0f));
    ImGui::SetNextWindowBgAlpha(0.78f);
    if (ImGui::Begin("##aguafria-team-radio", nullptr, flags)) {
        ImGui::TextColored(yellow, "TEAM RADIO");
        for (int team = 0; team < 4; ++team) {
            const AguafriaImguiRadio& latest =
                current.radio[team * AGUAFRIA_IMGUI_RADIO_PER_TEAM];
            if (current.radio_counts[team] == 0 || !latest.valid) {
                ImGui::TextColored(muted, "%s | radio quiet", team_name(team));
            } else if (latest.source == 1) {
                ImGui::TextColored(
                    racer_color(latest.target),
                    "R%u -> %s strategist | %s | tire %.0f%% | damage %.0f%%",
                    latest.target, team_name(team), radio_name(latest.code),
                    std::clamp(latest.tire_condition, 0.0f, 1.0f) * 100.0f,
                    std::clamp(latest.damage, 0.0f, 1.0f) * 100.0f);
            } else {
                ImGui::TextColored(
                    racer_color(latest.target),
                    "%s strategist -> R%u | %s | AI #%llu | %.1f ms",
                    team_name(team), latest.target, radio_name(latest.code),
                    static_cast<unsigned long long>(latest.decision_revision),
                    static_cast<double>(latest.latency_us) / 1000.0);
            }
        }
    }
    ImGui::End();

    const float leaderboard_width = 290.0f;
    ImGui::SetNextWindowPos(
        ImVec2(std::max(14.0f, ImGui::GetIO().DisplaySize.x - leaderboard_width - 14.0f),
               14.0f),
        ImGuiCond_Always);
    ImGui::SetNextWindowSize(ImVec2(leaderboard_width, 0.0f), ImGuiCond_Always);
    ImGui::SetNextWindowBgAlpha(0.78f);
    if (ImGui::Begin("##aguafria-leaderboard", nullptr, flags)) {
        ImGui::TextColored(yellow, "LEADERBOARD");
        for (int rank = 1; rank <= AGUAFRIA_IMGUI_RACER_COUNT; ++rank) {
            bool found = false;
            for (const AguafriaImguiRacer& racer : current.racers) {
                if (racer.valid && racer.rank == rank) {
                    ImGui::TextColored(
                        racer_color(racer.id), "%s | R%u | lap %u | %.0f%% | %s",
                        ordinal(racer.rank), racer.id, racer.lap + 1,
                        std::clamp(racer.progress, 0.0f, 1.0f) * 100.0f,
                        racer.item == 0 ? "empty" : item_name(racer.item));
                    found = true;
                    break;
                }
            }
            if (!found) {
                ImGui::TextColored(muted, "%s | waiting", ordinal(rank));
            }
        }
    }
    ImGui::End();

    ImGui::SetNextWindowPos(
        ImVec2(ImGui::GetIO().DisplaySize.x - 14.0f,
               ImGui::GetIO().DisplaySize.y - 12.0f),
        ImGuiCond_Always, ImVec2(1.0f, 1.0f));
    ImGui::SetNextWindowBgAlpha(0.78f);
    if (ImGui::Begin("##aguafria-controls", nullptr, flags)) {
        ImGui::TextColored(yellow,
                           "F2 LOGS  |  F1 INTENTS  |  P PAUSE  |  R RESET");
    }
    ImGui::End();
}

void draw_monitor() {
    ImGui::SetNextWindowSize(ImVec2(720.0f, 660.0f), ImGuiCond_FirstUseEver);
    if (!ImGui::Begin("Aguafria racer cognition", &visible)) {
        ImGui::End();
        return;
    }

    ImGui::Text("Tick %llu | %llu decisions | %llu Granite | %llu fallback",
                static_cast<unsigned long long>(current.tick),
                static_cast<unsigned long long>(current.total_decisions),
                static_cast<unsigned long long>(current.llm_decisions),
                static_cast<unsigned long long>(current.fallback_decisions));
    ImGui::Text("Workers %llu/%llu | pending %u | %.2f model steps/s | %.1f MiB state",
                static_cast<unsigned long long>(current.worker_results),
                static_cast<unsigned long long>(current.worker_requests),
                current.pending_requests, current.average_steps_per_second,
                static_cast<double>(current.worker_state_bytes) / (1024.0 * 1024.0));
    ImGui::Text("Deadline misses %llu | rejected %llu | measured outcomes %llu",
                static_cast<unsigned long long>(current.deadline_misses),
                static_cast<unsigned long long>(current.rejected_decisions),
                static_cast<unsigned long long>(current.resolved_outcomes));

    if (ImGui::CollapsingHeader("Controls and track marks")) {
        ImGui::BulletText("Track diamonds are item pickups; crosses are traps; arrow diamonds are bolts.");
        ImGui::BulletText("Thin lines show each racer's short-horizon lane and opponent targets.");
        ImGui::TextDisabled("F1: track intent lines | F2: hide/show this panel | P: pause | R: reset");
    }

    ImGui::Checkbox("Only Granite decisions", &show_only_granite);
    ImGui::SameLine();
    ImGui::Checkbox("Show raw protocol", &show_raw_protocol);
    ImGui::Separator();

    if (ImGui::BeginTable("racers", 10,
                          ImGuiTableFlags_RowBg | ImGuiTableFlags_Borders |
                          ImGuiTableFlags_Resizable | ImGuiTableFlags_SizingStretchProp)) {
        ImGui::TableSetupColumn("Racer");
        ImGui::TableSetupColumn("Team");
        ImGui::TableSetupColumn("Rank");
        ImGui::TableSetupColumn("Tires");
        ImGui::TableSetupColumn("Damage");
        ImGui::TableSetupColumn("Pit");
        ImGui::TableSetupColumn("Source");
        ImGui::TableSetupColumn("Decision");
        ImGui::TableSetupColumn("Latency");
        ImGui::TableSetupColumn("Item");
        ImGui::TableHeadersRow();
        for (int index = 0; index < AGUAFRIA_IMGUI_RACER_COUNT; ++index) {
            const AguafriaImguiRacer& racer = current.racers[index];
            if (!racer.valid || (show_only_granite && racer.source != 1)) {
                continue;
            }
            ImGui::TableNextRow();
            ImGui::TableSetColumnIndex(0);
            char label[32];
            std::snprintf(label, sizeof(label), "Racer %u", racer.id);
            if (ImGui::Selectable(label, selected_racer == index,
                                  ImGuiSelectableFlags_SpanAllColumns)) {
                selected_racer = index;
                selected_history_offset = 0;
            }
            ImGui::TableSetColumnIndex(1); ImGui::TextUnformatted(team_short_name(racer.team));
            ImGui::TableSetColumnIndex(2); ImGui::TextUnformatted(ordinal(racer.rank));
            ImGui::TableSetColumnIndex(3); ImGui::Text("%.0f%%", std::clamp(racer.tire_condition, 0.0f, 1.0f) * 100.0f);
            ImGui::TableSetColumnIndex(4); ImGui::Text("%.0f%%", std::clamp(racer.damage, 0.0f, 1.0f) * 100.0f);
            ImGui::TableSetColumnIndex(5); ImGui::TextUnformatted(pit_state_name(racer.pit_state));
            ImGui::TableSetColumnIndex(6); ImGui::TextUnformatted(source_name(racer.source));
            ImGui::TableSetColumnIndex(7); ImGui::Text("%llu", static_cast<unsigned long long>(racer.revision));
            ImGui::TableSetColumnIndex(8); ImGui::Text("%.1f ms", static_cast<double>(racer.total_us) / 1000.0);
            ImGui::TableSetColumnIndex(9); ImGui::TextUnformatted(item_name(racer.item));
        }
        ImGui::EndTable();
    }

    ImGui::SeparatorText("Team radio history - newest first");
    const int selected_team = std::clamp<int>(
        current.racers[std::clamp(selected_racer, 0, 7)].team, 0, 3);
    const int radio_count = std::min<int>(
        current.radio_counts[selected_team], AGUAFRIA_IMGUI_RADIO_PER_TEAM);
    const bool radio_visible =
        ImGui::BeginChild("team-radio-history", ImVec2(0.0f, 105.0f), true);
    if (radio_visible) {
        if (radio_count == 0) {
            ImGui::TextUnformatted("No team/driver exchanges recorded yet.");
        }
        for (int offset = 0; offset < radio_count; ++offset) {
            const AguafriaImguiRadio& entry =
                current.radio[selected_team * AGUAFRIA_IMGUI_RADIO_PER_TEAM + offset];
            if (!entry.valid) continue;
            if (entry.source == 1) {
                ImGui::TextWrapped(
                    "tick %llu | R%u -> %s strategist | %s | tires %.0f%% | damage %.0f%%",
                    static_cast<unsigned long long>(entry.tick), entry.target,
                    team_name(entry.team), radio_name(entry.code),
                    std::clamp(entry.tire_condition, 0.0f, 1.0f) * 100.0f,
                    std::clamp(entry.damage, 0.0f, 1.0f) * 100.0f);
            } else {
                ImGui::TextWrapped(
                    "tick %llu | %s strategist -> R%u | %s | decision %llu | %.1f ms",
                    static_cast<unsigned long long>(entry.tick), team_name(entry.team),
                    entry.target, radio_name(entry.code),
                    static_cast<unsigned long long>(entry.decision_revision),
                    static_cast<double>(entry.latency_us) / 1000.0);
            }
        }
    }
    ImGui::EndChild();

    const int racer_index = std::clamp(selected_racer, 0, 7);
    const int history_count = std::min<int>(
        current.history_counts[racer_index], AGUAFRIA_IMGUI_HISTORY_PER_RACER);
    selected_history_offset = std::clamp(
        selected_history_offset, 0, std::max(0, history_count - 1));
    if (show_only_granite && history_count > 0 &&
        current.history[racer_index * AGUAFRIA_IMGUI_HISTORY_PER_RACER +
                        selected_history_offset].source != 1) {
        for (int offset = 0; offset < history_count; ++offset) {
            if (current.history[racer_index * AGUAFRIA_IMGUI_HISTORY_PER_RACER +
                                offset].source == 1) {
                selected_history_offset = offset;
                break;
            }
        }
    }

    ImGui::SeparatorText("Decision history - newest first");
    if (history_count == 0) {
        ImGui::TextUnformatted("No decisions have been recorded for this racer yet.");
    } else {
        const bool history_visible =
            ImGui::BeginChild("decision-history", ImVec2(0.0f, 112.0f), true);
        if (history_visible) {
            for (int offset = 0; offset < history_count; ++offset) {
                const AguafriaImguiRacer& entry =
                    current.history[racer_index * AGUAFRIA_IMGUI_HISTORY_PER_RACER + offset];
                if (!entry.valid || (show_only_granite && entry.source != 1)) {
                    continue;
                }
                char label[192];
                std::snprintf(label, sizeof(label),
                              "#%llu | %s | %.1f ms | %s##history-%d-%d",
                              static_cast<unsigned long long>(entry.revision),
                              source_name(entry.source),
                              static_cast<double>(entry.total_us) / 1000.0,
                              entry.outcome_resolved ? "result ready" : "measuring result",
                              racer_index, offset);
                if (ImGui::Selectable(label, selected_history_offset == offset)) {
                    selected_history_offset = offset;
                }
            }
        }
        ImGui::EndChild();
    }

    ImGui::SeparatorText("Selected decision");
    const AguafriaImguiRacer& racer = history_count > 0
        ? current.history[racer_index * AGUAFRIA_IMGUI_HISTORY_PER_RACER +
                          selected_history_offset]
        : current.racers[racer_index];
    if (racer.valid) {
        draw_summary(racer);
        ImGui::PlotLines("Latency (ms)", latency_history[racer.id], 120,
                         latency_cursor[racer.id], nullptr, 0.0f, 600.0f,
                         ImVec2(0.0f, 70.0f));
        if (!racer.accepted) {
            ImGui::TextColored(ImVec4(1.0f, 0.45f, 0.10f, 1.0f),
                               "Validation rejected this decision.");
        }
        if (show_raw_protocol) {
            ImGui::SeparatorText("Raw protocol (explicitly enabled)");
            if (racer.source == 1 && racer.prompt[0] != '\0') {
                ImGui::Text("Prompt: %s", racer.prompt);
                ImGui::Text("Response: %s (token %u)", racer.response, racer.output_token);
                ImGui::TextUnformatted("Input token IDs:");
                ImGui::SameLine();
                for (uint32_t index = 0;
                     index < std::min<uint32_t>(racer.input_token_count, 8); ++index) {
                    ImGui::Text("%u%s", racer.input_tokens[index],
                                index + 1 == std::min<uint32_t>(racer.input_token_count, 8) ? "" : ",");
                    if (index + 1 != std::min<uint32_t>(racer.input_token_count, 8)) {
                        ImGui::SameLine();
                    }
                }
            } else {
                ImGui::TextUnformatted("No encoded model prompt or output exists for this action.");
            }
        }
    } else {
        ImGui::TextUnformatted("No decision has been recorded for this racer yet.");
    }
    ImGui::End();
}

} // namespace

extern "C" AguafriaImguiBool aguafria_imgui_initialize(
    AguafriaImguiAddress window,
    AguafriaImguiAddress instance,
    AguafriaImguiAddress physical_device,
    AguafriaImguiAddress device,
    AguafriaImguiAddress queue,
    unsigned int queue_family,
    AguafriaImguiAddress render_pass,
    unsigned int image_count) {
    if (initialized) {
        return true;
    }
    if (window == 0 || instance == 0 || physical_device == 0 || device == 0 ||
        queue == 0 || render_pass == 0 || image_count < 2) {
        return false;
    }

    IMGUI_CHECKVERSION();
    ImGui::CreateContext();
    ImGuiIO& io = ImGui::GetIO();
    io.ConfigFlags |= ImGuiConfigFlags_NavEnableKeyboard;
    io.IniFilename = nullptr;

    ImGui::StyleColorsDark();
    ImGuiStyle& style = ImGui::GetStyle();
    style.WindowRounding = 4.0f;
    style.FrameRounding = 3.0f;
    style.Colors[ImGuiCol_CheckMark] = ImVec4(1.0f, 0.78f, 0.0f, 1.0f);
    style.Colors[ImGuiCol_Header] = ImVec4(0.32f, 0.25f, 0.0f, 1.0f);
    style.Colors[ImGuiCol_HeaderHovered] = ImVec4(0.55f, 0.43f, 0.0f, 1.0f);
    style.Colors[ImGuiCol_Button] = ImVec4(0.32f, 0.25f, 0.0f, 1.0f);

    if (!ImGui_ImplGlfw_InitForVulkan(reinterpret_cast<GLFWwindow*>(window), true)) {
        ImGui::DestroyContext();
        return false;
    }

    ImGui_ImplVulkan_InitInfo info = {};
    info.ApiVersion = VK_API_VERSION_1_0;
    info.Instance = reinterpret_cast<VkInstance>(instance);
    info.PhysicalDevice = reinterpret_cast<VkPhysicalDevice>(physical_device);
    info.Device = reinterpret_cast<VkDevice>(device);
    info.QueueFamily = queue_family;
    info.Queue = reinterpret_cast<VkQueue>(queue);
    info.DescriptorPoolSize = 128;
    info.MinImageCount = image_count;
    info.ImageCount = image_count;
    info.PipelineInfoMain.RenderPass = reinterpret_cast<VkRenderPass>(render_pass);
    info.PipelineInfoMain.Subpass = 0;
    info.PipelineInfoMain.MSAASamples = VK_SAMPLE_COUNT_1_BIT;
    if (!ImGui_ImplVulkan_Init(&info)) {
        ImGui_ImplGlfw_Shutdown();
        ImGui::DestroyContext();
        return false;
    }

    monitor_device = info.Device;
    monitor_window = reinterpret_cast<GLFWwindow*>(window);
    const char* monitor_setting = std::getenv("AGUAFRIA_RACING_MONITOR");
    visible = monitor_setting == nullptr ||
        (std::strcmp(monitor_setting, "0") != 0 &&
         std::strcmp(monitor_setting, "false") != 0 &&
         std::strcmp(monitor_setting, "no") != 0 &&
         std::strcmp(monitor_setting, "off") != 0);
    initialized = true;
    return true;
}

extern "C" void aguafria_imgui_update(const AguafriaImguiSnapshot* snapshot) {
    if (snapshot == nullptr) {
        return;
    }
    current = *snapshot;
    for (const AguafriaImguiRacer& racer : current.racers) {
        append_latency(racer);
    }
}

extern "C" void aguafria_imgui_render(AguafriaImguiAddress command_buffer) {
    if (!initialized || command_buffer == 0) {
        return;
    }
    const bool f2 = monitor_window != nullptr &&
        glfwGetKey(monitor_window, GLFW_KEY_F2) == GLFW_PRESS;
    if (f2 && !previous_f2) {
        visible = !visible;
    }
    previous_f2 = f2;
    ImGui_ImplVulkan_NewFrame();
    ImGui_ImplGlfw_NewFrame();
    ImGui::NewFrame();
    draw_track_hud();
    if (visible) {
        draw_monitor();
    }
    ImGui::Render();
    ImGui_ImplVulkan_RenderDrawData(
        ImGui::GetDrawData(), reinterpret_cast<VkCommandBuffer>(command_buffer));
}

extern "C" void aguafria_imgui_set_visible(AguafriaImguiBool next_visible) {
    visible = next_visible;
}

extern "C" AguafriaImguiBool aguafria_imgui_toggle_visible(void) {
    visible = !visible;
    return visible;
}

extern "C" AguafriaImguiBool aguafria_imgui_is_visible(void) {
    return visible;
}

extern "C" void aguafria_imgui_set_raw_protocol(AguafriaImguiBool next_visible) {
    show_raw_protocol = next_visible;
}

extern "C" AguafriaImguiBool aguafria_imgui_raw_protocol_visible(void) {
    return show_raw_protocol;
}

extern "C" unsigned int aguafria_imgui_racer_size(void) {
    return static_cast<unsigned int>(sizeof(AguafriaImguiRacer));
}

extern "C" unsigned int aguafria_imgui_snapshot_size(void) {
    return static_cast<unsigned int>(sizeof(AguafriaImguiSnapshot));
}

extern "C" void aguafria_imgui_shutdown(void) {
    if (!initialized) {
        return;
    }
    vkDeviceWaitIdle(monitor_device);
    ImGui_ImplVulkan_Shutdown();
    ImGui_ImplGlfw_Shutdown();
    ImGui::DestroyContext();
    initialized = false;
    monitor_device = VK_NULL_HANDLE;
    monitor_window = nullptr;
    previous_f2 = false;
    visible = true;
    show_raw_protocol = false;
    std::memset(&current, 0, sizeof(current));
}
