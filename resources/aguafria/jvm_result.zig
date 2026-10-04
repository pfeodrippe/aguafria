// Value transport for specialized Clojure/Java calls into native Zig.
// The call runs in the live library; no child process or simulated evaluation.
pub const __aguafria_jvm = struct {
    const std = @import("std");
    extern fn aguafria_jvm_allocate(usize, usize) ?[*]u8;
    extern fn aguafria_jvm_writer_new() ?*anyopaque;
    extern fn aguafria_jvm_writer_append(*anyopaque, [*]const u8, usize) bool;
    extern fn aguafria_jvm_writer_destroy(*anyopaque) void;
    extern fn aguafria_jvm_writer_finish(*anyopaque) usize;
    extern fn aguafria_jvm_release(usize) void;
    extern fn aguafria_jvm_release_native(usize, usize, usize) void;

    // The shared library owns the byte buffer, not this image's Writer vtable.
    // Its C ABI returns status flags; construct Zig errors in the caller image.
    const NativeWriter = struct {
        handle: *anyopaque,
        writer: std.Io.Writer = .{ .vtable = &.{ .drain = drain }, .buffer = &.{} },

        fn init() NativeWriter {
            return .{ .handle = aguafria_jvm_writer_new() orelse @panic("Cannot allocate JVM writer") };
        }

        fn deinit(self: *NativeWriter) void {
            aguafria_jvm_writer_destroy(self.handle);
        }

        fn finish(self: *NativeWriter) usize {
            const address = aguafria_jvm_writer_finish(self.handle);
            if (address == 0) @panic("Cannot allocate JVM result");
            return address;
        }

        fn drain(writer: *std.Io.Writer, data: []const []const u8, splat: usize) std.Io.Writer.Error!usize {
            const self: *NativeWriter = @fieldParentPtr("writer", writer);
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

    pub fn parseComptimeFloat(text: []const u8) f128 {
        return std.fmt.parseFloat(f128, text) catch @panic("Invalid JVM float literal");
    }

    fn Borrowed(comptime Pointer: type) type {
        return struct {
            pub const aguafria_borrowed_view = true;
            pointer: Pointer,
        };
    }

    // Only emit schemas that reproduce the exact compiler type. Nominal types
    // and pointer qualifiers not represented here retain their Zig expression.
    fn hasStructuralType(comptime T: type) bool {
        return switch (@typeInfo(T)) {
            .int, .float, .bool, .void, .noreturn, .comptime_int, .comptime_float, .type => true,
            .array => |a| hasStructuralType(a.child) and
                (a.sentinel_ptr == null or sentinelRepresentable(a.child)),
            .vector => |v| hasStructuralType(v.child),
            .optional => |o| hasStructuralType(o.child),
            .error_union => |e| hasStructuralType(e.error_set) and hasStructuralType(e.payload),
            .error_set => |errors| errors: {
                if (errors.error_names) |members| {
                    for (members) |member| {
                        if (member.len == 0 or std.ascii.isDigit(member[0])) break :errors false;
                        for (member) |c| {
                            if (!std.ascii.isAlphanumeric(c) and c != '_') break :errors false;
                        }
                    }
                }
                break :errors true;
            },
            .@"struct" => |s| tuple: {
                if (!s.is_tuple) break :tuple false;
                var types: [s.field_types.len]type = undefined;
                for (s.field_types, s.field_attrs, 0..) |field_type, field_attrs, i| {
                    if (field_attrs.@"comptime" or !hasStructuralType(field_type)) break :tuple false;
                    types[i] = field_type;
                }
                break :tuple T == @Tuple(&types);
            },
            .pointer => |p| hasStructuralType(p.child) and
                (p.sentinel_ptr == null or sentinelRepresentable(p.child)),
            else => false,
        };
    }

    fn sentinelRepresentable(comptime T: type) bool {
        return switch (@typeInfo(T)) {
            .int, .bool => true,
            else => false,
        };
    }

    fn writeSentinel(writer: *std.Io.Writer, comptime sentinel: anytype) !void {
        if (@TypeOf(sentinel) == bool) {
            try writer.writeAll(if (sentinel) "true" else "false");
        } else {
            try writer.print("{d}", .{sentinel});
        }
    }

    fn writeStructuralType(writer: *std.Io.Writer, comptime T: type) !void {
        switch (@typeInfo(T)) {
            .int, .float, .bool, .void, .noreturn, .comptime_int, .comptime_float, .type => try writer.print(":{s}", .{@typeName(T)}),
            inline .array, .vector => |a| {
                try writer.print("[:{s} {d} ", .{ @tagName(@typeInfo(T)), a.len });
                if (@typeInfo(T) == .array) {
                    if (a.sentinel()) |sentinel| {
                        try writer.writeAll("{:sentinel ");
                        try writeSentinel(writer, sentinel);
                        try writer.writeAll("} ");
                    }
                }
                try writeStructuralType(writer, a.child);
                try writer.writeAll("]");
            },
            .optional => |o| {
                try writer.writeAll("[:optional ");
                try writeStructuralType(writer, o.child);
                try writer.writeAll("]");
            },
            .error_union => |e| {
                try writer.writeAll("[:error-union ");
                try writeStructuralType(writer, e.error_set);
                try writer.writeByte(' ');
                try writeStructuralType(writer, e.payload);
                try writer.writeByte(']');
            },
            .error_set => |errors| {
                if (errors.error_names) |members| {
                    try writer.writeAll("[:error-set [");
                    for (members) |member| try writer.print(":{s} ", .{member});
                    try writer.writeAll("]]");
                } else {
                    try writer.writeAll(":anyerror");
                }
            },
            .@"struct" => |s| {
                try writer.writeAll("(aguafria.keyword/Tuple (aguafria.keyword/& [");
                inline for (s.field_types) |field_type| {
                    const schema_vector = switch (@typeInfo(field_type)) {
                        .array, .vector, .optional, .pointer, .error_union, .error_set => true,
                        else => false,
                    };
                    if (schema_vector) try writer.writeAll("(aguafria.zig/type ");
                    try writeStructuralType(writer, field_type);
                    if (schema_vector) try writer.writeByte(')');
                    try writer.writeByte(' ');
                }
                try writer.writeAll("]))");
            },
            .pointer => |p| {
                if (p.size == .many and p.sentinel_ptr != null and
                    !p.attrs.@"volatile" and !p.attrs.@"allowzero" and (p.attrs.@"addrspace" == null or p.attrs.@"addrspace" == .generic) and
                    p.attrs.@"align" == null)
                {
                    try writer.print("[:{s} ", .{if (p.attrs.@"const") "sentinel-const" else "sentinel"});
                    try writeStructuralType(writer, p.child);
                    try writer.writeAll(" ");
                    try writeSentinel(writer, p.sentinel().?);
                    try writer.writeAll("]");
                    return;
                }
                const qualified = p.sentinel_ptr != null or p.attrs.@"volatile" or (p.attrs.@"allowzero" and p.size != .c) or
                    (p.attrs.@"addrspace" != null and p.attrs.@"addrspace" != .generic) or
                    p.attrs.@"align" != null or
                    (p.size == .c and p.attrs.@"const");
                if (qualified) {
                    try writer.writeAll("[:* {");
                    if (p.size != .one) try writer.print(":size :{s}", .{@tagName(p.size)});
                    if (p.attrs.@"const") try writer.writeAll(" :const? true");
                    if (p.attrs.@"volatile") try writer.writeAll(" :volatile? true");
                    if (p.attrs.@"allowzero" and p.size != .c) try writer.writeAll(" :allowzero? true");
                    if (p.attrs.@"align") |alignment| {
                        try writer.print(" :align {d}", .{alignment});
                    }
                    if ((p.attrs.@"addrspace" != null and p.attrs.@"addrspace" != .generic))
                        try writer.print(" :addrspace :.{s}", .{@tagName(p.attrs.@"addrspace".?)});
                    if (p.sentinel()) |sentinel| {
                        try writer.writeAll(" :sentinel ");
                        try writeSentinel(writer, sentinel);
                    }
                    try writer.writeAll("} ");
                    try writeStructuralType(writer, p.child);
                    try writer.writeAll("]");
                    return;
                }
                const tag = switch (p.size) {
                    .one => if (p.attrs.@"const") "*const" else "*",
                    .many => if (p.attrs.@"const") "many-const" else "many",
                    .slice => if (p.attrs.@"const") "slice-const" else "slice",
                    .c => "c-pointer",
                };
                try writer.print("[:{s} ", .{tag});
                try writeStructuralType(writer, p.child);
                try writer.writeAll("]");
            },
            else => unreachable,
        }
    }

    pub fn runtimeArraySlice(pointer: anytype, length: usize, start: usize, end: usize) @TypeOf(pointer[0..length][start..end]) {
        return pointer[0..length][start..end];
    }

    pub fn arrayIndexView(pointer: anytype, length: usize, index: usize) if (fieldNeedsStorage(@TypeOf(pointer[index]))) Borrowed(@TypeOf(&pointer[index])) else @TypeOf(pointer[index]) {
        if (comptime fieldNeedsStorage(@TypeOf(pointer[index]))) {
            return .{ .pointer = &pointer[0..length][index] };
        } else {
            return pointer[0..length][index];
        }
    }

    pub fn indexRequiresComptime(comptime T: type) bool {
        return @typeInfo(T) == .@"struct" and @typeInfo(T).@"struct".is_tuple;
    }

    pub fn tupleIndexView(pointer: anytype, comptime index: usize) @TypeOf(fieldView(pointer, std.fmt.comptimePrint("{d}", .{index}))) {
        return fieldView(pointer, std.fmt.comptimePrint("{d}", .{index}));
    }

    pub fn dereferenceView(pointer: anytype) Borrowed(@TypeOf(pointer)) {
        std.debug.assert(@intFromPtr(pointer) != 0);
        return .{ .pointer = pointer };
    }

    pub fn indexView(pointer: anytype, index: usize) if (@typeInfo(@typeInfo(@TypeOf(pointer)).pointer.child) == .vector)
        @typeInfo(@typeInfo(@TypeOf(pointer)).pointer.child).vector.child
    else if (!fieldNeedsStorage(@TypeOf(pointer.*[index])))
        @TypeOf(pointer.*[index])
    else
        Borrowed(@TypeOf(&pointer.*[index])) {
        if (comptime @typeInfo(@typeInfo(@TypeOf(pointer)).pointer.child) == .vector) {
            const info = @typeInfo(@typeInfo(@TypeOf(pointer)).pointer.child).vector;
            const lanes: [info.len]info.child = pointer.*;
            return lanes[index];
        } else if (comptime !fieldNeedsStorage(@TypeOf(pointer.*[index]))) {
            return pointer.*[index];
        } else {
            return .{ .pointer = &pointer.*[index] };
        }
    }

    pub fn fieldView(pointer: anytype, comptime name: []const u8) if (hasAddressableField(@typeInfo(@TypeOf(pointer)).pointer.child, name))
        Borrowed(@TypeOf(&@field(pointer.*, name)))
    else
        FieldValue(@typeInfo(@TypeOf(pointer)).pointer.child, name) {
        if (comptime hasAddressableField(@typeInfo(@TypeOf(pointer)).pointer.child, name)) {
            return .{ .pointer = &@field(pointer.*, name) };
        } else {
            return lookupField(pointer.*, name);
        }
    }

    pub fn borrowedResult(value: anytype) usize {
        return borrowedResultWithType(value, null);
    }

    pub fn borrowedFieldResult(value: anytype, comptime Receiver: type, comptime identity: []const u8, comptime member: []const u8) usize {
        const container = switch (@typeInfo(Receiver)) {
            .pointer => |p| if (p.size == .one)
                "(aguafria.zig/field (aguafria.zig/field (aguafria.keyword/typeInfo " ++ identity ++ ") :pointer) :child)"
            else
                identity,
            else => identity,
        };
        return borrowedResultWithType(value, "(aguafria.keyword/FieldType " ++ container ++ " " ++ member ++ ")");
    }

    fn borrowedResultWithType(value: anytype, comptime nominal_type: ?[]const u8) usize {
        const T = @TypeOf(value);
        if (comptime @typeInfo(T) != .@"struct" or !@hasDecl(T, "aguafria_borrowed_view")) return result(value);
        const P = @typeInfo(@TypeOf(value.pointer)).pointer;
        var sink = NativeWriter.init();
        defer sink.deinit();
        const writer = &sink.writer;
        writer.print("{{:aguafria.jvm/borrowed {{:address {d} :size {d} :alignment {d} :mutable? {s} :native-kind :{s}", .{ @intFromPtr(value.pointer), @sizeOf(P.child), @alignOf(P.child), if (P.attrs.@"const") "false" else "true", @tagName(@typeInfo(P.child)) }) catch @panic("Cannot encode native view");
        writeTupleLength(writer, P.child) catch @panic("Cannot encode native tuple length");
        if (@typeInfo(P.child) == .int or @typeInfo(P.child) == .float) {
            writer.print(" :scalar-type :{s}", .{@typeName(P.child)}) catch @panic("Cannot encode native view type");
        }
        if (comptime hasStructuralType(P.child)) {
            writer.writeAll(" :native-type ") catch @panic("Cannot encode native view type");
            writeStructuralType(writer, P.child) catch @panic("Cannot encode native view type");
        } else if (nominal_type) |identity| {
            writer.writeAll(" :native-type ") catch @panic("Cannot encode native view type");
            writer.writeAll(identity) catch @panic("Cannot encode native view type");
        }
        writer.writeAll("}}") catch @panic("Cannot encode native view");
        return sink.finish();
    }

    fn isMethod(comptime T: type, comptime name: []const u8) bool {
        const Container = switch (@typeInfo(T)) {
            .pointer => |p| if (p.size == .one) p.child else T,
            else => T,
        };
        return switch (@typeInfo(Container)) {
            .@"struct", .@"union", .@"enum", .@"opaque" => @hasDecl(Container, name) and @typeInfo(@TypeOf(@field(Container, name))) == .@"fn",
            else => false,
        };
    }

    const BoundMethod = struct { __aguafria_bound_method: bool = true };

    pub fn boundMethod() BoundMethod {
        return .{};
    }

    fn FieldValue(comptime T: type, comptime name: []const u8) type {
        if (isMethod(T, name)) return BoundMethod;
        const Container = switch (@typeInfo(T)) {
            .pointer => |p| if (p.size == .one) p.child else T,
            else => T,
        };
        // Field access implicitly dereferences a single-item pointer. Inspect its
        // child type, rather than dereferencing an undefined pointer at comptime.
        return @TypeOf(@field(@as(Container, undefined), name));
    }

    pub fn lookupField(value: anytype, comptime name: []const u8) FieldValue(@TypeOf(value), name) {
        if (comptime isMethod(@TypeOf(value), name)) {
            return .{};
        } else {
            return @field(value, name);
        }
    }

    pub fn unsignedIntegerBits(comptime T: type) usize {
        return switch (@typeInfo(T)) {
            .int => |info| if (info.signedness == .unsigned) info.bits else 0,
            else => 0,
        };
    }

    fn fieldNeedsStorage(comptime T: type) bool {
        // Wrapping false or null in an object changes Clojure truthiness.
        return T != bool and @typeInfo(T) != .optional;
    }

    pub fn hasAddressableField(comptime T: type, comptime name: []const u8) bool {
        const Container = switch (@typeInfo(T)) {
            .pointer => |p| if (p.size == .one) p.child else T,
            else => T,
        };
        return switch (@typeInfo(Container)) {
            .@"struct" => |info| blk: {
                if (info.layout == .@"packed") break :blk false;
                inline for (info.field_names, info.field_types, info.field_attrs) |field_name, field_type, field_attrs| {
                    if (std.mem.eql(u8, field_name, name)) {
                        break :blk !field_attrs.@"comptime" and fieldNeedsStorage(field_type);
                    }
                }
                break :blk false;
            },
            .@"union" => |info| blk: {
                if (info.layout == .@"packed") break :blk false;
                inline for (info.field_names, info.field_types) |field_name, field_type| {
                    if (std.mem.eql(u8, field_name, name)) break :blk fieldNeedsStorage(field_type);
                }
                break :blk false;
            },
            .pointer => |p| p.size == .slice and
                (std.mem.eql(u8, name, "ptr") or std.mem.eql(u8, name, "len")),
            else => false,
        };
    }

    fn needsNativeStorage(comptime T: type) bool {
        return switch (@typeInfo(T)) {
            .pointer => |p| p.size != .slice and
                !(p.size == .one and @typeInfo(p.child) == .array and @typeInfo(p.child).array.child == u8),
            .@"struct" => |info| blk: {
                inline for (info.field_types) |field_type| {
                    if (containsNativeStorage(field_type)) break :blk true;
                }
                break :blk false;
            },
            .@"union" => true,
            else => false,
        };
    }

    fn containsNativeStorage(comptime T: type) bool {
        return switch (@typeInfo(T)) {
            .optional => |info| containsNativeStorage(info.child),
            .error_union => |info| containsNativeStorage(info.payload),
            inline .array, .vector => |info| containsNativeStorage(info.child),
            .pointer => |info| if (info.size == .slice) containsNativeStorage(info.child) else needsNativeStorage(T),
            else => needsNativeStorage(T),
        };
    }

    fn write(writer: *std.Io.Writer, value: anytype) !void {
        return writeAt(writer, value, &.{});
    }

    fn writeAt(writer: *std.Io.Writer, value: anytype, comptime path: []const []const u8) !void {
        const T = @TypeOf(value);
        if (comptime needsNativeStorage(T)) {
            const size = @sizeOf(T);
            const bytes = aguafria_jvm_allocate(size, @alignOf(T)) orelse return error.OutOfMemory;
            errdefer aguafria_jvm_release_native(@intFromPtr(bytes), size, @alignOf(T));
            @memcpy(bytes[0..size], std.mem.asBytes(&value));
            try writer.writeAll("{:aguafria.jvm/native {:address ");
            try writer.print("{d} :size {d} :alignment {d} :path ", .{ @intFromPtr(bytes), size, @alignOf(T) });
            try write(writer, path);
            try writer.writeAll("}}");
            return;
        }
        switch (@typeInfo(T)) {
            .void, .null => try writer.writeAll("nil"),
            .bool => try writer.writeAll(if (value) "true" else "false"),
            .int, .comptime_int => {
                try writer.print("{d}", .{value});
                if (std.math.cast(i64, value) == null) try writer.writeByte('N');
            },
            .comptime_float, .float => {
                if (std.math.isNan(value)) {
                    try writer.writeAll("##NaN");
                } else if (if (T == comptime_float)
                    @abs(value) == @as(comptime_float, std.math.inf(f128))
                else
                    std.math.isInf(value))
                {
                    try writer.writeAll(if (value < 0) "##-Inf" else "##Inf");
                } else {
                    try writer.print("{e}", .{value});
                }
            },
            .optional => if (value) |payload| try writeAt(writer, payload, path ++ .{ "optional", "child" }) else try writer.writeAll("nil"),
            .error_union => {
                if (value) |payload| {
                    try writer.writeAll("{:ok ");
                    try writeAt(writer, payload, path ++ .{ "error_union", "payload" });
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
                if (errors.error_names) |members| {
                    try writer.writeByte('[');
                    inline for (members) |member| {
                        try write(writer, @as([]const u8, member));
                        try writer.writeByte(' ');
                    }
                    try writer.writeByte(']');
                } else {
                    try writer.writeAll("nil");
                }
                try writer.writeAll("}}");
            },
            .@"enum", .enum_literal => try write(writer, @tagName(value)),
            .type => {
                try writer.writeAll("{:aguafria.jvm/type {:name ");
                try write(writer, @as([]const u8, @typeName(value)));
                if (comptime hasStructuralType(value)) {
                    try writer.writeAll(" :schema ");
                    try writeStructuralType(writer, value);
                }
                try writer.writeAll("}}");
            },
            .pointer => |pointer| {
                if (pointer.size == .slice) {
                    if (pointer.child == u8) {
                        // JSON and EDN share quoted-string escape syntax.
                        try std.json.Stringify.value(value, .{}, writer);
                    } else {
                        try writer.writeByte('[');
                        for (value) |item| {
                            try writeAt(writer, item, path ++ .{ "pointer", "child" });
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
                    try writeAt(writer, value[index], path ++ .{ if (@typeInfo(T) == .array) "array" else "vector", "child" });
                    try writer.writeByte(' ');
                }
                try writer.writeByte(']');
            },
            .@"struct" => |info| {
                try writer.writeByte(if (info.is_tuple) '[' else '{');
                inline for (info.field_names) |field_name| {
                    if (!info.is_tuple) {
                        try write(writer, @as([]const u8, field_name));
                        try writer.writeByte(' ');
                    }
                    try write(writer, @field(value, field_name));
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
                inline for (info.field_names) |field_name| {
                    if (!info.is_tuple) {
                        try writer.writeByte('[');
                        try write(writer, @as([]const u8, field_name));
                        try writer.writeByte(' ');
                    }
                    try inspect(writer, @field(value, field_name));
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
                const Child = if (@typeInfo(T) == .array) @typeInfo(T).array.child else @typeInfo(T).vector.child;
                try writer.writeByte('[');
                if (comptime @typeInfo(T) == .vector or requiresComptime(Child)) {
                    inline for (0..length) |index| {
                        try inspect(writer, value[index]);
                        try writer.writeByte(' ');
                    }
                } else {
                    for (0..length) |index| {
                        try inspect(writer, value[index]);
                        try writer.writeByte(' ');
                    }
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

    fn describeField(writer: *std.Io.Writer, comptime name: []const u8, comptime T: type) !void {
        try writer.writeAll("{:name ");
        try write(writer, name);
        try writer.writeAll(" :type ");
        try write(writer, @as([]const u8, @typeName(T)));
        try writer.writeAll("} ");
    }

    fn describeFunction(writer: *std.Io.Writer, comptime T: type) !void {
        const info = @typeInfo(T).@"fn";
        try writer.writeAll(" :parameters [");
        inline for (info.param_types) |param| {
            try writer.writeAll("{:type ");
            if (param) |P| try write(writer, @as([]const u8, @typeName(P))) else try writer.writeAll("nil");
            try writer.writeAll(" :generic? ");
            try write(writer, param == null);
            try writer.writeAll("} ");
        }
        try writer.writeAll("] :return ");
        if (info.return_type) |R| try write(writer, @as([]const u8, @typeName(R))) else try writer.writeAll("nil");
        try writer.writeAll(" :variadic? ");
        try write(writer, info.attrs.varargs);
    }

    fn describeType(writer: *std.Io.Writer, comptime T: type) !void {
        // Inspect types only: never load a receiver, dereference pointers, or
        // execute discovered functions (including on undefined native storage).
        const Container = switch (@typeInfo(T)) {
            .pointer => |p| if (p.size == .one) p.child else T,
            else => T,
        };
        try writer.writeAll("{:type ");
        try write(writer, @as([]const u8, @typeName(T)));
        try writer.writeAll(" :kind ");
        try write(writer, @tagName(@typeInfo(T)));
        if (@typeInfo(T) == .@"fn") try describeFunction(writer, T);
        try writer.writeAll(" :fields [");
        switch (@typeInfo(Container)) {
            inline .@"struct", .@"union" => |info| inline for (info.field_names, info.field_types) |field_name, field_type| {
                try describeField(writer, field_name, field_type);
            },
            .array => try describeField(writer, "len", @TypeOf(@as(Container, undefined).len)),
            .pointer => |p| if (p.size == .slice) {
                try describeField(writer, "len", @TypeOf(@as(T, undefined).len));
                try describeField(writer, "ptr", @TypeOf(@as(T, undefined).ptr));
            },
            else => {},
        }
        try writer.writeAll("] :members [");
        switch (@typeInfo(Container)) {
            inline .@"struct", .@"union", .@"enum", .@"opaque" => |info| {
                inline for (info.decl_names) |decl_name| {
                    const D = @TypeOf(@field(Container, decl_name));
                    try writer.writeAll("{:name ");
                    try write(writer, @as([]const u8, decl_name));
                    try writer.writeAll(" :type ");
                    try write(writer, @as([]const u8, @typeName(D)));
                    try writer.writeAll(" :kind ");
                    try write(writer, @tagName(@typeInfo(D)));
                    try writer.writeAll(" :declaration-kind ");
                    if (D == type) {
                        try writer.writeAll(":type");
                    } else if (@typeInfo(D) == .@"fn") {
                        try writer.writeAll(":function");
                    } else if (@typeInfo(@TypeOf(&@field(Container, decl_name))).pointer.attrs.@"const") {
                        try writer.writeAll(":const");
                    } else {
                        try writer.writeAll(":var");
                    }
                    if (@typeInfo(D) == .@"fn") try describeFunction(writer, D);
                    try writer.writeAll("} ");
                }
            },
            else => {},
        }
        try writer.writeAll("]}");
    }

    pub fn describeResult(comptime T: type) usize {
        var sink = NativeWriter.init();
        defer sink.deinit();
        const writer = &sink.writer;
        describeType(writer, T) catch @panic("Cannot describe Zig type");
        return sink.finish();
    }

    pub fn inspectResult(value: anytype) usize {
        var sink = NativeWriter.init();
        defer sink.deinit();
        const writer = &sink.writer;
        inspect(writer, value) catch @panic("Cannot inspect JVM value");
        return sink.finish();
    }

    pub fn result(value: anytype) usize {
        var sink = NativeWriter.init();
        defer sink.deinit();
        const writer = &sink.writer;
        writeResult(writer, value) catch @panic("Cannot encode JVM result");
        return sink.finish();
    }

    // Reflect storage eligibility in Zig. A type value, function body or
    // aggregate containing either cannot be copied into a native allocation.
    fn requiresComptime(comptime T: type) bool {
        return switch (@typeInfo(T)) {
            .type, .comptime_int, .comptime_float, .enum_literal, .@"fn", .null, .undefined => true,
            .array => |info| (info.len != 0 or info.sentinel_ptr != null) and requiresComptime(info.child),
            .vector => |info| info.len != 0 and requiresComptime(info.child),
            .optional => |info| requiresComptime(info.child),
            .error_union => |info| requiresComptime(info.payload),
            .@"struct" => |info| blk: {
                inline for (info.field_types, info.field_attrs) |field_type, field_attrs| {
                    if (!field_attrs.@"comptime" and requiresComptime(field_type)) break :blk true;
                }
                break :blk false;
            },
            .@"union" => |info| blk: {
                inline for (info.field_types) |field_type| {
                    if (requiresComptime(field_type)) break :blk true;
                }
                break :blk false;
            },
            else => false,
        };
    }

    // This is an inspection view only. The JVM retains the producing Zig
    // expression for round trips, including function bodies and comptime
    // pointers that cannot be reconstructed from a textual representation.
    fn inspectComptime(writer: *std.Io.Writer, comptime value: anytype) !void {
        const T = @TypeOf(value);
        switch (@typeInfo(T)) {
            .@"struct" => |info| {
                try writer.writeAll(if (info.is_tuple) "[" else "{:aguafria.jvm/struct [");
                inline for (info.field_names) |field_name| {
                    if (!info.is_tuple) {
                        try writer.writeByte('[');
                        try write(writer, @as([]const u8, field_name));
                        try writer.writeByte(' ');
                    }
                    try inspectComptime(writer, @field(value, field_name));
                    try writer.writeAll(if (info.is_tuple) " " else "] ");
                }
                try writer.writeAll(if (info.is_tuple) "]" else "]}");
            },
            .@"union" => |info| {
                if (info.tag_type) |_| {
                    switch (value) {
                        inline else => |payload, tag| {
                            try writer.writeAll("{:aguafria.jvm/struct [[");
                            try write(writer, @tagName(tag));
                            try writer.writeByte(' ');
                            try inspectComptime(writer, payload);
                            try writer.writeAll("]]}");
                        },
                    }
                } else {
                    try writer.writeAll("{:type ");
                    try write(writer, @as([]const u8, @typeName(T)));
                    try writer.writeAll(" :active-field :unknown}");
                }
            },
            .pointer => |info| {
                if (info.size == .slice) {
                    if (info.child == u8) {
                        try write(writer, value);
                    } else {
                        try writer.writeByte('[');
                        inline for (value) |item| {
                            try inspectComptime(writer, item);
                            try writer.writeByte(' ');
                        }
                        try writer.writeByte(']');
                    }
                } else {
                    try writer.writeAll("{:comptime-pointer-type ");
                    try write(writer, @as([]const u8, @typeName(T)));
                    try writer.writeByte('}');
                }
            },
            .array, .vector => {
                const length = if (@typeInfo(T) == .array) @typeInfo(T).array.len else @typeInfo(T).vector.len;
                try writer.writeByte('[');
                inline for (0..length) |index| {
                    try inspectComptime(writer, value[index]);
                    try writer.writeByte(' ');
                }
                try writer.writeByte(']');
            },
            .optional => if (value) |payload| try inspectComptime(writer, payload) else try writer.writeAll("nil"),
            .error_union => {
                if (value) |payload| {
                    try writer.writeAll("{:ok ");
                    try inspectComptime(writer, payload);
                    try writer.writeByte('}');
                } else |err| {
                    try writer.writeAll("{:error {:name ");
                    try write(writer, @errorName(err));
                    try writer.writeAll("}}");
                }
            },
            .@"enum", .enum_literal => {
                try writer.writeAll("{:aguafria.jvm/enum ");
                try write(writer, @tagName(value));
                try writer.writeByte('}');
            },
            .type => {
                try writer.writeAll("{:type ");
                try write(writer, @as([]const u8, @typeName(value)));
                try writer.writeByte('}');
            },
            .@"fn" => {
                try writer.writeAll("{:function-type ");
                try write(writer, @as([]const u8, @typeName(T)));
                try writer.writeByte('}');
            },
            else => try write(writer, value),
        }
    }

    fn writeComptimeExpression(writer: *std.Io.Writer, comptime value: anytype) !void {
        try writer.writeAll("{:aguafria.jvm/comptime-expression {:type-name ");
        try write(writer, @as([]const u8, @typeName(@TypeOf(value))));
        try writer.writeAll(" :snapshot ");
        try inspectComptime(writer, value);
        try writer.writeAll("}}");
    }

    pub fn comptimeExpressionResult(comptime value: anytype) usize {
        // Type results remain callable type handles, not inspection maps.
        // Booleans retain JVM truth semantics; a boxed false is truthy.
        if (@TypeOf(value) == type or @TypeOf(value) == bool) return result(value);
        if (@typeInfo(@TypeOf(value)) == .@"fn") return fieldResult(value);
        var sink = NativeWriter.init();
        defer sink.deinit();
        const writer = &sink.writer;
        writeComptimeExpression(writer, value) catch @panic("Cannot inspect comptime JVM result");
        return sink.finish();
    }

    pub fn concatenationResult(comptime value: anytype) usize {
        const T = @TypeOf(value);
        if (@typeInfo(T) == .pointer) {
            const pointer = @typeInfo(T).pointer;
            if (pointer.size == .one and @typeInfo(pointer.child) == .array and
                @typeInfo(pointer.child).array.child == u8)
            {
                // Keep both the literal expression for comptime parameters and
                // native storage for ordinary pointer operations on its result.
                var sink = NativeWriter.init();
                defer sink.deinit();
                const writer = &sink.writer;
                writer.writeAll("{:aguafria.jvm/comptime-expression {:native ") catch
                    @panic("Cannot encode concatenation result");
                writeNativeResult(writer, value) catch @panic("Cannot encode concatenation result");
                writer.writeAll("}}") catch @panic("Cannot encode concatenation result");
                return sink.finish();
            }
        }
        return result(value);
    }

    fn writeResult(writer: *std.Io.Writer, value: anytype) !void {
        const T = @TypeOf(value);
        if (T == BoundMethod) return write(writer, value);
        if (comptime requiresComptime(T) and switch (@typeInfo(T)) {
            .@"struct", .@"union", .array, .vector, .optional, .error_union, .@"fn" => true,
            else => false,
        }) {
            return writeComptimeExpression(writer, value);
        }
        switch (@typeInfo(T)) {
            .@"struct" => |info| {
                if (comptime hasStructuralType(T)) {
                    try writeNativeResult(writer, value);
                } else if (info.is_tuple) {
                    // A tuple is a collection of public results, not an
                    // inspection snapshot. Retain each element's native type
                    // and ownership exactly as when returning it on its own.
                    try writer.writeByte('[');
                    inline for (info.field_names) |field_name| {
                        try writeResult(writer, @field(value, field_name));
                        try writer.writeByte(' ');
                    }
                    try writer.writeByte(']');
                } else {
                    try writeNativeResult(writer, value);
                }
            },
            .comptime_int, .comptime_float => {
                try writer.writeAll("{:aguafria.jvm/comptime {:type :");
                try writer.writeAll(@typeName(T));
                try writer.writeAll(" :value ");
                try write(writer, value);
                try writer.writeAll("}}");
            },
            .int, .float, .array, .vector, .pointer => try writeNativeResult(writer, value),
            else => try write(writer, value),
        }
    }

    fn writeNativeResult(writer: *std.Io.Writer, value: anytype) !void {
        // Preserve the exact type, ownership and address of runtime results.
        // Inspection still produces ordinary EDN through write/inspectResult.
        const T = @TypeOf(value);
        const size = @sizeOf(T);
        const bytes = aguafria_jvm_allocate(size, @alignOf(T)) orelse return error.OutOfMemory;
        errdefer aguafria_jvm_release_native(@intFromPtr(bytes), size, @alignOf(T));
        @memcpy(bytes[0..size], std.mem.asBytes(&value));
        try writer.print("{{:aguafria.jvm/native {{:address {d} :size {d} :alignment {d} :path []", .{ @intFromPtr(bytes), size, @alignOf(T) });
        try writeTupleLength(writer, T);
        if (@typeInfo(T) == .int or @typeInfo(T) == .float) {
            try writer.writeAll(" :scalar-type :");
            try writer.writeAll(@typeName(T));
        }
        if (comptime hasStructuralType(T)) {
            try writer.writeAll(" :native-type ");
            try writeStructuralType(writer, T);
        }
        try writer.writeAll("}}");
    }

    fn writeTupleLength(writer: *std.Io.Writer, comptime T: type) !void {
        switch (@typeInfo(T)) {
            .@"struct" => |s| if (s.is_tuple) {
                try writer.print(" :tuple-length {d}", .{s.field_types.len});
            },
            else => {},
        }
    }

    pub fn fieldResult(value: anytype) usize {
        // Static container functions become callable JVM closures, just like
        // instance methods. A function has no runtime value to serialize.
        if (@typeInfo(@TypeOf(value)) == .@"fn") {
            return result(BoundMethod{});
        }
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

    pub fn declarationFieldResult(value: anytype, comptime Container: type, comptime name: []const u8) usize {
        // A typed container constant may coerce where a runtime value of the
        // same type cannot. Keep its compiler-known expression across JVM calls.
        switch (@typeInfo(Container)) {
            .@"struct", .@"union", .@"enum", .@"opaque" => {},
            else => return fieldResult(value),
        }
        if (@hasDecl(Container, name)) {
            switch (@typeInfo(@TypeOf(value))) {
                .int, .float => if (@typeInfo(@TypeOf(&@field(Container, name))).pointer.attrs.@"const") {
                    return comptimeExpressionResult(@field(Container, name));
                },
                else => {},
            }
        }
        return fieldResult(value);
    }

    pub fn release(address: usize) void {
        aguafria_jvm_release(address);
    }

    pub fn releaseNative(address: usize, size: usize, alignment: usize) void {
        aguafria_jvm_release_native(address, size, alignment);
    }

    pub fn comptimeResult(comptime value: anytype) usize {
        switch (@typeInfo(@TypeOf(value))) {
            .int, .float, .bool, .comptime_int, .comptime_float, .enum_literal, .null, .type => {},
            else => return result(null),
        }
        var sink = NativeWriter.init();
        defer sink.deinit();
        const writer = &sink.writer;
        // This is a transport envelope, not a field path in the source value.
        writer.writeAll("{:comptime_value ") catch @panic("Cannot write comptime result");
        write(writer, value) catch @panic("Cannot write comptime result");
        writer.writeByte('}') catch @panic("Cannot write comptime result");
        return sink.finish();
    }
};
