const std = @import("std");

test "for expression retains labeled else block" {
    const values = [_]u32{ 2, 3 };
    var sum: u32 = 0;
    const result = for (values) |value| {
        sum += value;
    } else done: {
        try std.testing.expectEqual(5, sum);
        break :done sum;
    };
    try std.testing.expectEqual(5, result);
}

test "while expression retains labeled else block" {
    var count: u32 = 0;
    const result = while (count < 3) : (count += 1) {
    } else done: {
        try std.testing.expectEqual(3, count);
        break :done count;
    };
    try std.testing.expectEqual(3, result);
}
