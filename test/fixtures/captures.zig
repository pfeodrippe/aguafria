fn next(state: *u32) error{Finished}!?u32 {
    if (state.* == 2) return error.Finished;
    if (state.* == 0) return null;
    state.* -= 1;
    return state.*;
}

pub fn captureIf(value: anyerror!u32) u32 {
    return if (value) |number| number else |_| 0;
}

pub fn captureIfStatement(value: ?u32) u32 {
    var result: u32 = 0;
    if (value) |number| {
        result = number;
    } else {
        result = 1;
    }
    return result;
}

pub fn captureCatch(value: anyerror!u32) u32 {
    return value catch |err| switch (err) {
        else => 0,
    };
}

pub fn captureCatchBlock(value: anyerror!u32) u32 {
    const number = value catch |err| {
        _ = err;
        return 0;
    };
    return number;
}

pub fn captureWhile() u32 {
    var current: ?u32 = 3;
    var total: u32 = 0;
    outer: while (current) |number| : (current = null) {
        total += number;
        continue :outer;
    } else {
        total += 1;
    }
    return total;
}

pub fn captureWhileError() u32 {
    var state: u32 = 3;
    var total: u32 = 0;
    while (next(&state)) |number| {
        total += number;
    } else |_| {
        total += 1;
    }
    return total;
}
