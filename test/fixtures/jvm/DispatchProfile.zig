// Cross-image Zig .auto dispatch must retain the same error-tracing ABI even
// when the safety/optimization profile differs. Keep the payload native.
const Shape = extern struct { index1: i32, world0: u16, generation: u16 };

export fn optimization_mode() callconv(.c) u32 {
    return switch (@import("builtin").optimize) {
        .fast => 1,
        .safe => 2,
        .debug => 3,
        else => 0,
    };
}

pub fn scalar(value: u32) u32 {
    return value + 7;
}

pub fn shape(value: Shape, context: ?*anyopaque) bool {
    @setRuntimeSafety(false);
    return value.index1 == 1234 and value.world0 == 2 and
        value.generation == 3 and context != null;
}

export fn scalar_address() callconv(.c) usize {
    return @intFromPtr(&scalar);
}

export fn shape_address() callconv(.c) usize {
    return @intFromPtr(&shape);
}

export fn scalar_probe(address: usize) callconv(.c) u32 {
    const target: @TypeOf(&scalar) = @ptrFromInt(address);
    return target(35);
}

export fn shape_probe(address: usize) callconv(.c) u32 {
    var sentinel: u8 = 1;
    const target: @TypeOf(&shape) = @ptrFromInt(address);
    return if (target(.{ .index1 = 1234, .world0 = 2, .generation = 3 }, &sentinel)) 99 else 0;
}
