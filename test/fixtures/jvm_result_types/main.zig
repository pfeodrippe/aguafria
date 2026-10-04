const sizes = @import("./sizes.zig");

pub const storage = true;
pub const ErrorUnion = true;
pub const name = true;
pub const items = true;

pub fn anonymous() struct { left: u32, right: u32 } {
    return .{ .left = 3, .right = 5 };
}

pub fn make_array() [sizes.count]u32 {
    return .{ 3, 5 };
}

pub fn optional(present: bool) ?u32 {
    return if (present) 7 else null;
}

pub fn make_slice() []const u8 {
    return "hello";
}

pub fn fallible(fail: bool) error{Failed}!u32 {
    if (fail) return error.Failed;
    return 11;
}
