// Inspection-only compiler reflection. No runtime entry points.
// The caller supplies declaration identities, not an inferred structural
// replacement. Only the compiler may establish equality with one of them.
pub fn Inspector(comptime declarations: anytype) type {
    return struct {
        const std = @import("std");

        pub inline fn log(comptime message: []const u8) void {
            const encoded = comptime std.fmt.bytesToHex(message[0..message.len].*, .lower);
            @compileLog("aguafria.operation.hex:" ++ encoded);
        }

        pub fn literal(comptime value: anytype) []const u8 {
            @setEvalBranchQuota(10_000_000);
            if (@TypeOf(value) == comptime_float)
                return std.fmt.comptimePrint("{{:literal {e} :type :comptime_float}}", .{value});
            return std.fmt.comptimePrint("{{:literal {d} :type :{s}}}", .{ value, @typeName(@TypeOf(value)) });
        }

        fn quoted(comptime bytes: []const u8) []const u8 {
            var result: []const u8 = "\"";
            for (bytes) |byte| {
                result = result ++ switch (byte) {
                    '"' => "\\\"",
                    '\\' => "\\\\",
                    '\n' => "\\n",
                    '\r' => "\\r",
                    '\t' => "\\t",
                    0...8, 11, 12, 14...31, 127 => std.fmt.comptimePrint("\\u{x:0>4}", .{@as(u16, byte)}),
                    else => &[_]u8{byte},
                };
            }
            return result ++ "\"";
        }

        pub fn comptimeValue(comptime value: anytype) []const u8 {
            const T = @TypeOf(value);
            return switch (@typeInfo(T)) {
                .comptime_int, .comptime_float => literal(value),
                .int => std.fmt.comptimePrint("{{:comptime {d}}}", .{value}),
                .bool => if (value) "{:comptime true}" else "{:comptime false}",
                .enum_literal => enum_literal: {
                    const name = @tagName(value);
                    if (name.len == 0) break :enum_literal "nil";
                    for (name) |c| {
                        if (!std.ascii.isAlphanumeric(c) and c != '_') break :enum_literal "nil";
                    }
                    break :enum_literal "{:comptime :." ++ name ++ "}";
                },
                .type => "{:comptime-type " ++ schema(value) ++ "}",
                .null => ":null",
                .pointer => |p| pointer: {
                    if ((p.size == .slice and p.child == u8) or
                        (p.size == .one and @typeInfo(p.child) == .array and @typeInfo(p.child).array.child == u8))
                    {
                        // JVM strings cannot preserve arbitrary invalid UTF-8
                        // bytes. Keep that case unsupported, never substitute
                        // replacement characters in a prepared specialization.
                        if (!std.unicode.utf8ValidateSlice(value)) break :pointer "nil";
                        break :pointer "{:comptime " ++ quoted(value) ++ "}";
                    }
                    break :pointer "nil";
                },
                else => "nil",
            };
        }

        pub fn hasDeclaration(comptime Container: type, comptime name: []const u8) bool {
            return switch (@typeInfo(Container)) {
                .@"struct", .@"union", .@"enum", .@"opaque" => @hasDecl(Container, name),
                else => false,
            };
        }

        pub fn declarationArgumentSchema(comptime Container: type, comptime name: []const u8, comptime member_form: []const u8) []const u8 {
            const T = @TypeOf(@field(Container, name));
            if (T == comptime_int or T == comptime_float) return literal(@field(Container, name));
            if (T == type) return "{:comptime-type " ++ schema(@field(Container, name)) ++ "}";
            switch (@typeInfo(Container)) {
                .@"struct", .@"union", .@"enum", .@"opaque" => {},
                else => return argumentSchema(T),
            }
            if (@hasDecl(Container, name)) {
                switch (@typeInfo(T)) {
                    .int, .float => if (@typeInfo(@TypeOf(&@field(Container, name))).pointer.is_const) {
                        const identity = schema(Container);
                        const expression = if (identity[0] == '[' or identity[0] == '(')
                            "(type " ++ identity ++ ")"
                        else
                            identity;
                        return "{:comptime-expression (aguafria.zig/field " ++ expression ++ " " ++ member_form ++ ")}";
                    },
                    else => {},
                }
            }
            return argumentSchema(T);
        }

        pub fn argumentSchema(comptime T: type) []const u8 {
            @setEvalBranchQuota(10_000_000);
            // JVM boolean results are ordinary booleans, so syntax calls may
            // embed them instead of receiving a native handle. Zig establishes
            // this finite domain; do not infer it from the Clojure expression.
            if (T == bool)
                return "{:representations [:bool {:comptime false} {:comptime true}]}";
            // A declared tuple is still a nominal container, not a JVM vector
            // of independently supplied arguments. Preserve the declaration
            // only after Zig establishes that it denotes this exact type.
            if (declarationSchema(T)) |identity| return identity;
            if (@typeInfo(T) == .@"struct" and @typeInfo(T).@"struct".is_tuple) {
                var result: []const u8 = "{:tuple [";
                for (@typeInfo(T).@"struct".fields) |field| {
                    // A typed value remains a typed JVM operand even when Zig
                    // happened to constant-fold this particular tuple. Only
                    // storage-free types require embedding their actual value.
                    const entry = if (field.is_comptime and
                        (field.type == comptime_int or field.type == comptime_float or
                            field.type == type or @typeInfo(field.type) == .enum_literal))
                        comptimeValue(field.defaultValue().?)
                    else if (field.is_comptime and field.type == bool)
                        schema(field.type)
                    else
                        argumentSchema(field.type);
                    const constant = if (field.is_comptime and
                        (field.type == bool or @typeInfo(field.type) == .pointer))
                        comptimeValue(field.defaultValue().?)
                    else
                        "nil";
                    // A bool/string constant can arrive from the JVM as an
                    // ordinary literal or an explicitly typed native handle.
                    // Zig supplies both its exact type and constant value;
                    // preparation must not guess which representation was used.
                    result = result ++ if (!std.mem.eql(u8, constant, "nil"))
                        "{:representations [" ++ entry ++ " " ++ constant ++ "]} "
                    else
                        entry ++ " ";
                }
                const elements = result ++ "]}";
                const native = schema(T);
                return if (std.mem.eql(u8, native, "nil")) elements else "{:representations [" ++ native ++ " " ++ elements ++ "]}";
            }
            return schema(T);
        }

        fn declarationSchema(comptime T: type) ?[]const u8 {
            switch (@typeInfo(T)) {
                .@"struct", .@"enum", .@"union", .@"opaque", .@"fn" => {
                    inline for (declarations) |declaration| {
                        if (T == declaration[0].get()) return declaration[1];
                    }
                    inline for (declarations) |declaration| {
                        if (wrappedDeclarationSchema(T, declaration[0].get(), declaration[1], 0)) |identity|
                            return identity;
                        if (nestedDeclarationSchema(T, declaration[0].get(), declaration[1], 0)) |identity|
                            return identity;
                        if (fieldDeclarationSchema(T, declaration[0].get(), declaration[1])) |identity|
                            return identity;
                    }
                },
                else => {},
            }
            return null;
        }

        fn wrappedDeclarationSchema(comptime T: type, comptime Root: type, comptime expression: []const u8, comptime depth: usize) ?[]const u8 {
            if (depth == 16) return null;
            const info = @typeInfo(Root);
            if (info == .@"fn") {
                const function_expression = "(aguafria.keyword/field (aguafria.keyword/typeInfo " ++ expression ++ ") \"fn\")";
                if (info.@"fn".return_type) |Return| {
                    const result_expression = "(aguafria.zig/unwrap (aguafria.zig/field " ++ function_expression ++ " \"return_type\"))";
                    if (T == Return) return result_expression;
                    if (wrappedDeclarationSchema(T, Return, result_expression, depth + 1)) |identity|
                        return identity;
                }
                inline for (info.@"fn".params, 0..) |parameter, index| {
                    if (parameter.type) |Parameter| {
                        const parameter_expression = "(aguafria.zig/unwrap (aguafria.zig/field (aguafria.zig/index (aguafria.zig/field " ++ function_expression ++ " \"params\") " ++ std.fmt.comptimePrint("{d}", .{index}) ++ ") \"type\"))";
                        if (T == Parameter) return parameter_expression;
                        if (wrappedDeclarationSchema(T, Parameter, parameter_expression, depth + 1)) |identity|
                            return identity;
                    }
                }
                return null;
            }
            const Child = switch (info) {
                .pointer => |p| p.child,
                .optional => |o| o.child,
                .array => |a| a.child,
                .vector => |v| v.child,
                else => return null,
            };
            const child_expression = "(aguafria.zig/field (aguafria.zig/field (aguafria.keyword/typeInfo " ++ expression ++ ") " ++ quoted(@tagName(info)) ++ ") \"child\")";
            if (T == Child) return child_expression;
            return wrappedDeclarationSchema(T, Child, child_expression, depth + 1);
        }

        fn fieldDeclarationSchema(comptime T: type, comptime Root: type, comptime expression: []const u8) ?[]const u8 {
            return switch (@typeInfo(Root)) {
                .@"struct" => |s| fieldsDeclarationSchema(T, expression, s.fields),
                .@"union" => |u| fieldsDeclarationSchema(T, expression, u.fields),
                else => null,
            };
        }

        fn fieldsDeclarationSchema(comptime T: type, comptime expression: []const u8, comptime fields: anytype) ?[]const u8 {
            inline for (fields) |field| {
                const field_expression = "(aguafria.keyword/FieldType " ++ expression ++ " " ++ quoted(field.name) ++ ")";
                if (T == field.type) return field_expression;
                if (wrappedDeclarationSchema(T, field.type, field_expression, 0)) |identity|
                    return identity;
            }
            return null;
        }

        fn nestedDeclarationSchema(comptime T: type, comptime Root: type, comptime expression: []const u8, comptime depth: usize) ?[]const u8 {
            if (depth == 8) return null;
            // A qualified type name narrows the search, but never establishes
            // identity. Only equality with the actual declaration may do that.
            if (!std.mem.startsWith(u8, @typeName(T), @typeName(Root) ++ ".")) return null;
            const info = @typeInfo(Root);
            const members = switch (info) {
                .@"struct" => |s| s.decls,
                .@"union" => |u| u.decls,
                .@"enum" => |e| e.decls,
                .@"opaque" => |o| o.decls,
                else => return null,
            };
            const relative_name = @typeName(T)[@typeName(Root).len + 1 ..];
            const has_named_member = comptime named: {
                for (members) |member| {
                    if (std.mem.eql(u8, relative_name, member.name) or
                        std.mem.startsWith(u8, relative_name, member.name ++ ".")) break :named true;
                }
                break :named false;
            };
            // Reflection cannot safely evaluate every declaration: translated
            // C macros and lazy Zig declarations may deliberately fail. Leave
            // unnamed identities unresolved instead of forcing unrelated code.
            if (!has_named_member) return null;
            inline for (members) |member| {
                // C imports can contain declarations whose value is
                // @compileError (unsupported macros). Prefer the matching name
                // without evaluating unrelated declarations.
                if (!std.mem.eql(u8, relative_name, member.name) and
                    !std.mem.startsWith(u8, relative_name, member.name ++ ".")) continue;
                if (@TypeOf(@field(Root, member.name)) == type) {
                    const Child = @field(Root, member.name);
                    const child_expression = "(aguafria.zig/field " ++ expression ++ " " ++ quoted(member.name) ++ ")";
                    if (T == Child) return child_expression;
                    if (Child != Root) {
                        if (nestedDeclarationSchema(T, Child, child_expression, depth + 1)) |identity|
                            return identity;
                    }
                }
            }
            return null;
        }

        pub fn schema(comptime T: type) []const u8 {
            @setEvalBranchQuota(10_000_000);
            if (declarationSchema(T)) |identity| return identity;
            return switch (@typeInfo(T)) {
                .int, .float, .bool, .void, .noreturn, .comptime_int, .comptime_float, .type => ":" ++ @typeName(T),
                .null => ":null",
                .undefined => ":undefined",
                .@"struct" => |s| tuple: {
                    if (!s.is_tuple) break :tuple "nil";
                    var types: [s.fields.len]type = undefined;
                    var result: []const u8 = "(aguafria.keyword/Tuple (aguafria.keyword/& [";
                    for (s.fields, 0..) |field, i| {
                        if (field.is_comptime) break :tuple "nil";
                        const child = schema(field.type);
                        if (std.mem.eql(u8, child, "nil")) break :tuple "nil";
                        types[i] = field.type;
                        result = result ++ if (std.mem.startsWith(u8, child, "["))
                            "(aguafria.zig/type " ++ child ++ ") "
                        else
                            child ++ " ";
                    }
                    if (T != @Tuple(&types)) break :tuple "nil";
                    break :tuple result ++ "]))";
                },
                .array => |a| array: {
                    if (a.sentinel()) |sentinel| {
                        switch (@typeInfo(a.child)) {
                            .int => break :array std.fmt.comptimePrint("[:array {d} {{:sentinel {d}}} {s}]", .{ a.len, sentinel, schema(a.child) }),
                            .bool => break :array std.fmt.comptimePrint("[:array {d} {{:sentinel {s}}} {s}]", .{ a.len, if (sentinel) "true" else "false", schema(a.child) }),
                            else => break :array "nil",
                        }
                    }
                    break :array std.fmt.comptimePrint("[:array {d} {s}]", .{ a.len, schema(a.child) });
                },
                .vector => |v| std.fmt.comptimePrint("[:vector {d} {s}]", .{ v.len, schema(v.child) }),
                .optional => |o| "[:optional " ++ schema(o.child) ++ "]",
                .error_union => |e| "[:error-union " ++ schema(e.error_set) ++ " " ++ schema(e.payload) ++ "]",
                .error_set => |errors| errors: {
                    const fields = errors orelse break :errors ":anyerror";
                    var result: []const u8 = "[:error-set [";
                    for (fields) |field| {
                        // Only canonical EDN keywords here; never lose an exact
                        // escaped Zig name by inventing a similar identifier.
                        for (field.name) |c| {
                            if (!std.ascii.isAlphanumeric(c) and c != '_') break :errors "nil";
                        }
                        if (std.ascii.isDigit(field.name[0])) break :errors "nil";
                        result = result ++ ":" ++ field.name ++ " ";
                    }
                    break :errors result ++ "]]";
                },
                .pointer => |p| pointer: {
                    if (p.size == .many and p.sentinel_ptr != null and
                        !p.is_volatile and !p.is_allowzero and p.address_space == .generic and
                        p.alignment == null)
                    {
                        const sentinel = switch (@typeInfo(p.child)) {
                            .int => std.fmt.comptimePrint("{d}", .{p.sentinel().?}),
                            .bool => if (p.sentinel().?) "true" else "false",
                            else => break :pointer "nil",
                        };
                        return "[:" ++ (if (p.is_const) "sentinel-const" else "sentinel") ++ " " ++ schema(p.child) ++ " " ++ sentinel ++ "]";
                    }
                    const qualified = p.sentinel_ptr != null or p.is_volatile or (p.is_allowzero and p.size != .c) or
                        p.address_space != .generic or
                        p.alignment != null or
                        (p.size == .c and p.is_const);
                    if (qualified) {
                        var options: []const u8 = if (p.size == .one) "" else ":size :" ++ @tagName(p.size);
                        if (p.is_const) options = options ++ " :const? true";
                        if (p.is_volatile) options = options ++ " :volatile? true";
                        if (p.is_allowzero and p.size != .c) options = options ++ " :allowzero? true";
                        if (p.alignment) |alignment| {
                            options = options ++ std.fmt.comptimePrint(" :align {d}", .{alignment});
                        }
                        if (p.address_space != .generic)
                            options = options ++ " :addrspace :." ++ @tagName(p.address_space);
                        if (p.sentinel()) |sentinel| {
                            options = options ++ " :sentinel " ++ switch (@typeInfo(p.child)) {
                                .int => std.fmt.comptimePrint("{d}", .{sentinel}),
                                .bool => if (sentinel) "true" else "false",
                                else => break :pointer "nil",
                            };
                        }
                        break :pointer "[:* {" ++ options ++ "} " ++ schema(p.child) ++ "]";
                    }
                    const tag = switch (p.size) {
                        .one => if (p.is_const) "*const" else "*",
                        .many => if (p.is_const) "many-const" else "many",
                        .slice => if (p.is_const) "slice-const" else "slice",
                        .c => "c-pointer",
                    };
                    break :pointer "[:" ++ tag ++ " " ++ schema(p.child) ++ "]";
                },
                // Nominal identity and unsupported qualifiers must not be replaced
                // with a structurally similar type or guessed from a display name.
                else => "nil",
            };
        }
    };
}
