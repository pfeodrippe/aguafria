#!/usr/bin/env python3
"""Independent finite differences and feasibility checks for Pitoco's native ABI."""
import argparse
import ctypes as c
import hashlib
import json
import math
from pathlib import Path


def verify(path):
    library = c.CDLL(str(path.resolve()))
    double_pointer = c.POINTER(c.c_double)
    index_pointer = c.POINTER(c.c_uint32)
    handle = c.c_void_p
    signatures = {
        "create": (handle, [c.c_uint32, double_pointer, double_pointer, c.c_uint32, index_pointer,
                            c.c_uint32, index_pointer, c.POINTER(c.c_uint8)]),
        "destroy": (None, [handle]),
        "error": (c.c_char_p, []),
        "begin_step": (c.c_uint32, [handle, double_pointer, double_pointer, c.c_double, c.c_double, c.c_double]),
        "evaluate": (c.c_uint32, [handle, double_pointer, c.c_double, c.c_double, c.c_uint32,
                                  double_pointer, double_pointer, double_pointer]),
        "matrix_begin": (c.c_uint32, [handle, double_pointer, c.c_double]),
        "add_projected_element": (c.c_uint32, [handle, index_pointer, double_pointer]),
        "solve": (c.c_uint32, [handle, double_pointer, double_pointer]),
        "safe_step": (c.c_double, [handle, double_pointer, double_pointer]),
        "trial": (c.c_uint32, [handle, double_pointer, double_pointer, c.c_double, double_pointer]),
    }
    prefix = "pitoco_aguafria_variational_" if hasattr(library, "pitoco_aguafria_variational_create") else "pitoco_variational_"
    functions = {}
    for name, (result, arguments) in signatures.items():
        function = getattr(library, prefix + name)
        function.restype = result
        function.argtypes = arguments
        functions[name] = function

    def doubles(values):
        return (c.c_double * len(values))(*values)

    def check(status):
        if status:
            raise AssertionError(functions["error"]().decode())

    gap = 5e-5
    rest = [0, gap, 0, 0.1, gap, 0, 0, gap, 0.1, 0, gap + 0.1, 0]
    faces = (c.c_uint32 * 12)(2, 1, 3, 0, 3, 1, 0, 2, 3, 0, 1, 2)
    cells = (c.c_uint32 * 4)(0, 2, 1, 3)
    floor = (c.c_uint8 * 4)(1, 1, 1, 1)
    context = functions["create"](4, doubles(rest), doubles(rest), 4, faces, 1, cells, floor)
    assert context, functions["error"]()
    try:
        check(functions["begin_step"](context, doubles(rest), doubles([0.5] * 4), 0.001, 1e-4, 1000))
        point = [value + (1e-5 if index % 3 == 0 else 0) for index, value in enumerate(rest)]

        def evaluate(values, derivatives=1):
            gradient = doubles([0] * 12)
            barrier, friction = c.c_double(), c.c_double()
            check(functions["evaluate"](context, doubles(values), 1e-4, 1000, derivatives,
                                         gradient, c.byref(barrier), c.byref(friction)))
            return barrier.value + friction.value, list(gradient), barrier.value, friction.value

        energy, gradient, barrier, friction = evaluate(point, 2)
        assert energy > barrier > 0 and friction > 0
        normal_force = -sum(gradient[1::3])
        tangential_force = abs(sum(gradient[0::3]))
        assert normal_force > 0
        assert math.isclose(tangential_force, 0.5 * normal_force, rel_tol=1e-10)
        errors = []
        for index in range(12):
            plus, minus = point.copy(), point.copy()
            plus[index] += 1e-8
            minus[index] -= 1e-8
            numerical = (evaluate(plus, 0)[0] - evaluate(minus, 0)[0]) / 2e-8
            errors.append(abs(numerical - gradient[index]) / max(1, abs(gradient[index])))
        assert max(errors) < 2e-6, errors

        # Floor/friction Hessians are PSD here, so finite differences also check
        # the matrix assembled for Newton, independently of the sparse solver.
        evaluate(point, 2)
        scale = 1e-6
        check(functions["matrix_begin"](context, doubles([1] * 4), scale))
        rhs = [math.sin(index + 1) for index in range(12)]
        direction = doubles([0] * 12)
        check(functions["solve"](context, doubles(rhs), direction))
        epsilon = 1e-9
        plus = [x + epsilon * d for x, d in zip(point, direction)]
        minus = [x - epsilon * d for x, d in zip(point, direction)]
        gp, gm = evaluate(plus)[1], evaluate(minus)[1]
        linear_error = max(abs(d + scale * (a - b) / (2 * epsilon) + r)
                           for d, a, b, r in zip(direction, gp, gm, rhs))
        assert linear_error < 1e-5, linear_error

        # Insert a known PSD rank-one block with permuted local node numbers.
        # Its independently assembled matrix-vector product checks layout,
        # local/global indexing and the time-step scale in the sparse adapter.
        evaluate(point, 2)
        check(functions["matrix_begin"](context, doubles([1] * 4), scale))
        local_nodes = [2, 0, 3, 1]
        local_vector = [100 * math.sin(i + 0.5) for i in range(12)]
        block = [a * b for a in local_vector for b in local_vector]
        check(functions["add_projected_element"](
            context, (c.c_uint32 * 4)(*local_nodes), doubles(block)))
        check(functions["solve"](context, doubles(rhs), direction))
        global_vector = [0.0] * 12
        for local, value in enumerate(local_vector):
            global_vector[3 * local_nodes[local // 3] + local % 3] = value
        product = sum(a * b for a, b in zip(global_vector, direction))
        plus = [x + epsilon * d for x, d in zip(point, direction)]
        minus = [x - epsilon * d for x, d in zip(point, direction)]
        gp, gm = evaluate(plus)[1], evaluate(minus)[1]
        projected_error = max(abs(d + scale * ((a - b) / (2 * epsilon) + v * product) + r)
                              for d, a, b, v, r in zip(direction, gp, gm, global_vector, rhs))
        assert projected_error < 1e-5, projected_error

        downward = [(-0.1 if i % 3 == 1 else 0.0) for i in range(12)]
        evaluate(rest, 2)
        fraction = functions["safe_step"](context, doubles(rest), doubles(downward))
        assert 0 < fraction <= 0.8 * gap / 0.1
        output = doubles([0] * 12)
        assert functions["trial"](context, doubles(rest), doubles(downward), 1, output) == 1
        check(functions["trial"](context, doubles(rest), doubles(downward), fraction, output))
        assert min(output[1::3]) > 0

        # A positive final volume is insufficient: these two swaps cross a
        # zero-volume configuration halfway along the linear trajectory.
        target = rest[3:6] + rest[0:3] + rest[9:12] + rest[6:9]
        displacement = [b - a for a, b in zip(rest, target)]
        assert functions["trial"](context, doubles(rest), doubles(displacement), 1, output) == 1

        inverted = rest.copy()
        inverted[3:6], inverted[6:9] = rest[6:9], rest[3:6]
        invalid = functions["create"](4, doubles(rest), doubles(inverted), 4, faces, 1, cells, floor)
        assert not invalid
        return {"library_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                "maximum_relative_gradient_error": max(errors), "newton_matrix_residual": linear_error,
                "projected_element_matrix_residual": projected_error,
                "friction_normal_force_ratio": tangential_force / normal_force,
                "floor_step_fraction": fraction, "passed": True}
    finally:
        functions["destroy"](context)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("library", type=Path)
    args = parser.parse_args()
    print(json.dumps(verify(args.library), indent=2))
