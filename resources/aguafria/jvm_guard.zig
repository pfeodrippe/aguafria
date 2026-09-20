const __aguafria_jvm_guard = struct {
    extern fn aguafria_guard_call(*const fn (*anyopaque) callconv(.c) void, *anyopaque) c_int;
    extern fn aguafria_guard_message() ?[*:0]const u8;

    fn call(comptime function: anytype, arguments: anytype) ?@typeInfo(@TypeOf(function)).@"fn".return_type.? {
        const Result = @typeInfo(@TypeOf(function)).@"fn".return_type.?;
        const Context = struct {
            arguments: @TypeOf(arguments),
            result: Result = undefined,

            fn invoke(address: *anyopaque) callconv(.c) void {
                const context: *@This() = @ptrCast(@alignCast(address));
                context.result = @call(.auto, function, context.arguments);
            }
        };
        var context: Context = .{ .arguments = arguments };
        if (aguafria_guard_call(Context.invoke, &context) != 0) return null;
        return context.result;
    }
};
