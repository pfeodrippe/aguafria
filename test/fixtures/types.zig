pub const Failure = error{ Missing, Invalid };
pub const Callback = *const fn (context: *anyopaque, value: u32) callconv(.c) void;
pub const BareCallback = fn (*anyopaque) void;
pub const SentinelBuffer = [4:0]u8;

pub fn xorValues(a: u32, b: u32) u32 {
    return a ^ b;
}

pub fn wrappingNegation(value: i32) i32 {
    return -%value;
}

pub fn terminated(values: [*:0]const u8) [:0]const u8 {
    return values[0..4 :0];
}

pub fn read(value: *align(8) const volatile allowzero u32) Failure!u32 {
    if (value.* == 0) return error.Missing;
    return value.*;
}

pub fn alignedLocal() u8 {
    var value: u8 align(8) = 1;
    value += 1;
    return value;
}
