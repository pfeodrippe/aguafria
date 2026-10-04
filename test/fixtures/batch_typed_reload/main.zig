const encoder = @import("encoder.zig");

pub fn gained() u8 {
    return encoder.encode(.gained);
}
