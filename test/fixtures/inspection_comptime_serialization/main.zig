const std = @import("std");
const probe = @import("operation_probe").Inspector(.{});

test "serialize a series of compiler-known strings" {
    comptime {
        for ("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+/") |character| {
            const bytes = [_]u8{ character, '_', character };
            const encoded = probe.comptimeValue(&bytes);
            if (!std.mem.eql(u8, encoded, "{:comptime \"" ++ bytes ++ "\"}"))
                @compileError("Compiler string serialization changed its value");
        }
    }
}
