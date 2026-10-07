// Inspection-only compiler reflection. No runtime entry points.
// The caller supplies declaration identities, not an inferred structural
// replacement. Only the compiler may establish equality with one of them.
pub fn Inspector(comptime declarations: anytype) type {
    return struct {
        const std = @import("std");
        pub const requiresComptime = @import("jvm_result.zig").__aguafria_jvm.requiresComptime;

        pub fn indexRequiresComptime(comptime T: type) bool {
            return switch (@typeInfo(T)) {
                .pointer => |info| requiresComptime(info.child),
                .array => |info| requiresComptime(info.child),
                .vector => |info| requiresComptime(info.child),
                .@"struct" => |info| info.is_tuple,
                else => false,
            };
        }

        const VisitedTypes = struct {
            buckets: [1024][]const type = @splat(&.{}),

            fn visit(comptime self: *VisitedTypes, comptime T: type) bool {
                // Names select buckets; only native type equality establishes
                // whether a type was already visited, including hash collisions.
                const index = std.hash.Wyhash.hash(0, @typeName(T)) % self.buckets.len;
                inline for (self.buckets[index]) |Seen| if (Seen == T) return false;
                self.buckets[index] = self.buckets[index] ++ [_]type{T};
                return true;
            }
        };

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

        pub fn integerCoercionRequiresConstant(comptime Result: type, comptime Input: type) bool {
            if (@typeInfo(Result) != .int or @typeInfo(Input) != .int) return false;
            const result = @typeInfo(Result).int;
            const input = @typeInfo(Input).int;
            const accepts_runtime = if (result.signedness == input.signedness)
                result.bits >= input.bits
            else
                result.signedness == .signed and result.bits > input.bits;
            return !accepts_runtime;
        }

        pub fn constantCoercion(comptime value: anytype) []const u8 {
            const identity = schema(@TypeOf(value));
            return std.fmt.comptimePrint(
                "{{:constant-coercion [{s} (aguafria.keyword/as {d} {s})]}}",
                .{ identity, value, identity },
            );
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

        pub fn comptimeExpression(comptime value: anytype, comptime source: []const u8) []const u8 {
            _ = @TypeOf(value);
            return "{:comptime-expression " ++ source ++ "}";
        }

        pub fn comptimeArgument(comptime value: anytype, comptime source: []const u8) []const u8 {
            const encoded = comptimeValue(value);
            if (!std.mem.eql(u8, encoded, "nil")) return encoded;
            switch (@typeInfo(@TypeOf(value))) {
                .pointer => |p| {
                    // An invalid UTF-8 byte literal is not a JVM string. Its
                    // ordinary native value does not retain a source expression.
                    if ((p.size == .slice and p.child == u8) or
                        (p.size == .one and @typeInfo(p.child) == .array and @typeInfo(p.child).array.child == u8))
                        return "nil";
                },
                else => {},
            }
            return comptimeExpression(value, source);
        }

        pub fn comptimeValue(comptime value: anytype) []const u8 {
            @setEvalBranchQuota(10_000_000);
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
                .error_set => named_error: {
                    const name = @errorName(value);
                    const identity = if (T == anyerror)
                        "[:error-set [:" ++ name ++ "]]"
                    else
                        schema(T);
                    break :named_error "{:comptime-expression (aguafria.keyword/as (aguafria.zig/field (aguafria.zig/type " ++
                        identity ++ ") :" ++ name ++ ") " ++ identity ++ ")}";
                },
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
            if (@typeInfo(Container) == .@"enum" and T == Container and !@hasDecl(Container, name))
                return declarationExpression(Container, member_form);
            if (@hasDecl(Container, name)) {
                switch (@typeInfo(T)) {
                    .int, .float, .@"enum" => if (@typeInfo(@TypeOf(&@field(Container, name))).pointer.attrs.@"const") {
                        return declarationExpression(Container, member_form);
                    },
                    else => {},
                }
            }
            return argumentSchema(T);
        }

        fn declarationExpression(comptime Container: type, comptime member_form: []const u8) []const u8 {
            const identity = schema(Container);
            const expression = if (identity[0] == '[' or identity[0] == '(')
                "(type " ++ identity ++ ")"
            else
                identity;
            return "{:comptime-expression (aguafria.zig/field " ++ expression ++ " " ++ member_form ++ ")}";
        }

        pub fn argumentSchema(comptime T: type) []const u8 {
            @setEvalBranchQuota(10_000_000);
            // JVM boolean results are ordinary booleans, so syntax calls may
            // embed them instead of receiving a native handle. Zig establishes
            // this finite domain; do not infer it from the Clojure expression.
            if (T == bool)
                return "{:representations [:bool {:comptime false} {:comptime true}]}";
            if (@typeInfo(T) == .error_set) {
                const identity = schema(T);
                const names = @typeInfo(T).error_set.error_names orelse return identity;
                if (std.mem.eql(u8, identity, "nil")) return identity;
                var result: []const u8 = "{:representations [" ++ identity;
                for (names) |name| {
                    // JVM errors cross native-library boundaries by name.
                    // Keep the addressable variant and each named expression.
                    result = result ++
                        " {:comptime-expression (aguafria.keyword/as (aguafria.zig/field (aguafria.zig/type " ++
                        identity ++ ") :" ++ name ++ ") " ++ identity ++ ")}";
                }
                return result ++ "]}";
            }
            // A declared tuple is still a nominal container, not a JVM vector
            // of independently supplied arguments. Preserve the declaration
            // only after Zig establishes that it denotes this exact type.
            if (declarationSchema(T)) |identity| return identity;
            if (@typeInfo(T) == .@"struct" and @typeInfo(T).@"struct".is_tuple) {
                var result: []const u8 = "{:tuple [";
                const info = @typeInfo(T).@"struct";
                for (info.field_types, info.field_attrs) |field_type, field_attrs| {
                    // A typed value remains a typed JVM operand even when Zig
                    // happened to constant-fold this particular tuple. Only
                    // storage-free types require embedding their actual value.
                    const entry = if (field_attrs.@"comptime" and
                        (field_type == comptime_int or field_type == comptime_float or
                            field_type == type or @typeInfo(field_type) == .enum_literal))
                        comptimeValue(field_attrs.defaultValue(field_type).?)
                    else if (field_attrs.@"comptime" and field_type == bool)
                        schema(field_type)
                    else
                        argumentSchema(field_type);
                    const constant = if (field_attrs.@"comptime" and
                        (field_type == bool or @typeInfo(field_type) == .pointer))
                        comptimeValue(field_attrs.defaultValue(field_type).?)
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

        pub fn jvmMapStringArgumentSchema(comptime value: anytype) []const u8 {
            const T = @TypeOf(value);
            const native = argumentSchema(T);
            const constant = comptimeValue(value);
            // Ordinary generic JVM map calls transport strings as slices;
            // syntax field calls may instead embed the literal. Ask Zig to
            // validate that exact original constant's slice representation.
            if (@typeInfo(T) != .pointer or std.mem.eql(u8, constant, "nil")) return native;
            const pointer = @typeInfo(T).pointer;
            if (!((pointer.size == .slice and pointer.child == u8) or
                (pointer.size == .one and @typeInfo(pointer.child) == .array and
                    @typeInfo(pointer.child).array.child == u8))) return native;
            return "{:representations [" ++ native ++ " " ++ constant ++ " " ++
                schema(@TypeOf(@as([]const u8, value))) ++ "]}";
        }

        pub fn jvmMapArgumentSchema(comptime T: type) []const u8 {
            @setEvalBranchQuota(10_000_000);
            // This describes the independently supplied fields of an ordinary
            // JVM map. It does NOT name T, borrow its storage, or assert that a
            // newly emitted anonymous literal has T's nominal identity.
            if (@typeInfo(T) != .@"struct") return "nil";
            if (declarationSchema(T) != null) return "nil";
            const info = @typeInfo(T).@"struct";
            if (info.is_tuple or info.decl_names.len != 0) return "nil";
            var result: []const u8 = "{:map {";
            inline for (info.field_names, info.field_types, info.field_attrs) |name, Field, attrs| {
                if (name.len == 0) return "nil";
                for (name) |c| {
                    if (!std.ascii.isAlphanumeric(c) and c != '_') return "nil";
                }
                const entry = if (attrs.@"comptime" and @typeInfo(Field) == .pointer)
                    jvmMapStringArgumentSchema(attrs.defaultValue(Field).?)
                else if (attrs.@"comptime" and
                    (Field == comptime_int or Field == comptime_float or
                        Field == type or @typeInfo(Field) == .enum_literal))
                    comptimeValue(attrs.defaultValue(Field).?)
                else
                    argumentSchema(Field);
                result = result ++ ":" ++ name ++ " " ++ entry ++ " ";
            }
            return result ++ "}}";
        }

        pub fn declaredSchema(comptime T: type, comptime Declaration: type, comptime expression: []const u8) []const u8 {
            if (T == Declaration) return expression;
            return schema(T);
        }

        fn declarationSchema(comptime T: type) ?[]const u8 {
            switch (@typeInfo(T)) {
                .@"struct", .@"enum", .@"union", .@"opaque", .@"fn" => {
                    // Search containers before unrelated function signatures.
                    // Merely resolving a foreign prototype can reject its ABI
                    // on this target, even though the queried type is unrelated.
                    comptime var visited: VisitedTypes = .{};
                    const order = if (@typeInfo(T) == .@"fn") .{ true, false } else .{ false, true };
                    inline for (order) |function_roots| {
                        inline for (declarations) |declaration| {
                            if (declaration.function_root != function_roots) continue;
                            if (T == declaration.get()) return declaration.name();
                        }
                        inline for (declarations) |declaration| {
                            if (declaration.function_root != function_roots) continue;
                            const Root = declaration.get();
                            if (wrappedDeclarationSchema(T, Root, declaration.name(), 0)) |identity|
                                return identity;
                            if (nestedDeclarationSchema(T, Root, declaration.name(), 0)) |identity|
                                return identity;
                            if (fieldDeclarationSchema(T, Root, declaration.name())) |identity|
                                return identity;
                        }
                        inline for (declarations) |declaration| {
                            if (declaration.function_root != function_roots) continue;
                            const Root = declaration.get();
                            if (reachableDeclarationSchema(T, Root, declaration.name(), &visited)) |identity|
                                return identity;
                        }
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
                const function_expression = "(aguafria.zig/field (aguafria.keyword/typeInfo " ++ expression ++ ") :fn)";
                if (info.@"fn".return_type) |Return| {
                    const result_expression = "(aguafria.zig/unwrap (aguafria.zig/field " ++ function_expression ++ " \"return_type\"))";
                    if (T == Return) return result_expression;
                    if (wrappedDeclarationSchema(T, Return, result_expression, depth + 1)) |identity|
                        return identity;
                }
                inline for (info.@"fn".param_types, 0..) |parameter, index| {
                    if (parameter) |Parameter| {
                        const parameter_expression = "(aguafria.zig/unwrap (aguafria.zig/index (aguafria.zig/field " ++ function_expression ++ " \"param_types\") " ++ std.fmt.comptimePrint("{d}", .{index}) ++ "))";
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
            if (@typeInfo(Root) == .@"union") {
                if (@typeInfo(Root).@"union".tag_type) |Tag| {
                    if (T == Tag)
                        return "(aguafria.zig/unwrap (aguafria.zig/field (aguafria.zig/field (aguafria.keyword/typeInfo " ++
                            expression ++ ") :union) :tag_type))";
                }
            }
            return switch (@typeInfo(Root)) {
                .@"struct" => |s| fieldsDeclarationSchema(T, expression, s.field_names, s.field_types),
                .@"union" => |u| fieldsDeclarationSchema(T, expression, u.field_names, u.field_types),
                else => null,
            };
        }

        fn fieldsDeclarationSchema(comptime T: type, comptime expression: []const u8, comptime names: []const [:0]const u8, comptime types: []const type) ?[]const u8 {
            inline for (names, types) |field_name, field_type| {
                const field_expression = "(aguafria.keyword/FieldType " ++ expression ++ " " ++ quoted(field_name) ++ ")";
                if (T == field_type) return field_expression;
                if (wrappedDeclarationSchema(T, field_type, field_expression, 0)) |identity|
                    return identity;
            }
            return null;
        }

        fn reachableDeclarationSchema(comptime T: type, comptime Root: type, comptime expression: []const u8, comptime visited: *VisitedTypes) ?[]const u8 {
            if (T == Root) return expression;
            if (!visited.visit(Root)) return null;
            const info = @typeInfo(Root);
            switch (info) {
                .@"struct", .@"union" => {
                    if (info == .@"union") {
                        if (info.@"union".tag_type) |Tag| {
                            const tag_expression = "(aguafria.zig/unwrap (aguafria.zig/field (aguafria.zig/field (aguafria.keyword/typeInfo " ++
                                expression ++ ") :union) :tag_type))";
                            if (reachableDeclarationSchema(T, Tag, tag_expression, visited)) |identity|
                                return identity;
                        }
                    }
                    const fields = if (info == .@"struct") info.@"struct" else info.@"union";
                    inline for (fields.field_names, fields.field_types) |name, Field| {
                        const field_expression = "(aguafria.keyword/FieldType " ++ expression ++ " " ++ quoted(name) ++ ")";
                        if (reachableDeclarationSchema(T, Field, field_expression, visited)) |identity|
                            return identity;
                    }
                },
                .pointer, .optional, .array, .vector => {
                    const Child = switch (info) {
                        .pointer => |p| p.child,
                        .optional => |o| o.child,
                        .array => |a| a.child,
                        .vector => |v| v.child,
                        else => unreachable,
                    };
                    const child_expression = "(aguafria.zig/field (aguafria.zig/field (aguafria.keyword/typeInfo " ++ expression ++ ") " ++ quoted(@tagName(info)) ++ ") \"child\")";
                    return reachableDeclarationSchema(T, Child, child_expression, visited);
                },
                .error_union => |e| {
                    const payload_expression = "(aguafria.zig/field (aguafria.zig/field (aguafria.keyword/typeInfo " ++ expression ++ ") \"error_union\") \"payload\")";
                    return reachableDeclarationSchema(T, e.payload, payload_expression, visited);
                },
                .@"fn" => |f| {
                    const function_expression = "(aguafria.zig/field (aguafria.keyword/typeInfo " ++ expression ++ ") :fn)";
                    if (f.return_type) |Return| {
                        const result_expression = "(aguafria.zig/unwrap (aguafria.zig/field " ++ function_expression ++ " \"return_type\"))";
                        if (reachableDeclarationSchema(T, Return, result_expression, visited)) |identity|
                            return identity;
                    }
                    inline for (f.param_types, 0..) |parameter, index| {
                        if (parameter) |Parameter| {
                            const parameter_expression = "(aguafria.zig/unwrap (aguafria.zig/index (aguafria.zig/field " ++ function_expression ++ " \"param_types\") " ++ std.fmt.comptimePrint("{d}", .{index}) ++ "))";
                            if (reachableDeclarationSchema(T, Parameter, parameter_expression, visited)) |identity|
                                return identity;
                        }
                    }
                },
                else => {},
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
                .@"struct" => |s| s.decl_names,
                .@"union" => |u| u.decl_names,
                .@"enum" => |e| e.decl_names,
                .@"opaque" => |o| o.decl_names,
                else => return null,
            };
            const relative_name = @typeName(T)[@typeName(Root).len + 1 ..];
            const has_named_member = comptime named: {
                for (members) |member| {
                    if (std.mem.eql(u8, relative_name, member) or
                        std.mem.startsWith(u8, relative_name, member ++ ".")) break :named true;
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
                if (!std.mem.eql(u8, relative_name, member) and
                    !std.mem.startsWith(u8, relative_name, member ++ ".")) continue;
                if (@TypeOf(@field(Root, member)) == type) {
                    const Child = @field(Root, member);
                    const child_expression = "(aguafria.zig/field " ++ expression ++ " " ++ quoted(member) ++ ")";
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
                    var types: [s.field_types.len]type = undefined;
                    var result: []const u8 = "(aguafria.keyword/Tuple (aguafria.keyword/& [";
                    for (s.field_types, s.field_attrs, 0..) |field_type, field_attrs, i| {
                        if (field_attrs.@"comptime") break :tuple "nil";
                        const child = schema(field_type);
                        if (std.mem.eql(u8, child, "nil")) break :tuple "nil";
                        types[i] = field_type;
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
                    const fields = errors.error_names orelse break :errors ":anyerror";
                    var result: []const u8 = "[:error-set [";
                    for (fields) |field| {
                        // Only canonical EDN keywords here; never lose an exact
                        // escaped Zig name by inventing a similar identifier.
                        for (field) |c| {
                            if (!std.ascii.isAlphanumeric(c) and c != '_') break :errors "nil";
                        }
                        if (std.ascii.isDigit(field[0])) break :errors "nil";
                        result = result ++ ":" ++ field ++ " ";
                    }
                    break :errors result ++ "]]";
                },
                .pointer => |p| pointer: {
                    if (p.size == .many and p.sentinel_ptr != null and
                        !p.attrs.@"volatile" and !p.attrs.@"allowzero" and (p.attrs.@"addrspace" == null or p.attrs.@"addrspace" == .generic) and
                        p.attrs.@"align" == null)
                    {
                        const sentinel = switch (@typeInfo(p.child)) {
                            .int => std.fmt.comptimePrint("{d}", .{p.sentinel().?}),
                            .bool => if (p.sentinel().?) "true" else "false",
                            else => break :pointer "nil",
                        };
                        return "[:" ++ (if (p.attrs.@"const") "sentinel-const" else "sentinel") ++ " " ++ schema(p.child) ++ " " ++ sentinel ++ "]";
                    }
                    const qualified = p.sentinel_ptr != null or p.attrs.@"volatile" or (p.attrs.@"allowzero" and p.size != .c) or
                        (p.attrs.@"addrspace" != null and p.attrs.@"addrspace" != .generic) or
                        p.attrs.@"align" != null or
                        (p.size == .c and p.attrs.@"const");
                    if (qualified) {
                        var options: []const u8 = if (p.size == .one) "" else ":size :" ++ @tagName(p.size);
                        if (p.attrs.@"const") options = options ++ " :const? true";
                        if (p.attrs.@"volatile") options = options ++ " :volatile? true";
                        if (p.attrs.@"allowzero" and p.size != .c) options = options ++ " :allowzero? true";
                        if (p.attrs.@"align") |alignment| {
                            options = options ++ std.fmt.comptimePrint(" :align {d}", .{alignment});
                        }
                        if ((p.attrs.@"addrspace" != null and p.attrs.@"addrspace" != .generic))
                            options = options ++ " :addrspace :." ++ @tagName(p.attrs.@"addrspace".?);
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
                        .one => if (p.attrs.@"const") "*const" else "*",
                        .many => if (p.attrs.@"const") "many-const" else "many",
                        .slice => if (p.attrs.@"const") "slice-const" else "slice",
                        .c => "c-pointer",
                    };
                    break :pointer "[:" ++ tag ++ " " ++ schema(p.child) ++ "]";
                },
                // Nominal identity and unsupported qualifiers must not be replaced
                // with a structurally similar type or guessed from a display name.
                else => "nil",
            };
        }

        pub fn storageSchema(comptime T: type) []const u8 {
            if (requiresComptime(T)) return "nil";
            return Inspector(.{}).schema(T);
        }

        pub fn runtimeSchema(comptime T: type) []const u8 {
            if (requiresComptime(T)) return "nil";
            return schema(T);
        }

        pub fn callableSignature(comptime T: type) []const u8 {
            if (@typeInfo(T) != .@"fn") @compileError("Imported declaration is not a function");
            const info = @typeInfo(T).@"fn";
            var result: []const u8 = "{:args [";
            inline for (info.param_types, 0..) |parameter, index| {
                result = result ++ std.fmt.comptimePrint("{{:name argument_{d} :type ", .{index});
                result = result ++ if (parameter) |P| schema(P) else ":anytype";
                // Reflection exposes the concrete types, but not which typed
                // parameters are comptime. Generic aliases retain source literals.
                result = result ++ if (info.is_generic) " :properties {:jvm/literal? true}} " else "} ";
            }
            if (info.attrs.varargs) result = result ++ "{:type :anytype :properties {:zig/variadic true}} ";
            return result ++ "] :return " ++ (if (info.return_type) |R| schema(R) else "nil") ++ "}";
        }
    };
}
