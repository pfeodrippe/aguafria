pub fn sum() u32 {
    const values = .{ @as(u32, 1), @as(u32, 2) };
    const left, const right = values;
    return left + right;
}

pub fn replace() u32 {
    var left: u32 = 0;
    const values = .{ @as(u32, 1), @as(u32, 2) };
    left, const right = values;
    return left + right;
}

pub fn discard() u32 {
    const values = .{ @as(u32, 1), @as(u32, 2) };
    _, const right = values;
    return right;
}

