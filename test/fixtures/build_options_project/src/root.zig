const build_options = @import("build_options");

pub export fn answer() callconv(.c) u32 {
    return build_options.answer;
}

pub fn main() void {
    _ = build_options.message;
}
