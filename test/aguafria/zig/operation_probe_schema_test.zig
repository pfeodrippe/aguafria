const std = @import("std");
const Inspector = @import("operation_probe").Inspector;

fn Declaration(comptime T: type, comptime expression: []const u8) type {
    return struct {
        pub fn get() type {
            return T;
        }

        pub fn name() []const u8 {
            return expression;
        }
    };
}

test "comptime aggregate arguments retain their original expression" {
    const Probe = Inspector(.{});
    const Options = struct { base: u8 = 10 };
    try std.testing.expectEqualStrings(
        "{:comptime-expression (object [])}",
        comptime Probe.comptimeArgument(@as(Options, .{}), "(object [])"),
    );
    try std.testing.expectEqualStrings(
        "{:comptime 10}",
        comptime Probe.comptimeArgument(@as(u8, 10), "ignored-scalar-source"),
    );
    try std.testing.expectEqualStrings(
        "nil",
        comptime Probe.comptimeArgument("\xff", "invalid UTF-8 source"),
    );
}

test "structural types do not force unrelated catalog names or types" {
    const Unrelated = struct {
        pub fn get() type {
            @compileError("An unrelated catalog type was evaluated");
        }

        pub fn name() []const u8 {
            @compileError("An unrelated catalog name was evaluated");
        }
    };
    const Probe = Inspector(.{Unrelated});
    try std.testing.expectEqualStrings("[:array 12 :u128]", comptime Probe.schema([12]u128));
}

const Root = struct {
    pub const Nested = struct {
        pub const Tag = enum { first, second };
    };
};

test "nested declarations retain compiler-confirmed nominal identities" {
    const Probe = Inspector(.{Declaration(Root, "fixture/Root")});
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
    const Probe = Inspector(.{Declaration(std.lang.Type, "fixture/Type")});
    try std.testing.expectEqualStrings(
        "(aguafria.zig/field fixture/Type \"Enum\")",
        comptime Probe.schema(std.lang.Type.Enum),
    );
}

test "field types preserve identities outside their owning container namespace" {
    const Child = struct { value: u32 };
    const Owner = struct { item: Child };
    const PointerOwner = struct { item: *Child };
    const Fields = Inspector(.{Declaration(Owner, "fixture/Owner")});
    const Pointers = Inspector(.{Declaration(PointerOwner, "fixture/PointerOwner")});
    try std.testing.expectEqualStrings(
        "(aguafria.keyword/FieldType fixture/Owner \"item\")",
        comptime Fields.schema(Child),
    );
    try std.testing.expectEqualStrings(
        "(aguafria.zig/field (aguafria.zig/field (aguafria.keyword/typeInfo (aguafria.keyword/FieldType fixture/PointerOwner \"item\")) \"pointer\") \"child\")",
        comptime Pointers.schema(Child),
    );
}
