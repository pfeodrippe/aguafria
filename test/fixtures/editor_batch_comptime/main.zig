const defaults = @import("defaults.zig");

pub fn answer() u32 {
    return defaults.increment(41);
}
