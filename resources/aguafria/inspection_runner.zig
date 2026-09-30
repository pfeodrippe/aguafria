// Compile-only inspection root, used with --test-no-exec and -fno-emit-bin.
// The C calling convention prevents std.start from exporting another `main`
// when the inspected module deliberately exports its own C entry point.
pub fn main() callconv(.c) void {
    for (@import("builtin").test_functions) |test_fn| {
        _ = test_fn.func;
    }
}

comptime {
    _ = &main;
}
