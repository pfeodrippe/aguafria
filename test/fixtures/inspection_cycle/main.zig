const peer = @import("./peer.zig");

pub const count: u32 = 7;

pub fn total() u32 {
    return peer.read();
}
