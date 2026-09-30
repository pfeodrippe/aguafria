const std = @import("std");
const Inspector = @import("operation_probe").Inspector;

const Root = struct {
    pub const Nested = struct {
        pub const Tag = enum { first, second };
    };
};

test "nested declarations retain compiler-confirmed nominal identities" {
    const Probe = Inspector(.{.{ Root, "fixture/Root" }});
    try std.testing.expectEqualStrings(
        "(aguafria.zig/field fixture/Root \"Nested\")",
        comptime Probe.schema(Root.Nested),
    );
    try std.testing.expectEqualStrings(
        "(aguafria.zig/field (aguafria.zig/field fixture/Root \"Nested\") \"Tag\")",
        comptime Probe.schema(Root.Nested.Tag),
    );
    try std.testing.expectEqualStrings("nil", comptime Probe.schema(struct {}));
}

test "reflected std types use their exact declaration rather than a structural substitute" {
    const Probe = Inspector(.{.{ std.builtin.Type, "fixture/Type" }});
    try std.testing.expectEqualStrings(
        "(aguafria.zig/field fixture/Type \"Enum\")",
        comptime Probe.schema(std.builtin.Type.Enum),
    );
}

test "field types preserve identities outside their owning container namespace" {
    const Child = struct { value: u32 };
    const Owner = struct { item: Child };
    const PointerOwner = struct { item: *Child };
    const Fields = Inspector(.{.{ Owner, "fixture/Owner" }});
    const Pointers = Inspector(.{.{ PointerOwner, "fixture/PointerOwner" }});
    try std.testing.expectEqualStrings(
        "(aguafria.keyword/FieldType fixture/Owner \"item\")",
        comptime Fields.schema(Child),
    );
    try std.testing.expectEqualStrings(
        "(aguafria.zig/field (aguafria.zig/field (aguafria.keyword/typeInfo (aguafria.keyword/FieldType fixture/PointerOwner \"item\")) \"pointer\") \"child\")",
        comptime Pointers.schema(Child),
    );
}
