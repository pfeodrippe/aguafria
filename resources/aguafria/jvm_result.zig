// Value transport for specialized Clojure/Java calls into native Zig.
// The call runs in the live library; no child process or simulated evaluation.
const __aguafria_jvm = struct {
    const std = @import("std");
    const allocator = std.heap.page_allocator;

    fn isMethod(comptime T: type, comptime name: []const u8) bool {
        const Container = switch (@typeInfo(T)) {
            .pointer => |p| if (p.size == .one) p.child else T,
            else => T,
        };
        return switch (@typeInfo(Container)) {
            .@"struct", .@"union", .@"enum", .@"opaque" =>
                @hasDecl(Container, name) and @typeInfo(@TypeOf(@field(Container, name))) == .@"fn",
            else => false,
        };
    }

    fn FieldValue(comptime T: type, comptime name: []const u8) type {
        if (isMethod(T, name)) return struct { __aguafria_bound_method: bool = true };
        return @TypeOf(@field(@as(T, undefined), name));
    }

    fn lookupField(value: anytype, comptime name: []const u8) FieldValue(@TypeOf(value), name) {
        if (comptime isMethod(@TypeOf(value), name)) {
            return .{};
        } else {
            return @field(value, name);
        }
    }

    fn write(writer: *std.Io.Writer, value: anytype) !void {
        const T = @TypeOf(value);
        switch (@typeInfo(T)) {
            .void, .null => try writer.writeAll("nil"),
            .bool => try writer.writeAll(if (value) "true" else "false"),
            .int, .comptime_int => {
                try writer.print("{d}", .{value});
                if (std.math.cast(i64, value) == null) try writer.writeByte('N');
            },
            .comptime_float => try writer.print("{e}", .{value}),
            .float => {
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
            .error_set => |errors| {
                try writer.writeAll("{:aguafria.jvm/error {:name ");
                try write(writer, @as([]const u8, @errorName(value)));
                try writer.writeAll(" :members ");
                if (errors) |members| {
                    try writer.writeByte('[');
                    inline for (members) |member| {
                        try write(writer, @as([]const u8, member.name));
                        try writer.writeByte(' ');
                    }
                    try writer.writeByte(']');
                } else {
                    try writer.writeAll("nil");
                }
                try writer.writeAll("}}");
            },
            .@"enum" => try write(writer, @tagName(value)),
            .type => {
                try writer.writeAll("{:aguafria.jvm/type ");
                try write(writer, @as([]const u8, @typeName(value)));
                try writer.writeByte('}');
            },
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
                    if (@typeInfo(pointer.child) == .array and @typeInfo(pointer.child).array.child == u8) {
                        try write(writer, @as([]const u8, value));
                    } else {
                        try write(writer, value.*);
                    }
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

    // Inspection follows value fields, never arbitrary pointers. Envelopes keep
    // field names distinct from user maps and preserve anonymous/generic types.
    fn inspect(writer: *std.Io.Writer, value: anytype) !void {
        const T = @TypeOf(value);
        switch (@typeInfo(T)) {
            .@"struct" => |info| {
                try writer.writeAll(if (info.is_tuple) "[" else "{:aguafria.jvm/struct [");
                inline for (info.fields) |field| {
                    if (!info.is_tuple) {
                        try writer.writeByte('[');
                        try write(writer, @as([]const u8, field.name));
                        try writer.writeByte(' ');
                    }
                    try inspect(writer, @field(value, field.name));
                    try writer.writeAll(if (info.is_tuple) " " else "] ");
                }
                try writer.writeAll(if (info.is_tuple) "]" else "]}");
            },
            .pointer => |pointer| {
                if (pointer.size == .slice) {
                    if (pointer.child == u8) {
                        try write(writer, value);
                    } else {
                        try writer.writeByte('[');
                        for (value) |item| {
                            try inspect(writer, item);
                            try writer.writeByte(' ');
                        }
                        try writer.writeByte(']');
                    }
                } else {
                    try writer.writeAll("{:aguafria.jvm/pointer {:address ");
                    try write(writer, @intFromPtr(value));
                    try writer.writeAll(" :type ");
                    try write(writer, @as([]const u8, @typeName(T)));
                    try writer.writeAll("}}");
                }
            },
            .array, .vector => {
                const length = if (@typeInfo(T) == .array) @typeInfo(T).array.len else @typeInfo(T).vector.len;
                try writer.writeByte('[');
                inline for (0..length) |index| {
                    try inspect(writer, value[index]);
                    try writer.writeByte(' ');
                }
                try writer.writeByte(']');
            },
            .optional => if (value) |payload| try inspect(writer, payload) else try writer.writeAll("nil"),
            .error_union => {
                if (value) |payload| {
                    try writer.writeAll("{:ok ");
                    try inspect(writer, payload);
                    try writer.writeByte('}');
                } else |err| {
                    try writer.writeAll("{:error {:name ");
                    try write(writer, @errorName(err));
                    try writer.writeAll("}}");
                }
            },
            .@"enum" => {
                try writer.writeAll("{:aguafria.jvm/enum ");
                try write(writer, @tagName(value));
                try writer.writeByte('}');
            },
            .@"union" => |info| {
                if (info.tag_type != null) {
                    switch (value) {
                        inline else => |payload, tag| {
                            try writer.writeAll("{:aguafria.jvm/struct [[");
                            try write(writer, @tagName(tag));
                            try writer.writeByte(' ');
                            try inspect(writer, payload);
                            try writer.writeAll("]]}");
                        },
                    }
                } else {
                    // Untagged unions have no safely discoverable active field.
                    try writer.writeAll("{:type ");
                    try write(writer, @as([]const u8, @typeName(T)));
                    try writer.writeAll(" :active-field :unknown}");
                }
            },
            else => try write(writer, value),
        }
    }

    fn inspectResult(value: anytype) usize {
        var writer: std.Io.Writer.Allocating = .init(allocator);
        defer writer.deinit();
        inspect(&writer.writer, value) catch @panic("Cannot inspect JVM value");
        const encoded = allocator.dupeZ(u8, writer.written()) catch @panic("Cannot allocate JVM inspection");
        return @intFromPtr(encoded.ptr);
    }

    fn result(value: anytype) usize {
        var writer: std.Io.Writer.Allocating = .init(allocator);
        defer writer.deinit();
        write(&writer.writer, value) catch @panic("Cannot encode JVM result");
        const encoded = allocator.dupeZ(u8, writer.written()) catch @panic("Cannot allocate JVM result");
        return @intFromPtr(encoded.ptr);
    }

    fn fieldResult(value: anytype) usize {
        // A field may itself be an unbounded pointer. Return a typed borrowed
        // address, as inspection does; never read an unknown number of elements.
        if (@typeInfo(@TypeOf(value)) == .pointer) {
            const size = @typeInfo(@TypeOf(value)).pointer.size;
            if (size == .many or size == .c) {
                return inspectResult(value);
            }
        }
        return result(value);
    }

    fn release(address: usize) void {
        const text = std.mem.span(@as([*:0]const u8, @ptrFromInt(address)));
        allocator.free(text[0 .. text.len + 1]);
    }
};
