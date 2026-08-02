fn field(value: i32) i32 {
    return value + 1;
}

fn index(value: i32) i32 {
    return value * 2;
}

fn assert(value: i32) i32 {
    return value;
}

pub fn demo(value: i32) i32 {
    return assert(field(value)) + index(value);
}
