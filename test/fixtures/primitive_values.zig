pub fn fill() [8]u8 {
    var bytes: [8]u8 = undefined;
    @memset(&bytes, 42);
    return bytes;
}
