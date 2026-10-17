// Diagnostic only: an explicitly owned allocator lifecycle, NOT a replacement
// for Learn's editor workload and NOT an implicit std.testing lifecycle.
const std = @import("std");

fn workload(deinit_list: bool) !usize {
    // This is the actual allocator type of pinned std.testing.allocator_instance.
    var owner: std.heap.SafeAllocator = .init(std.heap.page_allocator, .{});
    errdefer _ = owner.deinitLog(false);
    const gpa = owner.allocator();
    var list: std.ArrayList(u21) = .empty;
    try list.append(gpa, '☔');
    try std.testing.expectEqual(@as(usize, 1), list.items.len);
    if (deinit_list) list.deinit(gpa);
    // Deinit consumes the allocator and returns a count; false suppresses logs
    // so the diagnostic's deliberately outstanding allocation is not itself
    // mistaken for a failure of the outer Zig test runner.
    return owner.deinitLog(false);
}

test "completed body and a positive deinit leak count are compatible" {
    try std.testing.expectEqual(@as(usize, 1), try workload(false));
}

test "explicit list cleanup yields zero deinit leaks" {
    try std.testing.expectEqual(@as(usize, 0), try workload(true));
}
