const types = @import("types.zig");
pub const Event = types.Event();

pub fn encode(event: Event) u8 {
    return switch (event) {
        .gained => 73,
        .lost => 79,
    };
}
