pub fn choose(a: bool, b: bool, optional: ?u32, fallible: anyerror!u32) u32 {
    var result: u32 = 0;
    if ((a and !b) or b) result += optional orelse 2;
    return result + (fallible catch 3);
}
