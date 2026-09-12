#include "../../native/sdk/pitoco.h"
#include <cstdio>
#include <cstdlib>

uint32_t fail_load(const PitocoHostV1 *, void **state) {
  *state = std::malloc(32);
  return PITOCO_PLUGIN_ERROR;
}

void cleanup(void *state) {
  std::free(state);
  if (auto *file = std::fopen(std::getenv("PITOCO_TEST_FAIL_MARKER"), "wb")) {
    std::fputs("partial state released", file);
    std::fclose(file);
  }
}

uint32_t command(void *, const char *) { return PITOCO_INVALID; }

extern "C" const PitocoPluginV1 *pitoco_plugin_v1() {
  static const PitocoPluginV1 wrong{999, sizeof(PitocoPluginV1)};
  static const PitocoPluginV1 partial{
      1, sizeof(PitocoPluginV1), "test.partial", fail_load, cleanup, command};
  return std::getenv("PITOCO_TEST_FAIL_MARKER") ? &partial : &wrong;
}
