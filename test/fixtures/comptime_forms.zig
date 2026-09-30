const std = @import("std");

fn answer() u32 {
    return comptime 42;
}

test "comptime expressions and statements" {
    comptime std.debug.assert(answer() == 42);
    comptime {
        var x: u32 = 0;
        x += 1;
        std.debug.assert(x == 1);
    }
    try std.testing.expectEqual(42, answer());
}
