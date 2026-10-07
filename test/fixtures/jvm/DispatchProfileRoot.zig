// Force an imported module with its own fast mode to retain the root tracing
// ABI. The child's exported probes and address getters are the test interface.
const child = @import("child");
comptime {
    _ = &child.scalar;
    _ = &child.shape;
}
