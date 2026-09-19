// Value transport for specialized Clojure/Java calls into native Zig.
// The call runs in the live library; no child process or simulated evaluation.
const __aguafria_jvm = struct {
    const std = @import("std");
    const allocator = std.heap.page_allocator;

    fn write(writer: *std.Io.Writer, value: anytype) !void {
        const T = @TypeOf(value);
        switch (@typeInfo(T)) {
            .void, .null => try writer.writeAll("nil"),
            .bool => try writer.writeAll(if (value) "true" else "false"),
            .int, .comptime_int => {
                try writer.print("{d}", .{value});
                if (std.math.cast(i64, value) == null) try writer.writeByte('N');
            },
            .float, .comptime_float => {
                if (std.math.isNan(value)) {
                    try writer.writeAll("##NaN");
                } else if (std.math.isInf(value)) {
                    try writer.writeAll(if (value < 0) "##-Inf" else "##Inf");
                } else {
                    try writer.print("{e}", .{value});
                }
            },
            .optional => if (value) |payload| try write(writer, payload) else try writer.writeAll("nil"),
            .error_union => {
                if (value) |payload| {
                    try writer.writeAll("{:ok ");
                    try write(writer, payload);
                    try writer.writeByte('}');
                } else |err| {
                    try writer.writeAll("{:error {:name ");
                    try write(writer, @errorName(err));
                    try writer.writeAll("}}");
                }
            },
            .error_set => try write(writer, @errorName(value)),
            .@"enum" => try write(writer, @tagName(value)),
            .type => try write(writer, @typeName(value)),
            .pointer => |pointer| {
                if (pointer.size == .slice) {
                    if (pointer.child == u8) {
                        // JSON and EDN share quoted-string escape syntax.
                        try std.json.Stringify.value(value, .{}, writer);
                    } else {
                        try writer.writeByte('[');
                        for (value) |item| {
                            try write(writer, item);
                            try writer.writeByte(' ');
                        }
                        try writer.writeByte(']');
                    }
                } else if (pointer.size == .one) {
                    try write(writer, value.*);
                } else {
                    @compileError("A JVM result with an unbounded pointer needs an explicit slice or native-value wrapper");
                }
            },
            .array, .vector => {
                try writer.writeByte('[');
                const length = if (@typeInfo(T) == .array) @typeInfo(T).array.len else @typeInfo(T).vector.len;
                inline for (0..length) |index| {
                    try write(writer, value[index]);
                    try writer.writeByte(' ');
                }
                try writer.writeByte(']');
            },
            .@"struct" => |info| {
                try writer.writeByte(if (info.is_tuple) '[' else '{');
                inline for (info.fields) |field| {
                    if (!info.is_tuple) {
                        try write(writer, @as([]const u8, field.name));
                        try writer.writeByte(' ');
                    }
                    try write(writer, @field(value, field.name));
                    try writer.writeByte(' ');
                }
                try writer.writeByte(if (info.is_tuple) ']' else '}');
            },
            else => @compileError("This Zig result requires an explicit native-value wrapper for JVM callers"),
        }
    }

    fn result(value: anytype) usize {
        var writer: std.Io.Writer.Allocating = .init(allocator);
        defer writer.deinit();
        write(&writer.writer, value) catch @panic("Cannot encode JVM result");
        const encoded = allocator.dupeZ(u8, writer.written()) catch @panic("Cannot allocate JVM result");
        return @intFromPtr(encoded.ptr);
    }

    fn release(address: usize) void {
        const text = std.mem.span(@as([*:0]const u8, @ptrFromInt(address)));
        allocator.free(text[0 .. text.len + 1]);
    }
};
