#include "../../native/extension_host.h"
#include <cassert>
#include <chrono>
#include <cstdio>
#include <fstream>
#include <string>
#include <thread>
#include <unistd.h>

uint64_t submit(uint32_t operation, int64_t integer = 0,
                const char *text = nullptr) {
  PitocoCommandV1 command{1,   sizeof(PitocoCommandV1), operation, 0, integer,
                          text};
  uint64_t ticket = 0;
  assert(pitoco_submit_v1(&command, &ticket) == PITOCO_QUEUED);
  assert(ticket > 0);
  return ticket;
}

PitocoStatusV1 status() {
  PitocoStatusV1 value{1, sizeof(PitocoStatusV1)};
  assert(pitoco_status_v1(&value) == PITOCO_OK);
  return value;
}

int main(int argc, char **argv) {
  assert(argc >= 3);
  LabPanel panel{};
  panel.count = 241;
  panel.cursor = 100;
  panel.paused = 1;
  pitoco_tick_v1(&panel);
  assert(status().frames == 241);
  PitocoStatusV1 wrong_status{99, sizeof(PitocoStatusV1)};
  assert(pitoco_status_v1(&wrong_status) == PITOCO_ABI_MISMATCH);
  PitocoCommandV1 wrong{99,     sizeof(PitocoCommandV1), PITOCO_SEEK, 0, 10,
                        nullptr};
  uint64_t ignored = 7;
  assert(pitoco_submit_v1(&wrong, &ignored) == PITOCO_ABI_MISMATCH &&
         ignored == 0);
  wrong.abi_version = 1;
  wrong.struct_size = 4;
  assert(pitoco_submit_v1(&wrong, &ignored) == PITOCO_ABI_MISMATCH);
  wrong.struct_size = sizeof(wrong);
  wrong.reserved = 1;
  assert(pitoco_submit_v1(&wrong, &ignored) == PITOCO_INVALID);
  wrong.reserved = 0;
  std::string huge(4097, 'a');
  wrong.text = huge.c_str();
  assert(pitoco_submit_v1(&wrong, &ignored) == PITOCO_INVALID);
  wrong.text = nullptr;
  auto ticket = submit(PITOCO_SEEK, 180);
  assert(pitoco_submit_v1(&wrong, &ignored) == PITOCO_BUSY);
  assert(pitoco_result_v1(ticket) == PITOCO_QUEUED);
  panel.action = 4;
  pitoco_tick_v1(&panel);
  assert(panel.action == 4 && pitoco_result_v1(ticket) == PITOCO_QUEUED);
  panel.action = 0;
  pitoco_tick_v1(&panel);
  assert(panel.action == 3 && panel.cursor == 180 &&
         pitoco_result_v1(ticket) == PITOCO_OK);
  panel.action = 0;
  ticket = submit(PITOCO_SEEK, 241);
  pitoco_tick_v1(&panel);
  assert(pitoco_result_v1(ticket) == PITOCO_INVALID && panel.cursor == 180);
  panel.baking = 1;
  ticket = submit(PITOCO_PLAY);
  pitoco_tick_v1(&panel);
  assert(pitoco_result_v1(ticket) == PITOCO_BUSY && panel.paused == 1);
  panel.baking = 0;
  std::thread producer([&ticket] { ticket = submit(PITOCO_PLAY); });
  producer.join();
  pitoco_tick_v1(&panel);
  assert(panel.paused == 0 && pitoco_result_v1(ticket) == PITOCO_OK);

  ticket = submit(PITOCO_LOAD, 0, argv[2]);
  pitoco_tick_v1(&panel);
  assert(pitoco_result_v1(ticket) == PITOCO_ABI_MISMATCH &&
         status().plugins == 0);
  char marker[] = "/tmp/pitoco-plugin-cleanup-XXXXXX";
  int marker_fd = mkstemp(marker);
  assert(marker_fd >= 0);
  close(marker_fd);
  unlink(marker);
  setenv("PITOCO_TEST_FAIL_MARKER", marker, 1);
  ticket = submit(PITOCO_LOAD, 0, argv[2]);
  pitoco_tick_v1(&panel);
  assert(pitoco_result_v1(ticket) == PITOCO_PLUGIN_ERROR &&
         status().plugins == 0);
  std::string cleanup_message;
  std::ifstream cleanup_file(marker);
  std::getline(cleanup_file, cleanup_message);
  assert(cleanup_message == "partial state released");
  unlink(marker);
  unsetenv("PITOCO_TEST_FAIL_MARKER");
  for (int cycle = 0; cycle < 3; ++cycle) {
    std::string copied_path(argv[1]);
    ticket = submit(PITOCO_LOAD, 0, copied_path.c_str());
    copied_path.assign("source string is no longer alive");
    pitoco_tick_v1(&panel);
    assert(pitoco_result_v1(ticket) == PITOCO_OK && status().plugins == 1);
    ticket = submit(PITOCO_LOAD, 0, argv[1]);
    pitoco_tick_v1(&panel);
    assert(pitoco_result_v1(ticket) == PITOCO_BUSY && status().plugins == 1);
    panel.cursor = 100;
    ticket = submit(PITOCO_PLUGIN_COMMAND, 0, "example.rewind\trewind");
    pitoco_tick_v1(&panel);
    assert(pitoco_result_v1(ticket) == PITOCO_OK);
    pitoco_tick_v1(&panel);
    assert(panel.cursor == 0 && panel.action == 3);
    assert(pitoco_result_v1(ticket) ==
           PITOCO_OK); // retained after child command
    panel.action = 0;
    ticket = submit(PITOCO_UNLOAD, 0, "example.rewind");
    pitoco_tick_v1(&panel);
    assert(pitoco_result_v1(ticket) == PITOCO_OK && status().plugins == 0);
    ticket = submit(PITOCO_PLUGIN_COMMAND, 0, "example.rewind\trewind");
    pitoco_tick_v1(&panel);
    assert(pitoco_result_v1(ticket) == PITOCO_NOT_FOUND);
  }
  if (argc == 4) {
    assert(pitoco_bridge_open_v1(argv[3]) == PITOCO_OK);
    const auto stop = std::string(argv[3]) + "/stop";
    std::ofstream(std::string(argv[3]) + "/ready") << getpid();
    for (int i = 0; i < 6000 && !std::ifstream(stop); ++i) {
      panel.action = 0;
      pitoco_tick_v1(&panel);
      std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
  }
  pitoco_shutdown_v1();
  assert(status().plugins == 0);
  std::puts("Native ABI, queue, compiled AguaFria plugin lifecycle and command "
            "tests passed.");
}
