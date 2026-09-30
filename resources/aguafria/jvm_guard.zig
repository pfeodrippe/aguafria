const __aguafria_jvm_guard = struct {
    extern fn aguafria_guard_call(*const fn (*anyopaque) callconv(.c) void, *anyopaque) c_int;
    extern fn aguafria_guard_message() ?[*:0]const u8;

    fn resultType(comptime function: anytype) type {
        const Result = @typeInfo(@TypeOf(function)).@"fn".return_type.?;
        // A guarded panic returns to the JVM even when the callee cannot return.
        // No value of noreturn exists to store in the callback context.
        return if (Result == noreturn) void else Result;
    }

    fn call(comptime function: anytype, arguments: anytype) ?resultType(function) {
        const Result = resultType(function);
        const Context = struct {
            arguments: @TypeOf(arguments),
            result: Result = undefined,

            fn invoke(address: *anyopaque) callconv(.c) void {
                const context: *@This() = @ptrCast(@alignCast(address));
                if (@typeInfo(@TypeOf(function)).@"fn".return_type.? == noreturn) {
                    @call(.auto, function, context.arguments);
                } else {
                    context.result = @call(.auto, function, context.arguments);
                }
            }
        };
        var context: Context = .{ .arguments = arguments };
        if (aguafria_guard_call(Context.invoke, &context) != 0) return null;
        return context.result;
    }
};
