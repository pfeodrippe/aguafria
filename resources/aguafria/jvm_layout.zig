// Compiler-authored layouts for JVM construction of computed native types.
pub const __aguafria_jvm = struct {
    const std = @import("std");
    extern fn aguafria_jvm_writer_new() ?*anyopaque;
    extern fn aguafria_jvm_writer_append(*anyopaque, [*]const u8, usize) bool;
    extern fn aguafria_jvm_writer_destroy(*anyopaque) void;
    extern fn aguafria_jvm_writer_finish(*anyopaque) usize;
    extern fn aguafria_jvm_release(usize) void;
    extern fn aguafria_jvm_release_native(usize, usize, usize) void;

    const Sink = struct {
        handle: *anyopaque,
        writer: std.Io.Writer = .{ .vtable = &.{ .drain = drain }, .buffer = &.{} },

        fn drain(writer: *std.Io.Writer, data: []const []const u8, splat: usize) std.Io.Writer.Error!usize {
            const self: *Sink = @fieldParentPtr("writer", writer);
            var written: usize = 0;
            for (data[0 .. data.len - 1]) |bytes| {
                if (!aguafria_jvm_writer_append(self.handle, bytes.ptr, bytes.len)) return error.WriteFailed;
                written += bytes.len;
            }
            const last = data[data.len - 1];
            for (0..splat) |_| {
                if (!aguafria_jvm_writer_append(self.handle, last.ptr, last.len)) return error.WriteFailed;
                written += last.len;
            }
            return written;
        }
    };

    fn supported(comptime T: type) bool {
        return switch (@typeInfo(T)) {
            .int, .bool, .void => true,
            .float => |info| info.bits == 32 or info.bits == 64,
            .array => |info| supported(info.child) and info.sentinel_ptr == null,
            .pointer => |info| info.size != .slice or
                (info.sentinel_ptr == null and supported(info.child)),
            .@"struct" => |info| fields: {
                if (info.layout == .@"packed") break :fields false;
                for (info.field_types, info.field_attrs) |Field, attrs| {
                    if (attrs.@"comptime" or !supported(Field) or attrs.defaultValue(Field) != null)
                        break :fields false;
                }
                break :fields true;
            },
            else => false,
        };
    }

    fn writeType(writer: *std.Io.Writer, comptime T: type) !void {
        switch (@typeInfo(T)) {
            .int, .float, .bool, .void => try writer.print(":{s}", .{@typeName(T)}),
            else => try writer.writeAll("nil"),
        }
    }

    fn writeLayout(writer: *std.Io.Writer, comptime T: type) !void {
        try writer.print("{{:size {d} :alignment {d} :type ", .{ @sizeOf(T), @alignOf(T) });
        try writeType(writer, T);
        switch (@typeInfo(T)) {
            .int, .float, .bool, .void => try writer.writeAll(" :kind :scalar"),
            .array => |info| {
                try writer.print(" :kind :array :length {d} :element-size {d} :element-type ", .{ info.len, @sizeOf(info.child) });
                try writeType(writer, info.child);
                try writer.writeAll(" :element-schema ");
                try writeLayout(writer, info.child);
            },
            .pointer => |info| {
                if (info.size == .slice) {
                    // These are addresses of fields, not reads of undefined data.
                    var slice: T = undefined;
                    const base = @intFromPtr(&slice);
                    try writer.print(" :kind :slice :pointer-offset {d} :length-offset {d} :element-size {d} :element-alignment {d} :element-type ", .{ @intFromPtr(&slice.ptr) - base, @intFromPtr(&slice.len) - base, @sizeOf(info.child), @alignOf(info.child) });
                    try writeType(writer, info.child);
                    try writer.writeAll(" :element-schema ");
                    try writeLayout(writer, info.child);
                } else {
                    try writer.print(" :kind :pointer :nullable? {}", .{info.size == .c or info.attrs.@"allowzero"});
                }
            },
            .@"struct" => |info| {
                try writer.print(" :kind :struct :tuple? {} :fields [", .{info.is_tuple});
                inline for (info.field_names, info.field_types) |name, Field| {
                    try writer.writeAll("{:name ");
                    try std.json.Stringify.value(name, .{}, writer);
                    try writer.print(" :byte-offset {d} :byte-size {d} :type ", .{ @offsetOf(T, name), @sizeOf(Field) });
                    try writeType(writer, Field);
                    try writer.writeAll(" :schema ");
                    try writeLayout(writer, Field);
                    try writer.writeAll("} ");
                }
                try writer.writeByte(']');
            },
            else => unreachable,
        }
        try writer.writeByte('}');
    }

    pub fn layoutResult(comptime T: type) usize {
        var sink: Sink = .{ .handle = aguafria_jvm_writer_new() orelse @panic("Cannot allocate layout writer") };
        defer aguafria_jvm_writer_destroy(sink.handle);
        if (comptime supported(T)) {
            writeLayout(&sink.writer, T) catch @panic("Cannot encode native layout");
        } else {
            sink.writer.writeAll("nil") catch @panic("Cannot encode native layout");
        }
        const address = aguafria_jvm_writer_finish(sink.handle);
        if (address == 0) @panic("Cannot allocate native layout");
        return address;
    }

    pub fn release(address: usize) void {
        aguafria_jvm_release(address);
    }

    pub fn releaseNative(address: usize, size: usize, alignment: usize) void {
        aguafria_jvm_release_native(address, size, alignment);
    }
};
