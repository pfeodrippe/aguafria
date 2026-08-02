pub const hex_integer = 0xFF_FF;
pub const hex_float = 0x1.8p+1;
pub const zig_escape = "\x41";
pub const zig_character = '\x41';
pub const expected_error = error.Expected;
pub const message =
    \\hello
    \\world
;

pub fn value() u32 {
    return hex_integer;
}
