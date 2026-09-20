const std = @import("std");

fn chooseWord(comptime bits: usize) type {
    return for (.{ u8, u16, u32, u64 }) |T| {
        if (@bitSizeOf(T) >= bits) break T;
    } else unreachable;
}

test "for else unreachable remains a keyword expression" {
    try std.testing.expectEqual(u16, chooseWord(9));
}
