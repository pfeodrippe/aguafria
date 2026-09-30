pub fn compare(expected: anytype, actual: anytype) !void {
    const T = @TypeOf(expected, actual);
    return compareTyped(T, expected, actual);
}

fn compareTyped(comptime T: type, expected: T, actual: T) !void {
    if (expected != actual) return error.Different;
}
