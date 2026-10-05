const std = @import("std");

fn find(items: []const u8, wanted: u8) u8 {
    return for (items) |item| {
        if (item == wanted) break item;
    } else result: {
        const fallback: u8 = 7;
        break :result fallback;
    };
}

test "for else local scope" {
    try std.testing.expectEqual(3, find(&.{ 1, 2, 3 }, 3));
    try std.testing.expectEqual(7, find(&.{ 1, 2, 3 }, 4));
}

fn notify(items: []const u8, wanted: u8, result: *u8) void {
    for (items) |item| {
        if (item == wanted) break;
    } else {
        const fallback: u8 = 7;
        result.* = fallback;
    }
}

test "for else statement scope" {
    var result: u8 = 0;
    notify(&.{ 1, 2, 3 }, 4, &result);
    try std.testing.expectEqual(7, result);
}
