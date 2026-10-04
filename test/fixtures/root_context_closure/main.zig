const types = @import("./types.zig");

pub const helpers = @import("./helpers.zig");
pub const setting: u32 = 9;
pub const Box = types.Box;

pub fn box() Box {
    return .{ .value = 9 };
}
