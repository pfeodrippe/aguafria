fn field(value: i32) i32 {
    return value + 1;
}

fn index(value: i32) i32 {
    return value * 2;
}

pub fn demo(value: i32) i32 {
    return field(value) + index(value);
}
