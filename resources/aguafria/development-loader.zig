//! Generated-development publication listener.
//!
//! This file is copied into an Aguafria materialization, never into the
//! user's Zig checkout. The native application shell only calls its existing
//! Zig library API; the library starts and owns this listener itself.

const std = @import("std");
const c = @cImport({
    @cInclude("dlfcn.h");
    @cInclude("stdio.h");
    @cInclude("stdlib.h");
    @cInclude("unistd.h");
});

pub export var __aguafria_external_publication_epoch: usize = 0;

var started: std.atomic.Value(bool) = .init(false);
var initial_scan_complete: std.atomic.Value(bool) = .init(false);

const Getter = *const fn () callconv(.c) usize;
const Setter = *const fn (usize) callconv(.c) void;
const EpochSetter = *const fn (usize) callconv(.c) void;

const WatchState = struct {
    offset: c_long = 0,
    application: ?*anyopaque,
    generation: u64 = 0,
    library: ?*anyopaque = null,
    libraries: ?*Library = null,
    publishing: bool = false,
    failed: bool = false,
};

const Library = struct {
    handle: *anyopaque,
    next: ?*Library,
};

fn generationLibraryFlags() c_int {
    var flags: c_int = c.RTLD_NOW | c.RTLD_LOCAL;
    // macOS otherwise permits `dlsym(generation_handle, stable_getter)` to
    // resolve the first previously loaded generation with that exported name.
    // RTLD_FIRST makes the handle identify the new image itself. Other
    // platforms retain their normal handle-scoped lookup when unavailable.
    if (comptime @hasDecl(c, "RTLD_FIRST")) flags |= c.RTLD_FIRST;
    return flags;
}

fn applicationSymbol(state: *const WatchState, name: [*:0]const u8) ?*anyopaque {
    // `dlopen(NULL)` may search globally loaded dylibs after the executable on
    // macOS. Once generations are retained, that can find another generation's
    // stable setter and report success without updating the actual program.
    if (comptime @hasDecl(c, "RTLD_MAIN_ONLY")) {
        return c.dlsym(c.RTLD_MAIN_ONLY, name);
    }
    return c.dlsym(state.application, name);
}

pub fn start() bool {
    const manifest = c.getenv("AGUAFRIA_PUBLICATION_MANIFEST") orelse return false;
    if (started.cmpxchgStrong(false, true, .acq_rel, .acquire) != null) return true;

    const thread = std.Thread.spawn(.{}, watch, .{manifest}) catch |err| {
        started.store(false, .release);
        std.debug.print("Aguafria could not start its publication listener: {}\n", .{err});
        return false;
    };
    thread.detach();
    // Calls later in the application's initialization must observe every
    // generation that already existed when the process started. Continuing
    // before the watcher drains that prefix can enter an old long-running
    // function (for example a REPL loop) milliseconds before its setter moves.
    while (!initial_scan_complete.load(.acquire)) {
        _ = c.usleep(1_000);
    }
    return true;
}

fn watch(manifest: [*c]u8) void {
    var state = WatchState{
        .application = c.dlopen(null, c.RTLD_NOW | c.RTLD_LOCAL),
    };
    if (state.application == null) {
        std.debug.print("Aguafria could not open the running program for symbol lookup\n", .{});
        return;
    }

    readAvailable(manifest, &state);
    initial_scan_complete.store(true, .release);

    while (true) {
        _ = c.usleep(25_000);
        readAvailable(manifest, &state);
    }
}

fn readAvailable(manifest: [*c]u8, state: *WatchState) void {
    const file = c.fopen(manifest, "r") orelse return;
    defer _ = c.fclose(file);

    if (c.fseek(file, 0, c.SEEK_END) != 0) return;
    const size = c.ftell(file);
    if (size < 0) return;
    if (size < state.offset) {
        finishPublication(state);
        state.offset = 0;
    }
    if (c.fseek(file, state.offset, c.SEEK_SET) != 0) return;

    var buffer: [16 * 1024]u8 = undefined;
    while (true) {
        const line_offset = c.ftell(file);
        if (line_offset < 0) return;
        if (c.fgets(&buffer, @intCast(buffer.len), file) == null) return;

        const line = std.mem.sliceTo(buffer[0..], 0);
        if (line.len == 0 or line[line.len - 1] != '\n') {
            _ = c.fseek(file, line_offset, c.SEEK_SET);
            return;
        }

        state.offset = c.ftell(file);
        processLine(std.mem.trimEnd(u8, line, "\r\n"), state);
    }
}

fn processLine(line: []const u8, state: *WatchState) void {
    var fields = std.mem.splitScalar(u8, line, '\t');
    const operation = fields.next() orelse return;

    if (std.mem.eql(u8, operation, "B")) {
        finishPublication(state);
        const generation_text = fields.next() orelse return;
        _ = fields.next() orelse return; // logical module, retained for diagnostics
        const library_path = fields.next() orelse return;
        _ = fields.next() orelse return; // dispatch-cell count
        const epoch_setter_name = fields.next() orelse return;

        state.generation = std.fmt.parseUnsigned(u64, generation_text, 10) catch return;
        var path_buffer: [4096:0]u8 = undefined;
        const path = copyCString(library_path, &path_buffer) orelse return;
        state.library = c.dlopen(path, generationLibraryFlags());
        state.failed = state.library == null;
        state.publishing = true;
        _ = @atomicRmw(usize, &__aguafria_external_publication_epoch, .Add, 1, .acq_rel);

        if (state.library) |library| {
            const allocation = c.malloc(@sizeOf(Library)) orelse {
                state.failed = true;
                std.debug.print("Aguafria could not retain generation {d}\n", .{
                    state.generation,
                });
                return;
            };
            const retained: *Library = @ptrCast(@alignCast(allocation));
            retained.* = .{ .handle = library, .next = state.libraries };
            state.libraries = retained;
        }

        var epoch_setter_buffer: [512:0]u8 = undefined;
        const epoch_setter_c = copyCString(epoch_setter_name, &epoch_setter_buffer) orelse {
            state.failed = true;
            return;
        };
        const epoch_setter_address = applicationSymbol(state, epoch_setter_c) orelse {
            state.failed = true;
            std.debug.print("The development lib has no publication epoch {s}\n", .{
                epoch_setter_name,
            });
            return;
        };
        const epoch_setter: EpochSetter = @ptrCast(@alignCast(epoch_setter_address));
        epoch_setter(@intFromPtr(&__aguafria_external_publication_epoch));

        if (state.failed) {
            std.debug.print("Aguafria could not load generation {d}: {s}\n", .{
                state.generation,
                dlError(),
            });
        }
        return;
    }

    if (std.mem.eql(u8, operation, "S")) {
        if (!state.publishing or state.failed) return;
        const getter_name = fields.next() orelse return;
        const setter_name = fields.next() orelse return;

        var getter_buffer: [512:0]u8 = undefined;
        var setter_buffer: [512:0]u8 = undefined;
        const getter_c = copyCString(getter_name, &getter_buffer) orelse {
            state.failed = true;
            return;
        };
        const setter_c = copyCString(setter_name, &setter_buffer) orelse {
            state.failed = true;
            return;
        };

        const getter_address = c.dlsym(state.library, getter_c) orelse {
            state.failed = true;
            std.debug.print("Aguafria generation {d} has no getter {s}\n", .{
                state.generation,
                getter_name,
            });
            return;
        };
        const getter: Getter = @ptrCast(@alignCast(getter_address));
        var setter_count: usize = 0;
        if (applicationSymbol(state, setter_c)) |setter_address| {
            const setter: Setter = @ptrCast(@alignCast(setter_address));
            setter(getter());
            setter_count += 1;
        }

        var retained = state.libraries;
        while (retained) |library| : (retained = library.next) {
            if (c.dlsym(library.handle, setter_c)) |setter_address| {
                const setter: Setter = @ptrCast(@alignCast(setter_address));
                setter(getter());
                setter_count += 1;
            }
        }

        if (setter_count == 0) {
            state.failed = true;
            std.debug.print("The development lib has no dispatch cell {s}\n", .{setter_name});
            return;
        }
        return;
    }

    if (std.mem.eql(u8, operation, "E")) finishPublication(state);
}

fn finishPublication(state: *WatchState) void {
    if (!state.publishing) return;
    _ = @atomicRmw(usize, &__aguafria_external_publication_epoch, .Add, 1, .release);
    if (!state.failed) {
        std.debug.print("Aguafria published native generation {d}\n", .{state.generation});
    }
    state.publishing = false;
    state.failed = false;
    state.library = null;
}

fn copyCString(source: []const u8, destination: anytype) ?[*:0]const u8 {
    if (source.len >= destination.len) return null;
    @memcpy(destination[0..source.len], source);
    destination[source.len] = 0;
    return destination[0..source.len :0].ptr;
}

fn dlError() []const u8 {
    const message = c.dlerror() orelse return "unknown dynamic-loader error";
    return std.mem.span(message);
}
