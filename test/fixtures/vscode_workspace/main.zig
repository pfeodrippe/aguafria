const std = @import("std");

pub fn answer() u32 {
    return 42;
}

pub fn caller() u32 {
    return answer();
}

pub fn main(init: std.process.Init) !void {
    while (caller() == 42) {
        try init.io.sleep(.fromMilliseconds(1), .awake);
    }
}
