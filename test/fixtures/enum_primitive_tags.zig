const std = @import("std");

fn isVoid(comptime T: type) bool {
    return @typeInfo(T) == .void;
}

comptime {
    std.debug.assert(isVoid(void));
    std.debug.assert(!isVoid(u32));
}

pub fn check() bool {
    const Tag = enum { bool, void, u32 };
    const tag: Tag = .void;
    return tag == .void;
}
