pub const Bytes = []const u8;
pub const BytesAlias = Bytes;

pub const Row = struct {
    /// The row's stored number.
    value: u32,
    pub const Slice = []const Row;
};
pub const RowAlias = Row;

pub fn Buffer(comptime T: type) type {
    return struct {
        pub const Slice = []const T;
        items: Slice,
    };
}

pub fn bytes() Bytes {
    return "abc";
}

pub fn row() Row {
    return .{ .value = 42 };
}

pub fn buffer() Buffer(u8) {
    return .{ .items = "abc" };
}
