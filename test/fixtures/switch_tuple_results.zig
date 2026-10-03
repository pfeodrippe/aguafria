const std = @import("std");

const Helpers = struct {
    pub fn extra() u32 {
        return 2;
    }
};

pub fn choose(flag: bool) struct { u32, u32 } {
    return switch (flag) {
        true => .{ 1, Helpers.extra() },
        false => .{ 3, 4 },
    };
}

test "switch tuple results" {
    try std.testing.expectEqual(2, choose(true)[1]);
    try std.testing.expectEqual(3, choose(false)[0]);
}
