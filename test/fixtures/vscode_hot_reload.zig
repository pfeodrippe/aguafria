pub fn answer() u32 {
    return 42;
}

pub fn caller() u32 {
    return answer();
}
