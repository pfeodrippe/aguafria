const a = @import("value.zig");
const k = @import("value.zig");

pub fn answer() u32 {
    return a.answer() + k.answer();
}
