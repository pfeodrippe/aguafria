// Appended to the production transport source by shared-support-test.
const writer_testing = @import("std").testing;
const WriterFixture = struct {
    var reject_write = false;
    var output: [64]u8 = undefined;
    var used: usize = 0;

    export fn aguafria_jvm_writer_new() ?*anyopaque {
        used = 0;
        return &output;
    }

    export fn aguafria_jvm_writer_append(_: *anyopaque, bytes: [*]const u8, length: usize) bool {
        if (reject_write) return false;
        @memcpy(output[used..][0..length], bytes[0..length]);
        used += length;
        return true;
    }

    export fn aguafria_jvm_writer_destroy(_: *anyopaque) void {}
};

test "shared failure becomes the caller's Zig error" {
    WriterFixture.reject_write = true;
    defer WriterFixture.reject_write = false;
    var sink = __aguafria_jvm.NativeWriter.init();
    defer sink.deinit();
    try writer_testing.expectError(error.WriteFailed, sink.writer.writeAll("failed"));
}

test "writer drain preserves vectors and repeated tails" {
    var sink = __aguafria_jvm.NativeWriter.init();
    defer sink.deinit();
    const consumed = try __aguafria_jvm.NativeWriter.drain(&sink.writer, &.{ "one", "!" }, 3);
    try writer_testing.expectEqual(6, consumed);
    try writer_testing.expectEqualStrings("one!!!", WriterFixture.output[0..WriterFixture.used]);
}

test "zero splat does not write the tail" {
    var sink = __aguafria_jvm.NativeWriter.init();
    defer sink.deinit();
    const consumed = try __aguafria_jvm.NativeWriter.drain(&sink.writer, &.{ "one", "ignored" }, 0);
    try writer_testing.expectEqual(3, consumed);
    try writer_testing.expectEqualStrings("one", WriterFixture.output[0..WriterFixture.used]);
}
