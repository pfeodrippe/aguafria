const std = @import("std");

pub const Example = extern struct {
    value: Word,
    tail: u8,

    pub fn result(seed: u32) u32 {
        return bump(seed) + bonus;
    }

    const Word = u32;
    const bonus = 7;

    fn bump(value: Word) Word {
        return value + 1;
    }

    pub const CycleA = struct { b: ?*CycleB };
    pub const CycleB = struct { a: ?*CycleA };
};

test "container declaration dependencies" {
    try std.testing.expectEqual(12, Example.result(4));
    try std.testing.expectEqual(@sizeOf(?*anyopaque), @sizeOf(Example.CycleA));
    try std.testing.expect(@offsetOf(Example, "value") < @offsetOf(Example, "tail"));
}
