const probe = @import("operation_probe");

const First = struct { value: u32 };
const Second = struct { value: u32 };
const Node = struct { next: ?*@This(), value: u32 };
const Fields = struct { first: First, second: Second, node: *Node };
const Choice = union { first: First, second: Second };
const Packed = packed struct(u64) { a: u32, b: u32 };

// Real discovery emits these as top-level const queries, not runtime reads.
const FirstField = probe.fieldValueType(*Fields, "first");
const SecondField = probe.fieldValueType(*const volatile Fields, "second");
const NodeField = probe.fieldValueType(*Fields, "node");
const NextField = probe.fieldValueType(NodeField, "next");
const UnionField = probe.fieldValueType(*Choice, "second");
const PackedField = probe.fieldValueType(*align(4) Packed, "b");

fn directArrayLengthType() type {
    var value: [3]u8 = undefined;
    _ = &value;
    return @TypeOf(value.len);
}

fn directArrayPointerLengthType() type {
    var value: *[3]u8 = undefined;
    _ = &value;
    return @TypeOf(value.len);
}

comptime {
    if (FirstField != First or SecondField != Second or FirstField == SecondField)
        @compileError("field reflection lost nominal identity");
    if (NodeField != *Node or NextField != ?*Node or UnionField != Second)
        @compileError("recursive or union field reflection changed type");
    if (PackedField != u32)
        @compileError("aligned packed pointer field reflection changed type");
    if (probe.fieldValueType([]const u8, "ptr") != [*]const u8 or
        probe.fieldValueType([]const u8, "len") != usize)
        @compileError("slice field reflection changed type");
    // Comptime-known length does not itself establish comptime_int typing.
    if (probe.fieldValueType([3]u8, "len") != directArrayLengthType() or
        probe.fieldValueType(*[3]u8, "len") != directArrayPointerLengthType())
        @compileError("array length reflection differs from direct native property typing");
}

comptime {
    const known = probe.Inspector(.{});
    const std = @import("std");
    for (.{ [3]u8, [3:0]u8, *[3]u8, *const volatile [3]u8 }) |T| {
        if (!known.hasFixedArrayLength(T))
            @compileError("fixed array length provenance missing");
        if (!std.mem.eql(u8, known.fixedArrayLengthArgument(T), "{:comptime-expression (aguafria.keyword/as 3 :usize)}"))
            @compileError("fixed length lost its native value or usize type");
    }
    if (!std.mem.eql(u8, known.fixedArrayLengthArgument([0]u8), "{:comptime-expression (aguafria.keyword/as 0 :usize)}"))
        @compileError("zero-length provenance missing");
    const UserLength = struct { len: usize };
    for (.{ []u8, *const []u8, UserLength, *UserLength, [*]u8 }) |T| {
        if (known.hasFixedArrayLength(T))
            @compileError("runtime length was incorrectly made comptime");
    }
}

test "reflection is checked without runtime execution" {
    @panic("compile-only reflection fixture must not execute a test body");
}
