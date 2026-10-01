const __aguafria_jvm_guard = struct {
    extern fn aguafria_guard_call(*const fn (*anyopaque) callconv(.c) void, *anyopaque) c_int;
    extern fn aguafria_guard_message() ?[*:0]const u8;

    fn resultType(comptime __aguafria_function: anytype) type {
        const __aguafria_Result = @typeInfo(@TypeOf(__aguafria_function)).@"fn".return_type.?;
        // A guarded panic returns to the JVM even when the callee cannot return.
        // No value of noreturn exists to store in the callback context.
        return if (__aguafria_Result == noreturn) void else __aguafria_Result;
    }

    fn call(comptime __aguafria_function: anytype, __aguafria_arguments: anytype) ?resultType(__aguafria_function) {
        const __aguafria_Result = resultType(__aguafria_function);
        const __aguafria_Context = struct {
            arguments: @TypeOf(__aguafria_arguments),
            result: __aguafria_Result = undefined,

            fn invoke(__aguafria_address: *anyopaque) callconv(.c) void {
                const __aguafria_context: *@This() = @ptrCast(@alignCast(__aguafria_address));
                if (@typeInfo(@TypeOf(__aguafria_function)).@"fn".return_type.? == noreturn) {
                    @call(.auto, __aguafria_function, __aguafria_context.arguments);
                } else {
                    __aguafria_context.result = @call(.auto, __aguafria_function, __aguafria_context.arguments);
                }
            }
        };
        var __aguafria_context: __aguafria_Context = .{ .arguments = __aguafria_arguments };
        if (aguafria_guard_call(__aguafria_Context.invoke, &__aguafria_context) != 0) return null;
        return __aguafria_context.result;
    }
};
