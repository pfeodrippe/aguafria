//! Serialize Zig's compiler-owned syntax tree as compact EDN.
//!
//! Aguafria delegates tokenization and parsing to the selected Zig compiler.

const std = @import("std");
const Ast = std.zig.Ast;
const Io = std.Io;

fn nodeInt(node: Ast.Node.Index) u32 {
    return @intFromEnum(node);
}

fn optNodeInt(node: Ast.Node.OptionalIndex) ?u32 {
    return if (node.unwrap()) |value| nodeInt(value) else null;
}

fn writeOptional(writer: *Io.Writer, value: ?u32) !void {
    if (value) |number| {
        try writer.print("{}", .{number});
    } else {
        try writer.writeAll("nil");
    }
}

fn writeNodeIndices(writer: *Io.Writer, nodes: []const Ast.Node.Index) !void {
    try writer.writeByte('[');
    for (nodes, 0..) |node, index| {
        if (index != 0) try writer.writeByte(' ');
        try writer.print("{}", .{nodeInt(node)});
    }
    try writer.writeByte(']');
}

fn writeTokens(tree: *const Ast, writer: *Io.Writer) !void {
    try writer.writeAll(":tokens [");
    for (0..tree.tokens.len) |raw_index| {
        const index: Ast.TokenIndex = @intCast(raw_index);
        if (raw_index != 0) try writer.writeByte(' ');
        try writer.print("[:{s} {} {}]", .{
            @tagName(tree.tokenTag(index)),
            tree.tokenStart(index),
            tree.tokenSlice(index).len,
        });
    }
    try writer.writeAll("]\n");
}

fn writeNodeData(tree: *const Ast, node: Ast.Node.Index, writer: *Io.Writer) !void {
    switch (tree.nodeTag(node)) {
        .@"catch",
        .equal_equal,
        .bang_equal,
        .less_than,
        .greater_than,
        .less_or_equal,
        .greater_or_equal,
        .assign_mul,
        .assign_div,
        .assign_mod,
        .assign_add,
        .assign_sub,
        .assign_shl,
        .assign_shl_sat,
        .assign_shr,
        .assign_bit_and,
        .assign_bit_xor,
        .assign_bit_or,
        .assign_mul_wrap,
        .assign_add_wrap,
        .assign_sub_wrap,
        .assign_mul_sat,
        .assign_add_sat,
        .assign_sub_sat,
        .assign,
        .merge_error_sets,
        .mul,
        .div,
        .mod,
        .array_mult,
        .mul_wrap,
        .mul_sat,
        .add,
        .sub,
        .array_cat,
        .add_wrap,
        .sub_wrap,
        .add_sat,
        .sub_sat,
        .shl,
        .shl_sat,
        .shr,
        .bit_and,
        .bit_xor,
        .bit_or,
        .@"orelse",
        .bool_and,
        .bool_or,
        .array_access,
        .switch_range,
        .error_union,
        => {
            const data = tree.nodeData(node).node_and_node;
            try writer.print(":node-node {} {}", .{ nodeInt(data[0]), nodeInt(data[1]) });
        },

        .for_range => {
            const data = tree.nodeData(node).node_and_opt_node;
            try writer.print(":node-node {} ", .{nodeInt(data[0])});
            try writeOptional(writer, optNodeInt(data[1]));
        },

        .field_access, .unwrap_optional, .grouped_expression => {
            const data = tree.nodeData(node).node_and_token;
            try writer.print(":node-token {} {}", .{ nodeInt(data[0]), data[1] });
        },

        .asm_input => {
            const data = tree.nodeData(node).node_and_token;
            try writer.print(":node-token {} {}", .{ nodeInt(data[0]), data[1] });
        },

        .asm_output => {
            const data = tree.nodeData(node).opt_node_and_token;
            try writer.writeAll(":opt-node-token ");
            try writeOptional(writer, optNodeInt(data[0]));
            try writer.print(" {}", .{data[1]});
        },

        .bool_not,
        .negation,
        .bit_not,
        .negation_wrap,
        .address_of,
        .@"try",
        .optional_type,
        .deref,
        .@"defer",
        .@"suspend",
        .@"resume",
        .@"comptime",
        .@"nosuspend",
        => try writer.print(":node {} nil", .{nodeInt(tree.nodeData(node).node)}),

        .@"return" => {
            try writer.writeAll(":opt-node ");
            try writeOptional(writer, optNodeInt(tree.nodeData(node).opt_node));
            try writer.writeAll(" nil");
        },

        else => try writer.writeAll(":none nil nil"),
    }
}

fn writeNodes(tree: *const Ast, writer: *Io.Writer) !void {
    try writer.writeAll(":nodes [");
    for (0..tree.nodes.len) |raw_index| {
        const index: Ast.Node.Index = @enumFromInt(raw_index);
        if (raw_index != 0) try writer.writeByte(' ');
        try writer.print("[:{s} {} {} {} ", .{
            @tagName(tree.nodeTag(index)),
            tree.nodeMainToken(index),
            tree.firstToken(index),
            tree.lastToken(index),
        });
        try writeNodeData(tree, index, writer);
        try writer.writeByte(']');
    }
    try writer.writeAll("]\n");
}

fn writeExtra(tree: *const Ast, writer: *Io.Writer) !void {
    try writer.writeAll(":extra [");
    for (tree.extra_data, 0..) |value, index| {
        if (index != 0) try writer.writeByte(' ');
        try writer.print("{}", .{value});
    }
    try writer.writeAll("]\n");
}

fn writeErrors(tree: *const Ast, writer: *Io.Writer) !void {
    try writer.writeAll(":errors [");
    for (tree.errors, 0..) |parse_error, index| {
        if (index != 0) try writer.writeByte(' ');
        const location = tree.tokenLocation(0, parse_error.token);
        try writer.print("[:{s} {} {} {} {}]", .{
            @tagName(parse_error.tag),
            parse_error.token,
            location.line + 1,
            location.column + 1,
            parse_error.token_is_prev,
        });
    }
    try writer.writeAll("]\n");
}

fn writeFunctions(tree: *const Ast, writer: *Io.Writer) !void {
    try writer.writeAll(":functions [");
    var first = true;
    for (0..tree.nodes.len) |raw_index| {
        const decl: Ast.Node.Index = @enumFromInt(raw_index);
        if (tree.nodeTag(decl) != .fn_decl) continue;
        const data = tree.nodeData(decl).node_and_node;
        var buffer: [1]Ast.Node.Index = undefined;
        const proto = tree.fullFnProto(&buffer, decl).?;
        if (!first) try writer.writeByte(' ');
        first = false;
        try writer.print("[{} {} {} ", .{ nodeInt(decl), nodeInt(data[0]), nodeInt(data[1]) });
        try writeOptional(writer, proto.name_token);
        try writer.writeByte(' ');
        try writeOptional(writer, optNodeInt(proto.ast.return_type));
        try writer.print(" {} ", .{proto.lparen});
        try writeOptional(writer, proto.visib_token);
        try writer.writeByte(' ');
        try writeOptional(writer, proto.extern_export_inline_token);
        try writer.writeByte(' ');
        try writeOptional(writer, proto.lib_name);
        try writer.writeByte(' ');
        try writeOptional(writer, optNodeInt(proto.ast.align_expr));
        try writer.writeByte(' ');
        try writeOptional(writer, optNodeInt(proto.ast.addrspace_expr));
        try writer.writeByte(' ');
        try writeOptional(writer, optNodeInt(proto.ast.section_expr));
        try writer.writeByte(' ');
        try writeOptional(writer, optNodeInt(proto.ast.callconv_expr));
        try writer.writeAll(" [");
        var iterator = proto.iterate(tree);
        var param_index: usize = 0;
        while (iterator.next()) |param| : (param_index += 1) {
            if (param_index != 0) try writer.writeByte(' ');
            try writer.writeByte('[');
            try writeOptional(writer, param.name_token);
            try writer.writeByte(' ');
            try writeOptional(writer, if (param.type_expr) |node| nodeInt(node) else null);
            try writer.writeByte(' ');
            try writeOptional(writer, param.comptime_noalias);
            try writer.writeByte(' ');
            try writeOptional(writer, param.anytype_ellipsis3);
            try writer.writeByte(']');
        }
        try writer.writeAll("]]" );
    }
    try writer.writeAll("]\n");
}

fn writeFunctionPrototypes(tree: *const Ast, writer: *Io.Writer) !void {
    try writer.writeAll(":function-prototypes [");
    var first = true;
    for (0..tree.nodes.len) |raw_index| {
        const decl: Ast.Node.Index = @enumFromInt(raw_index);
        if (tree.nodeTag(decl) == .fn_decl) continue;
        var buffer: [1]Ast.Node.Index = undefined;
        const proto = tree.fullFnProto(&buffer, decl) orelse continue;
        if (!first) try writer.writeByte(' ');
        first = false;
        try writer.print("[{} ", .{nodeInt(decl)});
        try writeOptional(writer, proto.name_token);
        try writer.writeByte(' ');
        try writeOptional(writer, optNodeInt(proto.ast.return_type));
        try writer.print(" {} ", .{proto.lparen});
        try writeOptional(writer, proto.visib_token);
        try writer.writeByte(' ');
        try writeOptional(writer, proto.extern_export_inline_token);
        try writer.writeByte(' ');
        try writeOptional(writer, proto.lib_name);
        try writer.writeByte(' ');
        try writeOptional(writer, optNodeInt(proto.ast.align_expr));
        try writer.writeByte(' ');
        try writeOptional(writer, optNodeInt(proto.ast.addrspace_expr));
        try writer.writeByte(' ');
        try writeOptional(writer, optNodeInt(proto.ast.section_expr));
        try writer.writeByte(' ');
        try writeOptional(writer, optNodeInt(proto.ast.callconv_expr));
        try writer.writeAll(" [");
        var iterator = proto.iterate(tree);
        var param_index: usize = 0;
        while (iterator.next()) |param| : (param_index += 1) {
            if (param_index != 0) try writer.writeByte(' ');
            try writer.writeByte('[');
            try writeOptional(writer, param.name_token);
            try writer.writeByte(' ');
            try writeOptional(writer, if (param.type_expr) |node| nodeInt(node) else null);
            try writer.writeByte(' ');
            try writeOptional(writer, param.comptime_noalias);
            try writer.writeByte(' ');
            try writeOptional(writer, param.anytype_ellipsis3);
            try writer.writeByte(']');
        }
        try writer.writeAll("]]" );
    }
    try writer.writeAll("]\n");
}

fn writeTests(tree: *const Ast, writer: *Io.Writer) !void {
    try writer.writeAll(":tests [");
    var first = true;
    for (0..tree.nodes.len) |raw_index| {
        const decl: Ast.Node.Index = @enumFromInt(raw_index);
        if (tree.nodeTag(decl) != .test_decl) continue;
        const data = tree.nodeData(decl).opt_token_and_node;
        if (!first) try writer.writeByte(' ');
        first = false;
        try writer.print("[{} ", .{nodeInt(decl)});
        try writeOptional(writer, data[0].unwrap());
        try writer.print(" {}]", .{nodeInt(data[1])});
    }
    try writer.writeAll("]\n");
}

fn writeVarDecls(tree: *const Ast, writer: *Io.Writer) !void {
    try writer.writeAll(":var-decls [");
    var first = true;
    for (0..tree.nodes.len) |raw_index| {
        const node: Ast.Node.Index = @enumFromInt(raw_index);
        const decl = tree.fullVarDecl(node) orelse continue;
        if (!first) try writer.writeByte(' ');
        first = false;
        try writer.print("[{} ", .{raw_index});
        for ([_]?u32{
            decl.visib_token,
            decl.extern_export_token,
            decl.lib_name,
            decl.threadlocal_token,
            decl.comptime_token,
        }, 0..) |value, index| {
            if (index != 0) try writer.writeByte(' ');
            try writeOptional(writer, value);
        }
        try writer.print(" {} ", .{decl.ast.mut_token});
        for ([_]?u32{
            optNodeInt(decl.ast.type_node),
            optNodeInt(decl.ast.align_node),
            optNodeInt(decl.ast.addrspace_node),
            optNodeInt(decl.ast.section_node),
            optNodeInt(decl.ast.init_node),
        }, 0..) |value, index| {
            if (index != 0) try writer.writeByte(' ');
            try writeOptional(writer, value);
        }
        try writer.writeByte(']');
    }
    try writer.writeAll("]\n");
}

fn writeAssignDestructures(tree: *const Ast, writer: *Io.Writer) !void {
    try writer.writeAll(":assign-destructures [");
    var first = true;
    for (0..tree.nodes.len) |raw_index| {
        const node: Ast.Node.Index = @enumFromInt(raw_index);
        if (tree.nodeTag(node) != .assign_destructure) continue;
        const full = tree.assignDestructure(node);
        if (!first) try writer.writeByte(' ');
        first = false;
        try writer.print("[{} ", .{raw_index});
        try writeNodeIndices(writer, full.ast.variables);
        try writer.print(" {} ", .{nodeInt(full.ast.value_expr)});
        try writeOptional(writer, full.comptime_token);
        try writer.writeByte(']');
    }
    try writer.writeAll("]\n");
}

fn writeSemanticNodes(tree: *const Ast, writer: *Io.Writer) !void {
    try writer.writeAll(":blocks [");
    var first = true;
    for (0..tree.nodes.len) |raw_index| {
        const node: Ast.Node.Index = @enumFromInt(raw_index);
        var buffer: [2]Ast.Node.Index = undefined;
        const statements = tree.blockStatements(&buffer, node) orelse continue;
        if (!first) try writer.writeByte(' ');
        first = false;
        try writer.print("[{} ", .{raw_index});
        try writeNodeIndices(writer, statements);
        try writer.writeByte(']');
    }
    try writer.writeAll("]\n:calls [");
    first = true;
    for (0..tree.nodes.len) |raw_index| {
        const node: Ast.Node.Index = @enumFromInt(raw_index);
        var buffer: [1]Ast.Node.Index = undefined;
        const call = tree.fullCall(&buffer, node) orelse continue;
        if (!first) try writer.writeByte(' ');
        first = false;
        try writer.print("[{} {} ", .{ raw_index, nodeInt(call.ast.fn_expr) });
        try writeNodeIndices(writer, call.ast.params);
        try writer.writeByte(']');
    }
    try writer.writeAll("]\n:builtins [");
    first = true;
    for (0..tree.nodes.len) |raw_index| {
        const node: Ast.Node.Index = @enumFromInt(raw_index);
        var buffer: [2]Ast.Node.Index = undefined;
        const params = tree.builtinCallParams(&buffer, node) orelse continue;
        if (!first) try writer.writeByte(' ');
        first = false;
        try writer.print("[{} ", .{raw_index});
        try writeNodeIndices(writer, params);
        try writer.writeByte(']');
    }
    try writer.writeAll("]\n:array-inits [");
    first = true;
    for (0..tree.nodes.len) |raw_index| {
        const node: Ast.Node.Index = @enumFromInt(raw_index);
        var buffer: [2]Ast.Node.Index = undefined;
        const init = tree.fullArrayInit(&buffer, node) orelse continue;
        if (!first) try writer.writeByte(' ');
        first = false;
        try writer.print("[{} ", .{raw_index});
        try writeOptional(writer, optNodeInt(init.ast.type_expr));
        try writer.writeByte(' ');
        try writeNodeIndices(writer, init.ast.elements);
        try writer.writeByte(']');
    }
    try writer.writeAll("]\n:struct-inits [");
    first = true;
    for (0..tree.nodes.len) |raw_index| {
        const node: Ast.Node.Index = @enumFromInt(raw_index);
        var buffer: [2]Ast.Node.Index = undefined;
        const init = tree.fullStructInit(&buffer, node) orelse continue;
        if (!first) try writer.writeByte(' ');
        first = false;
        try writer.print("[{} ", .{raw_index});
        try writeOptional(writer, optNodeInt(init.ast.type_expr));
        try writer.writeByte(' ');
        try writeNodeIndices(writer, init.ast.fields);
        try writer.writeByte(']');
    }
    try writer.writeAll("]\n");
}

fn writeControlNodes(tree: *const Ast, writer: *Io.Writer) !void {
    try writer.writeAll(":ifs [");
    var first = true;
    for (0..tree.nodes.len) |raw_index| {
        const node: Ast.Node.Index = @enumFromInt(raw_index);
        const full = tree.fullIf(node) orelse continue;
        if (!first) try writer.writeByte(' ');
        first = false;
        try writer.print("[{} {} {} ", .{
            raw_index,
            nodeInt(full.ast.cond_expr),
            nodeInt(full.ast.then_expr),
        });
        try writeOptional(writer, optNodeInt(full.ast.else_expr));
        try writer.writeByte(' ');
        try writeOptional(writer, full.payload_token);
        try writer.writeByte(' ');
        try writeOptional(writer, full.error_token);
        try writer.writeByte(']');
    }
    try writer.writeAll("]\n:whiles [");
    first = true;
    for (0..tree.nodes.len) |raw_index| {
        const node: Ast.Node.Index = @enumFromInt(raw_index);
        const full = tree.fullWhile(node) orelse continue;
        if (!first) try writer.writeByte(' ');
        first = false;
        try writer.print("[{} {} ", .{ raw_index, nodeInt(full.ast.cond_expr) });
        try writeOptional(writer, optNodeInt(full.ast.cont_expr));
        try writer.print(" {} ", .{nodeInt(full.ast.then_expr)});
        try writeOptional(writer, optNodeInt(full.ast.else_expr));
        try writer.writeByte(' ');
        try writeOptional(writer, full.payload_token);
        try writer.writeByte(' ');
        try writeOptional(writer, full.error_token);
        try writer.writeByte(']');
    }
    try writer.writeAll("]\n:fors [");
    first = true;
    for (0..tree.nodes.len) |raw_index| {
        const node: Ast.Node.Index = @enumFromInt(raw_index);
        const full = tree.fullFor(node) orelse continue;
        if (!first) try writer.writeByte(' ');
        first = false;
        try writer.print("[{} ", .{raw_index});
        try writeNodeIndices(writer, full.ast.inputs);
        try writer.print(" {} ", .{nodeInt(full.ast.then_expr)});
        try writeOptional(writer, optNodeInt(full.ast.else_expr));
        try writer.writeByte(' ');
        try writeOptional(writer, full.payload_token);
        try writer.writeByte(']');
    }
    try writer.writeAll("]\n:switches [");
    first = true;
    for (0..tree.nodes.len) |raw_index| {
        const node: Ast.Node.Index = @enumFromInt(raw_index);
        const full = tree.fullSwitch(node) orelse continue;
        if (!first) try writer.writeByte(' ');
        first = false;
        try writer.print("[{} {} ", .{ raw_index, nodeInt(full.ast.condition) });
        try writeNodeIndices(writer, full.ast.cases);
        try writer.writeByte(' ');
        try writeOptional(writer, full.label_token);
        try writer.writeByte(']');
    }
    try writer.writeAll("]\n:switch-cases [");
    first = true;
    for (0..tree.nodes.len) |raw_index| {
        const node: Ast.Node.Index = @enumFromInt(raw_index);
        const full = tree.fullSwitchCase(node) orelse continue;
        if (!first) try writer.writeByte(' ');
        first = false;
        try writer.print("[{} ", .{raw_index});
        try writeNodeIndices(writer, full.ast.values);
        try writer.print(" {} ", .{nodeInt(full.ast.target_expr)});
        try writeOptional(writer, full.payload_token);
        try writer.writeByte(' ');
        try writeOptional(writer, full.inline_token);
        try writer.writeByte(']');
    }
    try writer.writeAll("]\n");
}

fn writeAsmNodes(tree: *const Ast, writer: *Io.Writer) !void {
    try writer.writeAll(":asms [");
    var first = true;
    for (0..tree.nodes.len) |raw_index| {
        const node: Ast.Node.Index = @enumFromInt(raw_index);
        const full = tree.fullAsm(node) orelse continue;
        if (!first) try writer.writeByte(' ');
        first = false;
        try writer.print("[{} {} ", .{
            raw_index,
            nodeInt(full.ast.template),
        });
        try writeOptional(writer, full.volatile_token);
        try writer.writeByte(' ');
        try writeNodeIndices(writer, full.outputs);
        try writer.writeByte(' ');
        try writeNodeIndices(writer, full.inputs);
        try writer.writeByte(' ');
        try writeOptional(writer, optNodeInt(full.ast.clobbers));
        try writer.print(" {}]", .{full.ast.rparen});
    }
    try writer.writeAll("]\n");
}

fn writeTypeNodes(tree: *const Ast, writer: *Io.Writer) !void {
    try writer.writeAll(":array-types [");
    var first = true;
    for (0..tree.nodes.len) |raw_index| {
        const node: Ast.Node.Index = @enumFromInt(raw_index);
        const full = tree.fullArrayType(node) orelse continue;
        if (!first) try writer.writeByte(' ');
        first = false;
        try writer.print("[{} {} ", .{ raw_index, nodeInt(full.ast.elem_count) });
        try writeOptional(writer, optNodeInt(full.ast.sentinel));
        try writer.print(" {}]", .{nodeInt(full.ast.elem_type)});
    }
    try writer.writeAll("]\n:ptr-types [");
    first = true;
    for (0..tree.nodes.len) |raw_index| {
        const node: Ast.Node.Index = @enumFromInt(raw_index);
        const full = tree.fullPtrType(node) orelse continue;
        if (!first) try writer.writeByte(' ');
        first = false;
        try writer.print("[{} :{s} ", .{ raw_index, @tagName(full.size) });
        for ([_]?u32{
            full.allowzero_token,
            full.const_token,
            full.volatile_token,
            optNodeInt(full.ast.align_node),
            optNodeInt(full.ast.addrspace_node),
            optNodeInt(full.ast.sentinel),
            optNodeInt(full.ast.bit_range_start),
            optNodeInt(full.ast.bit_range_end),
        }, 0..) |value, index| {
            if (index != 0) try writer.writeByte(' ');
            try writeOptional(writer, value);
        }
        try writer.print(" {}]", .{nodeInt(full.ast.child_type)});
    }
    try writer.writeAll("]\n:slices [");
    first = true;
    for (0..tree.nodes.len) |raw_index| {
        const node: Ast.Node.Index = @enumFromInt(raw_index);
        const full = tree.fullSlice(node) orelse continue;
        if (!first) try writer.writeByte(' ');
        first = false;
        try writer.print("[{} {} {} ", .{
            raw_index,
            nodeInt(full.ast.sliced),
            nodeInt(full.ast.start),
        });
        try writeOptional(writer, optNodeInt(full.ast.end));
        try writer.writeByte(' ');
        try writeOptional(writer, optNodeInt(full.ast.sentinel));
        try writer.writeByte(']');
    }
    try writer.writeAll("]\n");
}

fn writeContainerNodes(tree: *const Ast, writer: *Io.Writer) !void {
    try writer.writeAll(":containers [");
    var first = true;
    for (0..tree.nodes.len) |raw_index| {
        const node: Ast.Node.Index = @enumFromInt(raw_index);
        var buffer: [2]Ast.Node.Index = undefined;
        const full = tree.fullContainerDecl(&buffer, node) orelse continue;
        if (!first) try writer.writeByte(' ');
        first = false;
        try writer.print("[{} ", .{raw_index});
        try writeOptional(writer, full.layout_token);
        try writer.print(" {} ", .{full.ast.main_token});
        try writeOptional(writer, full.ast.enum_token);
        try writer.writeByte(' ');
        try writeNodeIndices(writer, full.ast.members);
        try writer.writeByte(' ');
        try writeOptional(writer, optNodeInt(full.ast.arg));
        try writer.writeByte(']');
    }
    try writer.writeAll("]\n:container-fields [");
    first = true;
    for (0..tree.nodes.len) |raw_index| {
        const node: Ast.Node.Index = @enumFromInt(raw_index);
        const full = tree.fullContainerField(node) orelse continue;
        if (!first) try writer.writeByte(' ');
        first = false;
        try writer.print("[{} ", .{raw_index});
        try writeOptional(writer, full.comptime_token);
        try writer.print(" {} ", .{full.ast.main_token});
        try writeOptional(writer, optNodeInt(full.ast.type_expr));
        try writer.writeByte(' ');
        try writeOptional(writer, optNodeInt(full.ast.align_expr));
        try writer.writeByte(' ');
        try writeOptional(writer, optNodeInt(full.ast.value_expr));
        try writer.print(" {}]", .{full.ast.tuple_like});
    }
    try writer.writeAll("]\n");
}

pub fn main(init: std.process.Init) !void {
    const gpa = init.gpa;
    const arena = init.arena.allocator();
    const args = try init.minimal.args.toSlice(arena);
    if (args.len != 2) {
        std.debug.print("usage: zig-ast <source.zig>\n", .{});
        return error.InvalidArguments;
    }

    const source_bytes = try Io.Dir.cwd().readFileAlloc(
        init.io,
        args[1],
        arena,
        .limited(1024 * 1024 * 1024),
    );
    const source = try arena.dupeZ(u8, source_bytes);
    var tree = try Ast.parse(gpa, source, .zig);
    defer tree.deinit(gpa);

    var stdout_buffer: [64 * 1024]u8 = undefined;
    var stdout_writer = Io.File.stdout().writer(init.io, &stdout_buffer);
    const writer = &stdout_writer.interface;
    try writer.writeAll("{:schema-version 4\n");
    try writeErrors(&tree, writer);
    try writeTokens(&tree, writer);
    try writeNodes(&tree, writer);
    try writeExtra(&tree, writer);
    try writer.writeAll(":root-decls ");
    try writeNodeIndices(writer, tree.rootDecls());
    try writer.writeByte('\n');
    try writeFunctions(&tree, writer);
    try writeFunctionPrototypes(&tree, writer);
    try writeTests(&tree, writer);
    try writeVarDecls(&tree, writer);
    try writeAssignDestructures(&tree, writer);
    try writeSemanticNodes(&tree, writer);
    try writeControlNodes(&tree, writer);
    try writeAsmNodes(&tree, writer);
    try writeTypeNodes(&tree, writer);
    try writeContainerNodes(&tree, writer);
    try writer.writeAll("}\n");
    try writer.flush();
}
