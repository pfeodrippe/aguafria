extern var environ: [*:null]?[*:0]u8;

pub fn exactIdentifier(@"type": u32) u32 {
    return @"type";
}

pub fn nestedExternValue() usize {
    const Api = extern struct {
        inner: extern struct {
            value: usize,
        },
    };
    return @sizeOf(Api) + @intFromPtr(environ);
}

pub fn nosuspendBlock(value: *u32) void {
    nosuspend {
        value.* += 1;
    }
}

pub fn compilerBarrier() void {
    asm volatile ("" ::: .{ .memory = true });
}
