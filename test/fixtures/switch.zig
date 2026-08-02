pub fn classify(value: u8) u8 {
    return switch (value) {
        0 => 10,
        1...3 => |matched| matched,
        inline 4, 5 => |matched| matched + 1,
        else => 0,
    };
}

pub fn stop(value: u8) void {
    switch (value) {
        0 => return,
        else => {},
    }
}
