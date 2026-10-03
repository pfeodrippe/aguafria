pub const left = [_]i32{ 1, 2 };
pub const right = [_]i32{ 3, 4 };
pub const joined = left ++ right;
pub const repeated: [4]i32 = @splat(0);
pub const terminated = [_:0]u8{ 1, 2 };
