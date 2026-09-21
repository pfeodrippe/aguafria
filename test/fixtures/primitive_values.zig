pub fn fill() [8]u8 {
    var bytes: [8]u8 = undefined;
    @memset(&bytes, 42);
    return bytes;
}
pub const SignedByte = i8;
pub const NativeLong = c_long;
pub const Missing: ?*anyopaque = null;
pub const Node = extern struct { next: ?*Link = null };
pub const Link = extern struct { parent: ?*Node = null };
