const std = @import("std");
const probe = @import("operation_probe");

const Record = struct {
    const Tag = enum { one, two };
    tag: Tag,
};

const Inspector = probe.Inspector(.{
    struct {
        pub const function_root = true;

        pub fn get() type {
            @compileError("Unrelated function signature must remain lazy");
        }

        pub fn name() []const u8 {
            return "fixture/unrelated";
        }
    },
    struct {
        pub const function_root = false;

        pub fn get() type {
            return Record;
        }

        pub fn name() []const u8 {
            return "fixture/Record";
        }
    },
});

comptime {
    if (!std.mem.eql(u8, Inspector.schema(Record), "fixture/Record"))
        @compileError("Direct declaration identity changed");
    if (!std.mem.eql(u8, Inspector.schema(Record.Tag), "(aguafria.keyword/FieldType fixture/Record \"tag\")"))
        @compileError("Reachable field identity changed");
}
