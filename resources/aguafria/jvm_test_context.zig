const __aguafria_test_std = @import("std");
var __aguafria_test_initialized = false;

// The JVM loads test-resource libraries without running Zig's test runner.
// Initialize its std.testing state once, before exposing any native values.
export fn __aguafria_jvm_init_test_context() callconv(.c) void {
    if (__aguafria_test_initialized) return;
    __aguafria_test_std.testing.allocator_instance = .init(__aguafria_test_std.heap.page_allocator, .{});
    __aguafria_test_std.testing.environ = .empty;
    __aguafria_test_std.testing.io_instance = .init(__aguafria_test_std.testing.allocator, .{});
    __aguafria_test_std.testing.log_level = .warn;
    __aguafria_test_initialized = true;
}
