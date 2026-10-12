fn cleanup(value: *u32) void {
    value.* += 1;
}

pub fn execute() u32 {
    var value: u32 = 0;
    {
        defer cleanup(&value);
    }
    return value;
}
