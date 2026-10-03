const std = @import("std");

const Example = struct {
    fn local() u32 {
        const k = 7;
        return k;
    }

    fn captured(value: ?u32) u32 {
        while (value) |k| {
            return k;
        }
        return 0;
    }
};

test "locals and captures may match the generated namespace alias" {
    try std.testing.expectEqual(7, Example.local());
    try std.testing.expectEqual(9, Example.captured(9));
    try std.testing.expectEqual(0, Example.captured(null));
}
