const std = @import("std");
const builtin = @import("builtin");

// Zig discovers and type-checks actual test declarations in test mode. The
// resulting code is linked into a library and invoked by the JVM, not executed
// by a child test runner. The native boundary contains Zig assertion panics.
pub fn main() void {}

export fn aguafria_run_test() callconv(.c) u32 {
    return __aguafria_jvm_guard.call(runTest, .{}) orelse 3;
}

export fn aguafria_test_panic_message() callconv(.c) ?[*:0]const u8 {
    return __aguafria_jvm_guard.aguafria_guard_message();
}

fn runTest() u32 {
    if (builtin.test_functions.len != 1) {
        std.debug.print("Expected one selected test, found {d}\n", .{builtin.test_functions.len});
        return 1;
    }
    std.testing.allocator_instance = .{};
    std.testing.environ = .empty;
    std.testing.io_instance = .init(std.testing.allocator, .{});
    std.testing.log_level = .warn;

    const selected = builtin.test_functions[0];
    std.debug.print("1/1 {s}...", .{selected.name});
    var status: u32 = 0;
    selected.func() catch |err| {
        if (err == error.SkipZigTest) {
            status = 2;
            std.debug.print("SKIP\n", .{});
        } else {
            status = 1;
            std.debug.print("FAIL ({t})\n", .{err});
            if (@errorReturnTrace()) |trace| std.debug.dumpErrorReturnTrace(trace);
        }
    };
    if (status == 0) std.debug.print("OK\n", .{});
    std.testing.io_instance.deinit();
    if (std.testing.allocator_instance.deinit() == .leak) {
        std.debug.print("1 test leaked memory.\n", .{});
        status = 1;
    }
    switch (status) {
        0 => std.debug.print("All 1 tests passed.\n", .{}),
        2 => std.debug.print("0 passed; 1 skipped; 0 failed.\n", .{}),
        else => std.debug.print("0 passed; 0 skipped; 1 failed.\n", .{}),
    }
    return status;
}
