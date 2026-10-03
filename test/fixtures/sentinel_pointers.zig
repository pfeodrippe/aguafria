const std = @import("std");

test "null sentinel slices coerce to null sentinel pointers" {
    const items = [_:null]?*const u8{null};
    const slice: [:null]const ?*const u8 = &items;
    const pointer: [*:null]align(8) const ?*const u8 = slice;
    try std.testing.expectEqual(null, slice[0]);
    try std.testing.expectEqual(null, pointer[1]);
}
