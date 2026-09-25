pub const Timestamp = struct {
    seconds: i64,
    nanos: u32,

    fn base() i64 {
        return 42;
    }

    /// Return the reference timestamp.
    pub fn reference() Timestamp {
        return .{ .seconds = base(), .nanos = 0 };
    }
};

pub fn main() void {
    const stamp = Timestamp.reference();
    @import("std").debug.assert(stamp.seconds == 42);
}

test "container methods" {
    main();
}
