const Ordered = struct {
    z: u8,
    a: u8,
    y: u8,
    b: u8,
    x: u8,
    c: u8,
    w: u8,
    d: u8,
    v: u8,
    e: u8,
};

pub fn ordered() Ordered {
    return .{
        .z = 1,
        .a = 2,
        .y = 3,
        .b = 4,
        .x = 5,
        .c = 6,
        .w = 7,
        .d = 8,
        .v = 9,
        .e = 10,
    };
}
