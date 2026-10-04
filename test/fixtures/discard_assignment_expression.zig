const std = @import("std");

fn value() !u32 {
    return 42;
}

pub fn exercise(which: u8) !u32 {
    switch (which) {
        0 => _ = try value(),
        else => return 1,
    }
    return 42;
}

test "discard assignment as a switch branch" {
    try std.testing.expectEqual(42, try exercise(0));
    try std.testing.expectEqual(1, try exercise(1));
}
