#include "extension_host.h"
#include <array>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <fstream>
#include <mutex>
#include <string>
#include <sys/file.h>
#include <sys/stat.h>
#include <unistd.h>

namespace {
struct Pending {
  uint32_t operation = 0;
  int64_t integer = 0;
  uint64_t ticket = 0;
  std::string text;
};

struct Plugin {
  void *library = nullptr;
  const PitocoPluginV1 *api = nullptr;
  void *state = nullptr;
  std::string id;
};

std::mutex mailbox;
Pending pending;
bool occupied = false;
uint64_t executing_ticket = 0;
struct Completion {
  uint64_t ticket = 0;
  uint32_t result = 0;
};
std::array<Completion, 64> completions;
uint64_t next_ticket = 1;
PitocoStatusV1 snapshot{PITOCO_ABI_V1, sizeof(PitocoStatusV1)};
std::array<Plugin, 16> plugins;
std::string bridge_directory;
std::string bridge_response;
int bridge_lock = -1;
bool checked_environment = false;

const PitocoHostV1 host{PITOCO_ABI_V1, sizeof(PitocoHostV1), pitoco_submit_v1,
                        pitoco_status_v1};

bool valid_id(const char *id) {
  if (!id || !*id || strnlen(id, 65) > 64)
    return false;
  for (const char *p = id; *p; ++p)
    if (!((*p >= 'a' && *p <= 'z') || (*p >= '0' && *p <= '9') || *p == '-' ||
          *p == '_' || *p == '.'))
      return false;
  return true;
}

Plugin *find_plugin(const std::string &id) {
  for (auto &plugin : plugins)
    if (plugin.library && plugin.id == id)
      return &plugin;
  return nullptr;
}

uint32_t load_plugin(const std::string &path) {
  if (path.empty() || path.front() != '/')
    return PITOCO_INVALID;
  Plugin *slot = nullptr;
  for (auto &plugin : plugins)
    if (!plugin.library) {
      slot = &plugin;
      break;
    }
  if (!slot)
    return PITOCO_BUSY;
  void *library = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
  if (!library)
    return PITOCO_NOT_FOUND;
  auto entry =
      reinterpret_cast<PitocoPluginEntryV1>(dlsym(library, "pitoco_plugin_v1"));
  const PitocoPluginV1 *api = entry ? entry() : nullptr;
  if (!api || api->abi_version != PITOCO_ABI_V1 ||
      api->struct_size < sizeof(PitocoPluginV1)) {
    dlclose(library);
    return PITOCO_ABI_MISMATCH;
  }
  if (!valid_id(api->id) || !api->on_load || !api->on_unload ||
      !api->on_command) {
    dlclose(library);
    return PITOCO_INVALID;
  }
  if (find_plugin(api->id)) {
    dlclose(library);
    return PITOCO_BUSY;
  }
  void *state = nullptr;
  if (api->on_load(&host, &state) != PITOCO_OK) {
    // The plugin must accept on_unload even after partial initialization.
    api->on_unload(state);
    dlclose(library);
    return PITOCO_PLUGIN_ERROR;
  }
  *slot = Plugin{library, api, state, api->id};
  return PITOCO_OK;
}

uint32_t unload_plugin(const std::string &id) {
  auto *plugin = find_plugin(id);
  if (!plugin)
    return PITOCO_NOT_FOUND;
  plugin->api->on_unload(plugin->state);
  dlclose(plugin->library);
  *plugin = Plugin{};
  return PITOCO_OK;
}

uint32_t execute(const Pending &request, LabPanel *panel) {
  switch (request.operation) {
  case PITOCO_SEEK:
    if (panel->baking)
      return PITOCO_BUSY;
    if (request.integer < 0 || request.integer >= panel->count)
      return PITOCO_INVALID;
    panel->cursor = static_cast<int>(request.integer);
    panel->paused = 1;
    panel->action = 3;
    return PITOCO_OK;
  case PITOCO_PAUSE:
    panel->paused = 1;
    return PITOCO_OK;
  case PITOCO_PLAY:
    if (panel->baking)
      return PITOCO_BUSY;
    panel->paused = 0;
    return PITOCO_OK;
  case PITOCO_EXPORT:
    if (panel->baking)
      return PITOCO_BUSY;
    panel->action = 4;
    return PITOCO_OK;
  case PITOCO_STOP:
    panel->action = 8;
    return PITOCO_OK;
  case PITOCO_LOAD:
    return load_plugin(request.text);
  case PITOCO_UNLOAD:
    return unload_plugin(request.text);
  case PITOCO_BRIDGE:
    return pitoco_bridge_open_v1(request.text.c_str());
  case PITOCO_PLUGIN_COMMAND: {
    const auto separator = request.text.find('\t');
    if (separator == std::string::npos)
      return PITOCO_INVALID;
    auto *plugin = find_plugin(request.text.substr(0, separator));
    if (!plugin)
      return PITOCO_NOT_FOUND;
    return plugin->api->on_command(plugin->state,
                                   request.text.c_str() + separator + 1);
  }
  default:
    return PITOCO_INVALID;
  }
}

void publish(const LabPanel *panel) {
  std::lock_guard<std::mutex> lock(mailbox);
  snapshot.frames = panel->count;
  snapshot.cursor = panel->cursor;
  snapshot.revision = panel->revision;
  snapshot.baking = panel->baking != 0;
  snapshot.paused = panel->paused != 0;
  snapshot.plugins = 0;
  for (const auto &plugin : plugins)
    snapshot.plugins += plugin.library != nullptr;
}

// Deliberately small transport: one atomically published request at a time.
// The Clojure API is the authoring DSL; this framing never evaluates source
// code.
void poll_bridge() {
  if (bridge_directory.empty())
    return;
  const auto request_path = bridge_directory + "/request";
  const auto reply_path = bridge_directory + "/reply";
  struct stat reply_info{};
  if (stat(reply_path.c_str(), &reply_info) == 0)
    return;
  if (bridge_response.empty()) {
    std::ifstream input(request_path, std::ios::binary);
    if (!input)
      return;
    char bytes[8193];
    input.read(bytes, sizeof(bytes));
    std::string data(bytes, static_cast<size_t>(input.gcount()));
    std::string lines[4];
    size_t offset = 0;
    bool valid = data.size() < sizeof(bytes);
    for (auto &line : lines) {
      const auto end = data.find('\n', offset);
      if (end == std::string::npos) {
        valid = false;
        break;
      }
      line = data.substr(offset, end - offset);
      offset = end + 1;
    }
    valid = valid && offset == data.size() && lines[0] == "PITOCO/1" &&
            lines[3].size() <= PITOCO_TEXT_LIMIT &&
            data.find('\0') == std::string::npos;
    uint32_t result = PITOCO_INVALID;
    uint64_t ticket = 0;
    if (valid && lines[1] == "status" && lines[2] == "0" && lines[3].empty()) {
      result = PITOCO_OK;
    } else if (valid && lines[1] == "result" && lines[3].empty()) {
      char *end = nullptr;
      errno = 0;
      ticket = strtoull(lines[2].c_str(), &end, 10);
      if (errno == 0 && !lines[2].empty() && lines[2][0] != '-' && *end == '\0')
        result = pitoco_result_v1(ticket);
    } else if (valid) {
      char *op_end = nullptr, *integer_end = nullptr;
      errno = 0;
      long operation = strtol(lines[1].c_str(), &op_end, 10);
      long long integer = strtoll(lines[2].c_str(), &integer_end, 10);
      valid = errno == 0 && !lines[1].empty() && !lines[2].empty() &&
              *op_end == '\0' && *integer_end == '\0' && operation >= 1 &&
              operation <= 9;
      if (valid) {
        PitocoCommandV1 command{PITOCO_ABI_V1,
                                sizeof(PitocoCommandV1),
                                static_cast<uint32_t>(operation),
                                0,
                                integer,
                                lines[3].c_str()};
        result = pitoco_submit_v1(&command, &ticket);
      }
    }
    PitocoStatusV1 status{PITOCO_ABI_V1, sizeof(PitocoStatusV1)};
    pitoco_status_v1(&status);
    char response[512];
    snprintf(response, sizeof(response),
             "{:protocol 1 :result %u :ticket %llu :last-ticket %llu "
             ":last-result %u "
             ":frames %u :cursor %u :revision %u :baking? %s :paused? %s "
             ":plugins %u}\n",
             result, static_cast<unsigned long long>(ticket),
             static_cast<unsigned long long>(status.last_ticket),
             status.last_result, status.frames, status.cursor, status.revision,
             status.baking ? "true" : "false", status.paused ? "true" : "false",
             status.plugins);
    bridge_response = response;
  }
  const auto temporary = bridge_directory + "/reply.tmp";
  FILE *out = fopen(temporary.c_str(), "wb");
  if (!out)
    return;
  const bool written = fwrite(bridge_response.data(), 1, bridge_response.size(),
                              out) == bridge_response.size();
  const bool closed = fclose(out) == 0;
  if (written && closed &&
      (std::remove(request_path.c_str()) == 0 || errno == ENOENT) &&
      std::rename(temporary.c_str(), reply_path.c_str()) == 0)
    bridge_response.clear();
}
} // namespace

extern "C" uint32_t pitoco_submit_v1(const PitocoCommandV1 *command,
                                     uint64_t *ticket) {
  if (!command || !ticket)
    return PITOCO_INVALID;
  *ticket = 0;
  if (command->abi_version != PITOCO_ABI_V1 ||
      command->struct_size < sizeof(PitocoCommandV1))
    return PITOCO_ABI_MISMATCH;
  if (command->reserved || command->operation < 1 || command->operation > 9)
    return PITOCO_INVALID;
  if (command->text &&
      strnlen(command->text, PITOCO_TEXT_LIMIT + 1) > PITOCO_TEXT_LIMIT)
    return PITOCO_INVALID;
  std::lock_guard<std::mutex> lock(mailbox);
  if (occupied)
    return PITOCO_BUSY;
  pending = Pending{command->operation, command->integer, next_ticket++,
                    command->text ? command->text : ""};
  *ticket = pending.ticket;
  occupied = true;
  return PITOCO_QUEUED;
}

extern "C" uint32_t pitoco_status_v1(PitocoStatusV1 *status) {
  if (!status)
    return PITOCO_INVALID;
  if (status->abi_version != PITOCO_ABI_V1 ||
      status->struct_size < sizeof(PitocoStatusV1))
    return PITOCO_ABI_MISMATCH;
  std::lock_guard<std::mutex> lock(mailbox);
  *status = snapshot;
  return PITOCO_OK;
}

extern "C" uint32_t pitoco_result_v1(uint64_t ticket) {
  std::lock_guard<std::mutex> lock(mailbox);
  if (!ticket)
    return PITOCO_INVALID;
  const auto &completion = completions[ticket % completions.size()];
  if (completion.ticket == ticket)
    return completion.result;
  if (executing_ticket == ticket || (occupied && pending.ticket == ticket))
    return PITOCO_QUEUED;
  return PITOCO_NOT_FOUND;
}

extern "C" uint32_t pitoco_bridge_open_v1(const char *directory) {
  checked_environment = true;
  if (!directory || directory[0] != '/' || strnlen(directory, 4097) > 4096)
    return PITOCO_INVALID;
  struct stat info{};
  if (stat(directory, &info) != 0 || !S_ISDIR(info.st_mode))
    return PITOCO_IO_ERROR;
  const std::string lock_path = std::string(directory) + "/.host.lock";
  const int descriptor = open(lock_path.c_str(), O_CREAT | O_RDWR, 0600);
  if (descriptor < 0)
    return PITOCO_IO_ERROR;
  if (flock(descriptor, LOCK_EX | LOCK_NB) != 0) {
    close(descriptor);
    return PITOCO_BUSY;
  }
  if (bridge_lock >= 0)
    close(bridge_lock);
  bridge_lock = descriptor;
  bridge_directory = directory;
  bridge_response.clear();
  return PITOCO_OK;
}

extern "C" void pitoco_bridge_close_v1() {
  checked_environment = true;
  bridge_directory.clear();
  bridge_response.clear();
  if (bridge_lock >= 0)
    close(bridge_lock);
  bridge_lock = -1;
}

extern "C" void pitoco_tick_v1(LabPanel *panel) {
  if (!checked_environment) {
    checked_environment = true;
    if (const char *directory = std::getenv("PITOCO_BRIDGE_DIR"))
      pitoco_bridge_open_v1(directory);
  }
  publish(panel);
  poll_bridge();
  Pending request;
  bool available = false;
  {
    std::lock_guard<std::mutex> lock(mailbox);
    if (occupied && panel->action == 0) {
      request = std::move(pending);
      occupied = false;
      executing_ticket = request.ticket;
      available = true;
    }
  }
  if (available) {
    // No mailbox lock during callbacks: plugins may queue their next host
    // command.
    const auto result = execute(request, panel);
    std::lock_guard<std::mutex> lock(mailbox);
    completions[request.ticket % completions.size()] =
        Completion{request.ticket, result};
    executing_ticket = 0;
    snapshot.last_ticket = request.ticket;
    snapshot.last_result = result;
  }
  publish(panel);
}

extern "C" void pitoco_shutdown_v1() {
  for (auto &plugin : plugins)
    if (plugin.library)
      unload_plugin(plugin.id);
  pitoco_bridge_close_v1();
  std::lock_guard<std::mutex> lock(mailbox);
  pending = Pending{};
  occupied = false;
  snapshot.plugins = 0;
}
