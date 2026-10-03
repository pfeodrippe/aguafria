const std = @import("std");

const Thing = struct {
    value: u32,

    pub fn init(value: u32) @This() {
        return .{ .value = value };
    }
};

pub fn make() Thing {
    return .init(7);
}

test "inferred member call" {
    try std.testing.expectEqual(7, make().value);
}
