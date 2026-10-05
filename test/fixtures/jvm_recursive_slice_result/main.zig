pub const AnySlice = union(enum) {
    text: []const u8,
    children: []const AnySlice,
};

pub fn leaf() AnySlice {
    return .{ .text = "hello" };
}

pub fn branch() AnySlice {
    return .{ .children = &.{.{ .text = "hello" }} };
}
