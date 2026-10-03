const std = @import("std");
const builtin = @import("builtin");

pub const TestOnly = struct {
    comptime {
        std.debug.assert(builtin.is_test);
    }

    pub var count: u32 = 0;
    pub fn answer() u32 {
        return 42;
    }
};
