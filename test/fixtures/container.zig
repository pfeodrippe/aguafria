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
