pub const Number = union {
    int: i32,
    float: f64,
};

pub const Tagged = union(enum) {
    number: i32,
    empty,

    pub fn answer() i32 {
        return 42;
    }
};

pub const Tag = enum { number, empty };
pub const ExplicitTag = union(Tag) {
    number: i32,
    empty: void,
};

pub const Packed = packed union { int: i32, uint: u32 };
pub const External = extern union { int: i32, uint: u32 };
