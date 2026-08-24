#ifndef AGUAFRIA_EXAMPLES_NATIVE_IMGUI_MONITOR_H
#define AGUAFRIA_EXAMPLES_NATIVE_IMGUI_MONITOR_H

#ifdef __cplusplus
extern "C" {
typedef bool AguafriaImguiBool;
#else
typedef _Bool AguafriaImguiBool;
#endif

typedef unsigned long long AguafriaImguiAddress;

#define AGUAFRIA_IMGUI_RACER_COUNT 8
#define AGUAFRIA_IMGUI_HISTORY_PER_RACER 64
#define AGUAFRIA_IMGUI_HISTORY_COUNT \
    (AGUAFRIA_IMGUI_RACER_COUNT * AGUAFRIA_IMGUI_HISTORY_PER_RACER)
#define AGUAFRIA_IMGUI_OBSERVATION_TOKEN_COUNT 8
#define AGUAFRIA_IMGUI_TEAM_COUNT 4
#define AGUAFRIA_IMGUI_RADIO_PER_TEAM 32
#define AGUAFRIA_IMGUI_RADIO_COUNT \
    (AGUAFRIA_IMGUI_TEAM_COUNT * AGUAFRIA_IMGUI_RADIO_PER_TEAM)

typedef struct AguafriaImguiRacer {
    AguafriaImguiBool valid;
    AguafriaImguiBool detailed_observation;
    AguafriaImguiBool urgent;
    AguafriaImguiBool pending;
    AguafriaImguiBool accepted;
    AguafriaImguiBool outcome_resolved;
    AguafriaImguiBool outcome_item_used;
    unsigned char id;
    unsigned char team;
    unsigned char teammate;
    unsigned char pit_state;
    unsigned char pit_stops;
    unsigned char damage_stage;
    unsigned char radio_code;
    unsigned char radio_source;
    unsigned char team_instruction;
    AguafriaImguiBool team_pending;
    unsigned char source;
    unsigned char rank;
    unsigned char item;
    unsigned char target;
    unsigned char persona;
    unsigned char target_lane;
    unsigned char tactical_status;
    unsigned char lane_choice;
    unsigned char pace_choice;
    unsigned char item_choice;
    unsigned char deadline_status;
    unsigned char start_rank;
    unsigned char end_rank;
    unsigned short lap;
    unsigned short hits_dealt;
    unsigned char progress_bin;
    unsigned char speed_bin;
    unsigned char target_distance_bin;
    unsigned char model_step_count;
    unsigned long long revision;
    unsigned long long radio_revision;
    unsigned long long team_decision_revision;
    unsigned long long team_decisions;
    unsigned long long team_last_latency_us;
    unsigned long long team_average_latency_us;
    unsigned long long decisions;
    unsigned long long deadline_misses;
    unsigned long long pending_age_ticks;
    unsigned long long queue_us;
    unsigned long long total_us;
    float progress;
    float speed;
    float steps_per_second;
    float progress_gain;
    float tire_condition;
    float damage;
    float pit_seconds;
    char prompt[9];
    char response[2];
    unsigned int input_tokens[AGUAFRIA_IMGUI_OBSERVATION_TOKEN_COUNT];
    unsigned int input_token_count;
    unsigned int output_token;
} AguafriaImguiRacer;

typedef struct AguafriaImguiRadio {
    AguafriaImguiBool valid;
    unsigned char team;
    unsigned char source;
    unsigned char target;
    unsigned char code;
    unsigned char pit_state;
    unsigned char instruction;
    unsigned char reserved;
    unsigned long long tick;
    unsigned long long decision_revision;
    unsigned long long latency_us;
    float tire_condition;
    float damage;
} AguafriaImguiRadio;

typedef struct AguafriaImguiSnapshot {
    unsigned long long tick;
    unsigned long long total_decisions;
    unsigned long long llm_decisions;
    unsigned long long fallback_decisions;
    unsigned long long rejected_decisions;
    unsigned long long deadline_misses;
    unsigned long long resolved_outcomes;
    unsigned long long worker_requests;
    unsigned long long worker_results;
    unsigned long long worker_state_bytes;
    unsigned int pending_requests;
    float average_steps_per_second;
    AguafriaImguiRacer racers[AGUAFRIA_IMGUI_RACER_COUNT];
    unsigned char history_counts[AGUAFRIA_IMGUI_RACER_COUNT];
    AguafriaImguiRacer history[AGUAFRIA_IMGUI_HISTORY_COUNT];
    unsigned char radio_counts[AGUAFRIA_IMGUI_TEAM_COUNT];
    AguafriaImguiRadio radio[AGUAFRIA_IMGUI_RADIO_COUNT];
} AguafriaImguiSnapshot;

AguafriaImguiBool aguafria_imgui_initialize(
    AguafriaImguiAddress window,
    AguafriaImguiAddress instance,
    AguafriaImguiAddress physical_device,
    AguafriaImguiAddress device,
    AguafriaImguiAddress queue,
    unsigned int queue_family,
    AguafriaImguiAddress render_pass,
    unsigned int image_count);

void aguafria_imgui_update(const AguafriaImguiSnapshot* snapshot);
void aguafria_imgui_render(AguafriaImguiAddress command_buffer);
void aguafria_imgui_set_visible(AguafriaImguiBool visible);
AguafriaImguiBool aguafria_imgui_toggle_visible(void);
AguafriaImguiBool aguafria_imgui_is_visible(void);
void aguafria_imgui_set_raw_protocol(AguafriaImguiBool visible);
AguafriaImguiBool aguafria_imgui_raw_protocol_visible(void);
unsigned int aguafria_imgui_racer_size(void);
unsigned int aguafria_imgui_snapshot_size(void);
void aguafria_imgui_shutdown(void);

#ifdef __cplusplus
}
#endif

#endif
