const std = @import("std");
pub const TestOnly = @import("test_only.zig").TestOnly;
const test_only_answer = TestOnly.answer();

comptime {
    _ = @import("test_only.zig");
}

pub fn answer() u32 {
    return 42;
}

test "test-only import" {
    TestOnly.count += 1;
    try std.testing.expectEqual(1, TestOnly.count);
    try std.testing.expectEqual(42, test_only_answer);
}
