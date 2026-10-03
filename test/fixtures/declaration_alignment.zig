const std = @import("std");

pub var foo: u8 align(4) = 100;
pub const bar: u8 align(8) = 42;

pub fn derp() align(@sizeOf(usize) * 2) i32 {
    return 1234;
}

pub fn noop() align(4) void {}
extern fn external() align(8) callconv(.c) void;

test "declaration alignment" {
    try std.testing.expectEqual(4, @typeInfo(@TypeOf(&foo)).pointer.attrs.@"align");
    try std.testing.expectEqual(8, @typeInfo(@TypeOf(&bar)).pointer.attrs.@"align");
    try std.testing.expectEqual(@sizeOf(usize) * 2, @typeInfo(@TypeOf(&derp)).pointer.attrs.@"align");
    try std.testing.expectEqual(4, @typeInfo(@TypeOf(&noop)).pointer.attrs.@"align");
    try std.testing.expectEqual(1234, derp());
}
