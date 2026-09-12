#ifndef PITOCO_SDK_H
#define PITOCO_SDK_H
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif

#define PITOCO_ABI_V1 1u
#define PITOCO_TEXT_LIMIT 4096u

enum PitocoOperation {
  PITOCO_SEEK = 1,
  PITOCO_PAUSE = 2,
  PITOCO_PLAY = 3,
  PITOCO_EXPORT = 4,
  PITOCO_STOP = 5,
  PITOCO_LOAD = 6,
  PITOCO_UNLOAD = 7,
  PITOCO_PLUGIN_COMMAND = 8,
  PITOCO_BRIDGE = 9
};

enum PitocoResult {
  PITOCO_OK = 0,
  PITOCO_QUEUED = 1,
  PITOCO_BUSY = 2,
  PITOCO_INVALID = 3,
  PITOCO_ABI_MISMATCH = 4,
  PITOCO_NOT_FOUND = 5,
  PITOCO_PLUGIN_ERROR = 6,
  PITOCO_IO_ERROR = 7
};

/* Commands copy text before returning. No scene or solver pointers cross here.
 */
typedef struct PitocoCommandV1 {
  uint32_t abi_version, struct_size, operation, reserved;
  int64_t integer;
  const char *text;
} PitocoCommandV1;

typedef struct PitocoStatusV1 {
  uint32_t abi_version, struct_size;
  uint64_t last_ticket;
  uint32_t last_result, frames, cursor, revision;
  uint32_t baking, paused, plugins, reserved;
} PitocoStatusV1;

typedef struct PitocoHostV1 {
  uint32_t abi_version, struct_size;
  uint32_t (*submit)(const PitocoCommandV1 *, uint64_t *ticket);
  uint32_t (*status)(PitocoStatusV1 *);
} PitocoHostV1;

/* All lifecycle/command callbacks run on the host's owning thread.
 * on_unload must join plugin workers and release plugin-owned resources.
 * No callback or worker may retain host pointers after on_unload returns. */
typedef struct PitocoPluginV1 {
  uint32_t abi_version, struct_size;
  const char *id;
  uint32_t (*on_load)(const PitocoHostV1 *host, void **state);
  void (*on_unload)(void *state);
  uint32_t (*on_command)(void *state, const char *command);
} PitocoPluginV1;

typedef const PitocoPluginV1 *(*PitocoPluginEntryV1)(void);
/* Every plugin exports: const PitocoPluginV1 *pitoco_plugin_v1(void). */

uint32_t pitoco_submit_v1(const PitocoCommandV1 *, uint64_t *ticket);
uint32_t pitoco_status_v1(PitocoStatusV1 *);
/* Completion history retains the most recent 64 commands. */
uint32_t pitoco_result_v1(uint64_t ticket);
/* Optional local file transport; open/close only on the owning thread. */
uint32_t pitoco_bridge_open_v1(const char *directory);
void pitoco_bridge_close_v1(void);
void pitoco_shutdown_v1(void);
#ifdef __cplusplus
}
#endif
#endif
