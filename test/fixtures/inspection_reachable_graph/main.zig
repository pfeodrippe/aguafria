const std = @import("std");
const probe = @import("operation_probe");

fn Graph(comptime remaining: usize) type {
    return struct {
        const Self = @This();
        const Child = if (remaining == 0) void else Graph(remaining - 1);

        left: ?*Child,
        right: ?*Child,
        callback: ?*const fn (*Self, *Child) void,
    };
}

const Root = Graph(12);
const Inspector = probe.Inspector(.{TypeBox(Root, "fixture/Root")});

fn Marker(comptime id: usize) type {
    return struct {
        const identity = id;
    };
}

fn Inner(comptime id: usize) type {
    return struct {
        leaf: Marker(id),
    };
}

fn CollisionRoot(comptime id: usize) type {
    return struct {
        inner: Inner(id),
    };
}

fn TypeBox(comptime T: type, comptime expression: []const u8) type {
    return struct {
        pub fn get() type {
            return T;
        }

        pub fn name() []const u8 {
            return expression;
        }
    };
}

comptime {
    const anonymous = @Tuple(&.{u32});
    const schema = Inspector.schema(anonymous);
    if (!std.mem.eql(u8, schema, "(aguafria.keyword/Tuple (aguafria.keyword/& [:u32 ]))"))
        @compileError("Anonymous tuple schema changed: " ++ schema);
    if (!std.mem.eql(u8, Inspector.schema(Root), "fixture/Root"))
        @compileError("Direct declaration identity changed");
    if (!std.mem.eql(u8, Inspector.schema(*Root), "[:* fixture/Root]"))
        @compileError("Pointer declaration identity changed");
    if (!std.mem.endsWith(u8, Inspector.schema(Graph(11)), "\"child\")"))
        @compileError("Reachable child identity was not resolved");

    // More distinct types than buckets guarantees a collision in the search.
    var buckets: [1024]?usize = @splat(null);
    const collision = found: {
        for (0..1025) |id| {
            const bucket = std.hash.Wyhash.hash(0, @typeName(CollisionRoot(id))) % buckets.len;
            if (buckets[bucket]) |first| break :found .{ first, id };
            buckets[bucket] = id;
        }
        unreachable;
    };
    const First = CollisionRoot(collision[0]);
    const Second = CollisionRoot(collision[1]);
    if (First == Second) @compileError("Collision requires distinct native types");
    const CollisionInspector = probe.Inspector(.{
        TypeBox(First, "fixture/first"),
        TypeBox(Second, "fixture/second"),
    });
    const child = CollisionInspector.schema(Marker(collision[1]));
    if (!std.mem.startsWith(u8, child, "(aguafria.keyword/FieldType ") or
        std.mem.indexOf(u8, child, "fixture/second") == null)
        @compileError("Hash collision hid the reachable native child: " ++ child);
}
