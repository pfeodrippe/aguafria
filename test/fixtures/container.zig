pub const Options = union(enum) {
    replica: Replica,
    client,

    const Replica = struct {
        members_count: u8,
        pipeline_limit: u32 = 0,
    };

    pub fn score(options: Options) u32 {
        return switch (options) {
            .client => 1,
            .replica => |replica| replica.pipeline_limit,
        };
    }
};

pub const BooleanName = enum { true, false };

pub const @"127.0.0.1": u8 = 127;

pub const QuotedNames = struct {
    pub const @"null-device": u8 = 0;

    pub fn @"nil"() u8 {
        return 0;
    }

    pub fn init(value: u8) u8 {
        return value;
    }

    pub fn call_init(value: u8) u8 {
        return init(value);
    }
};
