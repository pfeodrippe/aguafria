pub fn update(values: *[4]u32) !u32 {
    errdefer {}

    var total: u32 = 0;
    inline for (0..4, 0..) |index, offset| {
        total += values[index] + offset;
    }

    for (values, 0..) |*value, index| {
        value.* += @intCast(index);
    } else {}

    return total;
}

pub fn find(values: []const u32, expected: u32) ?usize {
    return search: for (values, 0..) |value, index| {
        if (value == expected) break :search index;
    } else null;
}

pub fn inlineCount() u8 {
    comptime var count: u8 = 0;
    inline while (count < 4) : (count += 1) {}
    return count;
}
