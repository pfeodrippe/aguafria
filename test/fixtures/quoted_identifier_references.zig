const std = @import("std");

pub fn _(value: u32) u32 {
    return value + 1;
}

pub fn @"Enum"(value: u32) u32 {
    return value + 2;
}

pub fn exercise(@"value": u32) u32 {
    const @"local" = value;
    return @"_"(local) + Enum(@"local");
}

test "quoted and plain spellings refer to the same declaration" {
    try std.testing.expectEqual(41, exercise(19));
}
