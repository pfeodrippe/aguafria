const math_module = @import("math");

pub fn six_times(value: u32) u32 {
    return math_module.double(value) * 3;
}
