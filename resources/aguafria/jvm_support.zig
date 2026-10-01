// Shared, non-generic storage and encoding support for JVM adapters.
// This ABI is private to a content-addressed compiler/support generation.
const std = @import("std");
const allocator = std.heap.page_allocator;

export fn aguafria_jvm_allocate(size: usize, alignment: usize) ?[*]u8 {
    return allocator.rawAlloc(@max(1, size), .fromByteUnits(alignment), @returnAddress());
}

export fn aguafria_jvm_writer_new() ?*anyopaque {
    const writer = allocator.create(std.Io.Writer.Allocating) catch return null;
    writer.* = .init(allocator);
    return writer;
}

export fn aguafria_jvm_writer_append(handle: *anyopaque, bytes: [*]const u8, length: usize) bool {
    const allocating: *std.Io.Writer.Allocating = @ptrCast(@alignCast(handle));
    allocating.writer.writeAll(bytes[0..length]) catch return false;
    return true;
}

export fn aguafria_jvm_writer_destroy(handle: *anyopaque) void {
    const allocating: *std.Io.Writer.Allocating = @ptrCast(@alignCast(handle));
    allocating.deinit();
    allocator.destroy(allocating);
}

export fn aguafria_jvm_writer_finish(handle: *anyopaque) usize {
    const allocating: *std.Io.Writer.Allocating = @ptrCast(@alignCast(handle));
    const encoded = allocator.dupeZ(u8, allocating.written()) catch return 0;
    return @intFromPtr(encoded.ptr);
}

export fn aguafria_jvm_release(address: usize) void {
    const text = std.mem.span(@as([*:0]const u8, @ptrFromInt(address)));
    allocator.free(text[0 .. text.len + 1]);
}

export fn aguafria_jvm_release_native(address: usize, size: usize, alignment: usize) void {
    const bytes: [*]u8 = @ptrFromInt(address);
    allocator.rawFree(bytes[0..@max(1, size)], .fromByteUnits(alignment), @returnAddress());
}
