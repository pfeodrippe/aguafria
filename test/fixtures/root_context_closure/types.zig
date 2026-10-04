const root = @import("./main.zig");

pub const Box = extern struct {
    value: u32,

    comptime {
        if (root.helpers.expected != 9) @compileError("wrong setting");
    }
};
