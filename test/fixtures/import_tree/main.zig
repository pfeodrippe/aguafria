const math = @import("math.zig");

pub fn quadruple(value: u32) u32 {
    return math.double(math.double(value));
}
