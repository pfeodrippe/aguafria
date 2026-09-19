"""Independent mechanics references for tools/verify.py.

Exact rational element integration, constrained SciPy solves, modal checks, and
1D/3D rod diagnostics. These do not run in Pitoco or qualify a continuum model
merely by agreeing with its discretization. NumPy and SciPy are optional test
dependencies, imported only for mechanics commands. The 1D rod uses the boundary
mass redistribution of Dabaghi et al. (https://arxiv.org/abs/1601.00778); its
free-end benchmark differs from their clamped example.
"""

import argparse
from fractions import Fraction as Q
import hashlib
import json
from math import factorial
from pathlib import Path
import numpy as np
import csv
import scipy
from scipy.integrate import solve_ivp
import time
from scipy.linalg import cho_factor, cho_solve, solve_triangular, eigh
from scipy.optimize import minimize, nnls, root, brentq


ZERO = (0, 0, 0)


def add(a, b):
    result = a.copy()
    for powers, value in b.items():
        result[powers] = result.get(powers, Q(0)) + value
    return {powers: value for powers, value in result.items() if value}


def scale(a, coefficient):
    return {powers: value * coefficient for powers, value in a.items() if value * coefficient}


def multiply(a, b):
    result = {}
    for left, x in a.items():
        for right, y in b.items():
            powers = tuple(i + j for i, j in zip(left, right))
            result[powers] = result.get(powers, Q(0)) + x * y
    return {powers: value for powers, value in result.items() if value}


def derivative(a, axis):
    result = {}
    for powers, value in a.items():
        if powers[axis]:
            reduced = list(powers)
            reduced[axis] -= 1
            result[tuple(reduced)] = value * powers[axis]
    return result


def integral(a):
    return sum((value * Q(factorial(i) * factorial(j) * factorial(k), factorial(i+j+k+3))
                for (i, j, k), value in a.items()), Q(0))


def sum_polynomials(terms):
    result = {}
    for term in terms:
        result = add(result, term)
    return result


def reference_element(shear=Q(50000, 13), lame=Q(75000, 13),
                      inverse=((1, 0, 0), (0, 1, 0), (0, 0, 1)),
                      jacobian=Q(1), density=Q(120), trace_degree=1):
    one = {ZERO: Q(1)}
    linear = [{ZERO: Q(1), (1, 0, 0): Q(-1), (0, 1, 0): Q(-1), (0, 0, 1): Q(-1)},
              {(1, 0, 0): Q(1)}, {(0, 1, 0): Q(1)}, {(0, 0, 1): Q(1)}]
    bubble = one
    for coordinate in linear:
        bubble = multiply(bubble, coordinate)
    interior = [scale(multiply(bubble, add(scale(coordinate, Q(9)), scale(one, Q(-1)))), Q(168))
                for coordinate in linear]
    if trace_degree == 1:
        trace_shapes = linear
    elif trace_degree == 2:
        # Bernstein P2 traces: vertex squares and six edge products. Bounding
        # their position coefficients bounds the complete quadratic faces.
        trace_shapes = [multiply(shape, shape) for shape in linear]
        trace_shapes += [scale(multiply(linear[i], linear[j]), Q(2))
                         for i in range(4) for j in range(i + 1, 4)]
    else:
        raise ValueError('Trace degree must be one or two')
    boundary = []
    for shape in trace_shapes:
        moments = [integral(multiply(coordinate, shape)) for coordinate in linear]
        # Inverse of the exact P1 mass matrix on the reference tetrahedron.
        projection = [120 * (moment - sum(moments) / 5) for moment in moments]
        correction = sum_polynomials([scale(enrichment, coefficient)
                                      for enrichment, coefficient in zip(interior, projection)])
        boundary.append(add(shape, scale(correction, Q(-1))))
    shapes = boundary + interior
    summed = {}
    for shape in shapes:
        summed = add(summed, shape)
    assert summed == one
    for i in range(4):
        for shape in boundary:
            assert integral(multiply(linear[i], shape)) == 0
        for j in range(4):
            assert integral(multiply(linear[i], interior[j])) == Q(1 + (i == j), 120)
    gradients = [[derivative(shape, axis) for axis in range(3)] for shape in shapes]
    # Prove the compressed Bernstein control formulas by reconstructing every
    # polynomial derivative exactly, independent of the native interval code.
    reference_gradients = [(-1, -1, -1), (1, 0, 0), (0, 1, 0), (0, 0, 1)]
    indices = [(a, b, c, 4-a-b-c) for a in range(5) for b in range(5-a) for c in range(5-a-b)]
    assert len(indices) == 35
    for shape in range(4):
        for axis in range(3):
            reconstructed = {}
            for alpha in indices:
                coefficient = Q(0)
                if alpha == (1, 1, 1, 1):
                    coefficient = Q(126 * reference_gradients[shape][axis])
                elif sorted(alpha) == [0, 1, 1, 2]:
                    missing, doubled = alpha.index(0), alpha.index(2)
                    coefficient = Q(14 * (9 * (shape == doubled) - 1) * reference_gradients[missing][axis])
                bernstein = one
                multiplicity = factorial(4)
                for coordinate, exponent in zip(linear, alpha):
                    multiplicity //= factorial(exponent)
                    for _ in range(exponent):
                        bernstein = multiply(bernstein, coordinate)
                reconstructed = add(reconstructed, scale(bernstein, coefficient * multiplicity))
            assert reconstructed == derivative(interior[shape], axis)
    gradients = [[sum_polynomials([scale(gradient[j], Q(inverse[j][axis]))
                                   for j in range(3)]) for axis in range(3)]
                 for gradient in gradients]
    size = 3 * len(shapes)
    stiffness = np.zeros((size, size))
    for i in range(len(shapes)):
        for j in range(len(shapes)):
            products = [[jacobian * integral(multiply(gradients[i][a], gradients[j][b])) for b in range(3)] for a in range(3)]
            trace = sum(products[a][a] for a in range(3))
            for a in range(3):
                for b in range(3):
                    stiffness[3*i+a, 3*j+b] = float(lame*products[a][b] + shear*products[b][a]
                                                   + (shear*trace if a == b else 0))
    mass = np.zeros((size, size))
    private = 3 * len(boundary)
    for i in range(4):
        for j in range(4):
            for axis in range(3):
                mass[private+3*i+axis, private+3*j+axis] = float(density * jacobian * integral(multiply(linear[i], linear[j])))
    return stiffness, mass


def verify_assembly(directory):
    stiffness = np.zeros((39, 39))
    mass = np.zeros((39, 39))
    descriptions = [([0, 1, 2, 3], {}, 5),
                    ([1, 2, 3, 4], {'shear': Q(21250, 3), 'lame': Q(42500, 9),
                     'inverse': ((Q(-1, 2), Q(1, 2), Q(-1, 2)),
                                 (Q(-1, 2), Q(-1, 2), Q(1, 2)),
                                 (Q(1, 2), Q(1, 2), Q(1, 2))),
                     'jacobian': Q(2), 'density': Q(60)}, 9)]
    for vertices, parameters, private_offset in descriptions:
        local_k, local_m = reference_element(**parameters)
        indices = [3 * node + axis for node in vertices + list(range(private_offset, private_offset + 4))
                   for axis in range(3)]
        stiffness[np.ix_(indices, indices)] += local_k
        mass[np.ix_(indices, indices)] += local_m
    errors = []
    for duration, name in [(0.01, '10ms'), (0.02, '20ms')]:
        native = np.loadtxt(directory / f'mixed-assembly-tangent-{name}.csv', delimiter=',')
        expected = stiffness + mass / duration**2
        np.testing.assert_allclose(native, expected, rtol=3e-12, atol=4e-10)
        errors.append(float(np.max(abs(native - expected))))
        assert min(np.linalg.eigvalsh(native)) > 0
        force = np.zeros(39)
        force[16::3] = -49.05  # eight private coefficients, five kg each
        displacement = np.linalg.solve(native, force)
        np.testing.assert_allclose(displacement.reshape(-1, 3),
                                   np.tile([0, -9.81 * duration**2, 0], (13, 1)), atol=2e-15)
    eigenvalues = np.linalg.eigvalsh(stiffness)
    assert np.count_nonzero(abs(eigenvalues) < 1e-10 * max(eigenvalues)) == 6
    assert min(np.linalg.eigvalsh(stiffness[:15, :15])) > 0
    assert np.count_nonzero(np.linalg.eigvalsh(mass) == 0) == 15
    assert min(np.linalg.eigvalsh(mass[15:, 15:])) > 0
    return {'scope': 'two conforming tetrahedra; two materials; two densities',
            'native_tangent_maximum_absolute_errors': errors,
            'stiffness_rigid_nullity': 6, 'mass_nullity': 15,
            'minimum_boundary_stiffness_eigenvalue': float(min(np.linalg.eigvalsh(stiffness[:15, :15]))),
            'independent_free_fall_linear_solve': 'verified at 10 and 20 ms'}


def verify_tetra(directory, trace_degree=1):
    source_hash = hashlib.sha256(Path("src/field_lab/mixed_tetra.clj").read_bytes()).hexdigest()
    assert (directory / "mixed-tetra-native-source.sha256").read_text().strip() == source_hash
    exact, mass = reference_element(trace_degree=trace_degree)
    boundary_size = 12 if trace_degree == 1 else 30
    native = np.loadtxt(directory / 'mixed-tetra-rest-hessian.csv', delimiter=',')
    np.testing.assert_allclose(native, exact, rtol=2e-12, atol=2e-10)
    eigenvalues = np.linalg.eigvalsh(exact)
    scale_value = max(abs(eigenvalues))
    assert np.count_nonzero(abs(eigenvalues) < 1e-11 * scale_value) == 6
    assert eigenvalues[6] > 0
    boundary = exact[:boundary_size, :boundary_size]
    boundary_eigenvalues = np.linalg.eigvalsh(boundary)
    assert min(boundary_eigenvalues) > 0
    reduced = exact[boundary_size:, boundary_size:] - exact[boundary_size:, :boundary_size] @ np.linalg.solve(boundary, exact[:boundary_size, boundary_size:])
    reduced_eigenvalues = np.linalg.eigvalsh(reduced)
    assert np.count_nonzero(abs(reduced_eigenvalues) < 1e-10 * scale_value) == 6
    assert reduced_eigenvalues[6] > 0
    mass_eigenvalues = np.linalg.eigvalsh(mass)
    assert np.count_nonzero(mass_eigenvalues == 0) == boundary_size
    assert min(np.linalg.eigvalsh(mass[boundary_size:, boundary_size:])) > 0
    result = {'status': 'verified', 'scope': 'rest unit tetrahedron; not contact qualification',
              'trace_degree': trace_degree,
              'assembly': verify_assembly(directory) if trace_degree == 1 else 'checked separately in native job regressions',
              'exact_moment_and_partition_identities': True,
              'exact_bernstein_derivative_identities': True,
              'native_hessian_maximum_absolute_error': float(np.max(abs(native-exact))),
              'stiffness_rigid_nullity': 6,
              'stiffness_smallest_nonrigid_eigenvalue': float(eigenvalues[6]),
              'boundary_stiffness_smallest_eigenvalue': float(min(boundary_eigenvalues)),
              'condensed_stiffness_rigid_nullity': 6,
              'condensed_stiffness_smallest_nonrigid_eigenvalue': float(reduced_eigenvalues[6]),
              'mass_nullity': boundary_size, 'internal_mass_smallest_eigenvalue': float(mass_eigenvalues[boundary_size]),
              'native_source_sha256': hashlib.sha256(Path('src/field_lab/mixed_tetra.clj').read_bytes()).hexdigest(),
              'verifier_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
    (directory / 'mixed-tetra-independent.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))


def tetra_main(argv=None):
    parser = argparse.ArgumentParser(description="Independent exact-polynomial checks for Pitoco's experimental mixed tet.")
    parser.add_argument('--directory', type=Path, default=Path('build'))
    parser.add_argument('--trace-degree', type=int, choices=[1, 2], default=1)
    args = parser.parse_args(argv)
    verify_tetra(args.directory, args.trace_degree)


def samples():
    one = {ZERO: Q(1)}
    linear = [{ZERO: Q(1), (1, 0, 0): Q(-1),
               (0, 1, 0): Q(-1), (0, 0, 1): Q(-1)},
              {(1, 0, 0): Q(1)}, {(0, 1, 0): Q(1)}, {(0, 0, 1): Q(1)}]
    bubble = one
    for shape in linear:
        bubble = multiply(bubble, shape)
    interior = [scale(multiply(bubble, add(
        scale(shape, 9), scale(one, -1))), 168) for shape in linear]
    shapes = [add(a, scale(b, -1)) for a, b in zip(linear, interior)] + interior
    nodes, weights = np.polynomial.legendre.leggauss(6)
    nodes, weights = (nodes + 1) / 2, weights / 2
    coordinates, volumes = [], []
    for i, u in enumerate(nodes):
        for j, v in enumerate(nodes):
            for k, w in enumerate(nodes):
                coordinates.append([u, (1-u)*v, (1-u)*(1-v)*w])
                volumes.append((1-u)**2 * (1-v) * weights[i]*weights[j]*weights[k])
    coordinates = np.array(coordinates)
    gradients = np.zeros((len(coordinates), 8, 3))
    for i, shape in enumerate(shapes):
        for axis in range(3):
            for powers, value in derivative(shape, axis).items():
                gradients[:, i, axis] += float(value) * np.prod(coordinates ** np.array(powers), axis=1)
    return gradients, np.array(volumes)


GRADIENTS, WEIGHTS = samples()


CELLS = [([0, 1, 2, 3, 5, 6, 7, 8], np.eye(3), 1.0, 10000., .3),
         ([1, 2, 3, 4, 9, 10, 11, 12],
          np.array([[-.5, .5, -.5], [-.5, -.5, .5], [.5, .5, .5]]), 2.0, 17000., .2)]


def objective(flat, duration):
    x = flat.reshape(13, 3)
    gradient = np.zeros_like(x)
    energy = np.longdouble(0)
    minimum_j = np.inf
    for indices, inverse, jacobian, young, poisson in CELLS:
        g = GRADIENTS @ inverse
        f = np.eye(3) + np.einsum('ia,qib->qab', x[indices], g)
        determinant = np.linalg.det(f)
        minimum_j = min(minimum_j, min(determinant))
        invariant = np.einsum('qab,qab->q', f, f)
        shear = young / (2 * (1 + poisson))
        mu = 4 * shear / 3
        lame = young * poisson / ((1 + poisson) * (1 - 2 * poisson)) + 5 * shear / 6
        alpha = 1 + .75 * mu / lame
        # Evaluate the original constitutive expression in extended precision;
        # it is independent of the native cancellation-resistant implementation.
        wide = f.astype(np.longdouble)
        cof = np.stack([np.cross(wide[:, :, 1], wide[:, :, 2]),
                        np.cross(wide[:, :, 2], wide[:, :, 0]),
                        np.cross(wide[:, :, 0], wide[:, :, 1])], axis=2)
        j = np.einsum('qa,qa->q', wide[:, :, 0], cof[:, :, 0])
        i1 = np.einsum('qab,qab->q', wide, wide)
        density = (.5 * mu * (i1 - 3) + .5 * lame * ((j-alpha)**2 - (1-alpha)**2)
                   - .5 * mu * np.log((i1+1)/4))
        energy += np.dot(WEIGHTS * jacobian, density)
        stress = mu * (1 - 1/(invariant+1))[:, None, None] * f + lame * (determinant-alpha)[:, None, None] * cof.astype(float)
        gradient[indices] += np.einsum('q,qab,qib->ia', WEIGHTS * jacobian, stress, g)
        private = indices[4:]
        delta = x[private]
        inertia = (delta + np.sum(delta, axis=0)) / duration**2  # rho V / 20 = 1 kg in both cells
        gradient[private] += inertia
        gradient[private, 1] += 49.05
        energy += .5 * np.sum(delta * inertia) + 49.05 * np.sum(delta[:, 1])
    return float(energy), gradient.ravel(), minimum_j


def verify_solver():
    results = []
    lower = np.full((13, 3), -np.inf)
    lower[:5, 1] = -np.array([0., 0., 1., 0., 1.])
    scale = .001
    for duration, label in [(.01, 'floor'), (.005, 'floor-short')]:
        bounds = [(lo / scale, None) for lo in lower.ravel()]
        solved = minimize(lambda y: objective(y * scale, duration)[0], np.zeros(39),
                          jac=lambda y: objective(y * scale, duration)[1] * scale,
                          bounds=bounds, method='SLSQP', options={'ftol': 1e-14, 'maxiter': 500})
        assert solved.success, solved.message
        x = solved.x * scale
        active = np.isfinite(lower.ravel()) & (abs(x - lower.ravel()) < 1e-10)
        x[active] = lower.ravel()[active]
        free = ~active

        def residual(y):
            trial = x.copy()
            trial[free] = y * scale
            return objective(trial, duration)[1][free] * scale

        polished = root(residual, x[free] / scale, method='hybr', options={'xtol': 1e-10})
        assert polished.success, polished.message
        x[free] = polished.x * scale
        energy, gradient, minimum_j = objective(x, duration)
        assert np.all(x >= lower.ravel())
        assert np.all(gradient[active] > 0)
        assert max(abs(gradient[free])) < 1e-7
        native = np.loadtxt(f'build/mixed-solver-{label}.csv', delimiter=',').ravel()
        native_energy, native_gradient, native_j = objective(native, duration)
        native_free_residual = max(abs(native_gradient[free]))
        assert native_free_residual < 1e-7
        assert np.all(native_gradient[active] > 0)
        np.testing.assert_allclose(native, x, rtol=0, atol=2e-10)
        results.append({'duration_s': duration, 'slsqp_iterations': solved.nit,
                        'active_scalar_constraints': np.flatnonzero(active).tolist(),
                        'maximum_position_difference_m': float(max(abs(native-x))),
                        'independent_free_residual_N': float(max(abs(gradient[free]))),
                        'native_free_residual_N_recomputed': float(native_free_residual),
                        'native_energy_J_recomputed': native_energy,
                        'energy_difference_J': native_energy-energy,
                        'minimum_sampled_jacobian': float(min(native_j, minimum_j))})
    report = {'status': 'verified', 'scope': 'two native plane-constrained steps; not impact qualification',
              'cases': results,
              'source_sha256': {str(p): hashlib.sha256(p.read_bytes()).hexdigest()
                                for p in [Path('src/field_lab/mixed_solver.clj'), Path('src/field_lab/mixed_tetra.clj'), Path(__file__)]}}
    Path('build/mixed-solver-independent.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


def mode_reference():
    """Exact-polynomial tangent, massless-coordinate condensation, eigenmode."""
    from scipy.linalg import eigh
    stiffness, mass = reference_element()
    projection = -np.linalg.solve(stiffness[:12, :12], stiffness[:12, 12:])
    condensed = stiffness[12:, 12:] + stiffness[12:, :12] @ projection
    inertia = mass[12:, 12:]
    values, vectors = eigh((condensed + condensed.T) / 2, inertia)
    index = np.flatnonzero(values > 1e-6)[0]
    omega = np.sqrt(values[index])
    velocity = vectors[:, index]
    velocity *= 1e-4 / max(abs(velocity))
    duration = .7 * 2 * np.pi / omega
    return {'omega': float(omega), 'duration': float(duration),
            'initial_velocity': velocity.tolist(),
            'final_displacement': (np.sin(omega * duration) / omega * velocity).tolist(),
            'final_velocity': (np.cos(omega * duration) * velocity).tolist(),
            'mass': inertia.tolist(), 'initial_energy': float(.5 * velocity @ inertia @ velocity)}


def prepare_mode():
    reference = mode_reference()
    Path('build/sdirk-mode-reference.json').write_text(json.dumps(reference, indent=2) + '\n')
    velocities = np.array(reference['initial_velocity']).reshape(4, 3).tolist()
    Path('build/sdirk-mode-input.edn').write_text(
        '{:duration ' + repr(reference['duration']) + ' :velocities ' + str(velocities).replace(',', '') + '}\n')
    print({'mode_input': 'build/sdirk-mode-input.edn', 'omega': reference['omega']})


def verify_mode():
    reference = mode_reference()
    groups = {}
    with open('build/sdirk-mode-native.csv') as stream:
        for row in csv.DictReader(stream):
            key = (row['method'], int(row['steps']))
            groups.setdefault(key, []).append(row)
    expected_keys = {(method, steps) for method in ['backward-euler', 'sdirk2'] for steps in [16, 32, 64]}
    assert set(groups) == expected_keys, 'Missing native modal runs'
    mass = np.array(reference['mass'])
    v0 = np.array(reference['initial_velocity'])
    omega = reference['omega']
    results = []
    errors = {}
    for (method, steps), rows in sorted(groups.items()):
        rows.sort(key=lambda r: int(r['index']))
        assert [int(r['index']) for r in rows] == list(range(12))
        tolerance = float(rows[0]['force_tolerance'])
        residual = float(rows[0]['max_force_residual'])
        assert 0 < tolerance <= 1e-9 and 0 <= residual <= tolerance
        assert all(float(row['force_tolerance']) == tolerance and
                   float(row['max_force_residual']) == residual for row in rows)
        displacement = np.array([float(r['displacement']) for r in rows])
        velocity = np.array([float(r['velocity']) for r in rows])
        du = displacement - reference['final_displacement']
        dv = velocity - reference['final_velocity']
        error = np.sqrt((omega**2 * du @ mass @ du + dv @ mass @ dv) / (v0 @ mass @ v0))
        assert np.isfinite(error)
        errors[method, steps] = float(error)
        results.append({'method': method, 'steps': steps, 'relative_phase_space_error': float(error),
                        'force_tolerance_N': tolerance, 'maximum_endpoint_force_residual_N': residual})
    ratios = {method: errors[method, 64] / errors[method, 32] for method in ['backward-euler', 'sdirk2']}
    assert .2 < ratios['sdirk2'] < .3, ratios
    assert .45 < ratios['backward-euler'] < .65, ratios
    assert errors['sdirk2', 64] < errors['backward-euler', 64] / 20
    report = {'status': 'verified', 'scope': 'small-amplitude free elastic mode; not contact order or impact accuracy',
              'cases': results, 'fine_to_medium_error_ratios': ratios,
              'source_sha256': {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in
                 [Path(__file__), Path('tools/verify.py'), Path('src/field_lab/mixed_job.clj'),
                  Path('src/field_lab/mixed_solver.clj'), Path('src/field_lab/mixed_tetra.clj'),
                  Path('src/field_lab/hyperelastic.clj'), Path('test/field_lab/mixed_job_test.clj')]}}
    Path('build/sdirk-mode-independent.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


class LinearMixedRod:
    """Independent linearization of the authored 3D mixed mesh.

    Exact-polynomial stiffness/consistent mass and a dual nonnegative least
    squares contact solve replace native quadrature/Newton/CG/line search.
    This diagnoses discretization, not nonlinear or geometric qualification.
    """

    def __init__(self, case, trace_degree=1):
        description = case['description']
        if trace_degree not in {1, 2}:
            raise ValueError('Trace degree must be one or two')
        points = list(description['mesh']['points'])
        initial_velocities = list(description['initial-velocities'])
        cells = description['mesh']['cells']
        boundary_cells = [list(cell) for cell in cells]
        if trace_degree == 2:
            edge_controls = {}
            for cell, indices in enumerate(cells):
                for left in range(4):
                    for right in range(left + 1, 4):
                        edge = tuple(sorted((indices[left], indices[right])))
                        if edge not in edge_controls:
                            edge_controls[edge] = len(points)
                            points.append(((np.array(points[edge[0]]) + points[edge[1]]) / 2).tolist())
                            initial_velocities.append(((np.array(initial_velocities[edge[0]]) + initial_velocities[edge[1]]) / 2).tolist())
                        boundary_cells[cell].append(edge_controls[edge])
        points = np.array(points)
        vertices = len(points)
        size = 3 * (vertices + 4 * len(cells))
        self.stiffness = np.zeros((size, size))
        self.mass = np.zeros((size, size))
        self.contact = np.arange(vertices) * 3 + 1
        self.lower = -points[:, 1]
        self.velocity = np.zeros((size // 3, 3))
        self.velocity[:vertices] = initial_velocities
        young = description['material']['young-Pa']
        poisson = description['material']['poisson-ratio']
        density = description['density-kg-m3']
        assert poisson == 0 and description['gravity'] == [0.0, 0.0, 0.0]
        assert description['floor?'] and density > 0 and young > 0
        element_cache = {}
        for cell, indices in enumerate(cells):
            coordinates = points[indices]
            edges = (coordinates[1:] - coordinates[0]).T
            determinant = np.linalg.det(edges)
            assert determinant > 0
            inverse = np.linalg.inv(edges)
            # Cartesian authored meshes have a few repeated cell orientations.
            # Rational decimal rounding affects geometry below 1e-12 relative.
            key = (tuple(np.round(inverse.ravel(), 10)), round(determinant, 18))
            np.testing.assert_allclose(np.array(key[0]).reshape(3, 3), inverse, rtol=1e-12, atol=1e-12)
            assert abs(key[1] / determinant - 1) < 1e-12
            if key not in element_cache:
                q = Q
                rational_inverse = tuple(tuple(q(str(value)) for value in row)
                                         for row in np.array(key[0]).reshape(3, 3))
                element_cache[key] = reference_element(
                    shear=q(str(young)) / 2, lame=q(0), inverse=rational_inverse,
                    jacobian=q(str(key[1])), density=q(str(density)), trace_degree=trace_degree)
            stiffness, mass = element_cache[key]
            private = list(range(vertices + 4 * cell, vertices + 4 * cell + 4))
            nodes = boundary_cells[cell] + private
            dofs = [3 * node + axis for node in nodes for axis in range(3)]
            self.stiffness[np.ix_(dofs, dofs)] += stiffness
            self.mass[np.ix_(dofs, dofs)] += mass
            self.velocity[private] = self.velocity[indices]
        self.velocity = self.velocity.ravel()
        self.points = points
        self.cells = cells
        self.vertices = vertices
        self.trace_degree = trace_degree
        self.vertical = np.tile([0.0, 1.0, 0.0], size // 3)
        self.total_mass = float(self.vertical @ self.mass @ self.vertical)
        self.factorizations = {}
        self.maximum_stationarity = 0.0
        self.maximum_penetration = 0.0
        self.maximum_complementarity = 0.0
        expected_mass = case['reference']['mass-kg']
        assert abs(self.total_mass / expected_mass - 1) < 1e-12
        assert np.max(abs(self.stiffness @ self.vertical)) < 1e-7

    def localize_enrichment(self):
        """Restrict non-contact cells to ordinary affine displacement/velocity.

        This is a diagnostic alternative, not the current native formulation.
        Keep all cells incident on the initially lowest vertices enriched. In
        every other cell set its private coefficients equal to its vertices.
        The existing polynomial identities then recover an ordinary P1 tet.
        Contact vertices retain zero inertia; bulk vertices carry consistent
        inertia. No mass is discarded, redistributed or scaled.
        """
        if self.trace_degree != 1:
            raise ValueError('Boundary-star restriction is defined only for the linear trace')
        bottom = np.flatnonzero(self.points[:, 1] == np.min(self.points[:, 1]))
        selected = [index for index, cell in enumerate(self.cells) if np.isin(cell, bottom).any()]
        shared = 3 * self.vertices
        retained = list(range(shared))
        for cell in selected:
            retained.extend(range(shared + 12 * cell, shared + 12 * (cell + 1)))
        embedding = np.zeros((len(self.velocity), len(retained)))
        embedding[retained, np.arange(len(retained))] = 1.0
        for cell, vertices in enumerate(self.cells):
            if cell not in selected:
                for node, vertex in enumerate(vertices):
                    for axis in range(3):
                        embedding[shared + 12 * cell + 3 * node + axis, 3 * vertex + axis] = 1.0

        audit = self.restrict_coordinates(embedding, retained)
        assert np.all(self.mass[3 * bottom + 1] == 0)
        return dict(audit, enriched_cells=selected, ordinary_cells=len(self.cells) - len(selected))

    def share_projected_velocity(self):
        """Diagnostic continuous P1 velocity instead of cell-private P1.

        Retain the original enriched displacement functions, but identify the
        private coefficients belonging to the same rest vertex. This restricts
        both trial displacement and projected velocity spaces; it is not mass
        lumping and is not yet implemented in the native adapter.
        """
        if self.trace_degree != 1:
            raise ValueError('Continuous-velocity restriction is defined only for the linear trace')
        shared = 3 * self.vertices
        retained = list(range(shared))
        private = {}
        for cell, vertices in enumerate(self.cells):
            for node, vertex in enumerate(vertices):
                private.setdefault(vertex, shared + 12 * cell + 3 * node)
        for vertex in range(self.vertices):
            retained.extend(range(private[vertex], private[vertex] + 3))
        embedding = np.zeros((len(self.velocity), len(retained)))
        embedding[:shared, :shared] = np.eye(shared)
        for cell, vertices in enumerate(self.cells):
            for node, vertex in enumerate(vertices):
                for axis in range(3):
                    embedding[shared + 12 * cell + 3 * node + axis, shared + 3 * vertex + axis] = 1.0
        return self.restrict_coordinates(embedding, retained)

    def restrict_coordinates(self, embedding, retained):
        """Congruent restriction of stiffness/inertia with exact affine audit."""
        # Verify all affine velocity moments, including the six rigid modes,
        # against the original exactly integrated element mass matrices.
        coordinates = np.concatenate((self.points, self.points[np.array(self.cells)].reshape(-1, 3)))
        affine = np.zeros((len(self.velocity), 12))
        for axis in range(3):
            affine[axis::3, axis] = 1.0
            for derivative in range(3):
                affine[axis::3, 3 + 3 * axis + derivative] = coordinates[:, derivative]
        original_moments = affine.T @ self.mass @ affine
        reduced_mass = embedding.T @ self.mass @ embedding
        reduced_affine = affine[retained]
        projected_moments = reduced_affine.T @ reduced_mass @ reduced_affine
        np.testing.assert_allclose(embedding @ reduced_affine, affine, rtol=0, atol=1e-14)
        np.testing.assert_allclose(projected_moments, original_moments, rtol=1e-12, atol=1e-16)
        np.testing.assert_allclose(embedding @ self.velocity[retained], self.velocity, rtol=0, atol=1e-14)
        self.stiffness = embedding.T @ self.stiffness @ embedding
        self.mass = reduced_mass
        self.velocity = self.velocity[retained]
        self.vertical = self.vertical[retained]
        self.factorizations.clear()
        return {'original_scalar_dofs': embedding.shape[0], 'restricted_scalar_dofs': embedding.shape[1],
                'maximum_affine_mass_moment_difference': float(np.max(abs(projected_moments - original_moments)))}

    def stage(self, prediction, duration):
        if duration not in self.factorizations:
            # Floating endpoint subtraction produces several near-identical
            # steps. Bound retained dense factorizations without altering dt.
            if len(self.factorizations) >= 8:
                self.factorizations.clear()
            matrix = self.stiffness + self.mass / duration**2
            factor = cho_factor(matrix)
            selectors = np.eye(len(prediction))[:, self.contact]
            compliance = cho_solve(factor, selectors)
            dual = compliance[self.contact]
            dual = (dual + dual.T) / 2
            root = np.linalg.cholesky(dual)
            self.factorizations[duration] = matrix, factor, compliance, root
        matrix, factor, compliance, root = self.factorizations[duration]
        rhs = self.mass @ prediction / duration**2
        free = cho_solve(factor, rhs)
        gap = free[self.contact] - self.lower
        target = solve_triangular(root, -gap, lower=True)
        reactions, _ = nnls(root.T, target, maxiter=20 * len(gap))
        position = free + compliance @ reactions
        residual = matrix @ position - rhs
        residual[self.contact] -= reactions
        gap = position[self.contact] - self.lower
        stationarity = float(max(abs(residual)))
        penetration = float(max(0, -min(gap)))
        complementarity = float(max(abs(gap * reactions)))
        assert stationarity < 1e-8 and penetration < 1e-12 and complementarity < 1e-12
        self.maximum_stationarity = max(self.maximum_stationarity, stationarity)
        self.maximum_penetration = max(self.maximum_penetration, penetration)
        self.maximum_complementarity = max(self.maximum_complementarity, complementarity)
        return position, float(sum(reactions))

    def run(self, case, refinement=1):
        self.maximum_stationarity = 0.0
        self.maximum_penetration = 0.0
        self.maximum_complementarity = 0.0
        position = np.zeros_like(self.velocity)
        velocity = self.velocity.copy()
        method = case['integration']
        gamma = 1 - 1 / np.sqrt(2)
        reference = case['reference']
        initial_energy = reference['initial-energy-J']
        records = []
        previous_momentum = float(self.vertical @ self.mass @ velocity)
        for sample in case['samples']:
            duration = (sample['time-s'] - sample['begin-s']) / refinement
            impulse = 0.0
            for _ in range(refinement):
                if method == 'backward-euler':
                    endpoint, force = self.stage(position + duration * velocity, duration)
                    velocity = (endpoint - position) / duration
                    impulse += duration * force
                elif method == 'sdirk2':
                    stage, first_force = self.stage(position + gamma * duration * velocity, gamma * duration)
                    first_velocity = (stage - position) / (gamma * duration)
                    predictor = (position + 2 * (1-gamma) * duration * first_velocity
                                 + (2*gamma-1) * duration * velocity)
                    endpoint, last_force = self.stage(predictor, gamma * duration)
                    velocity = (endpoint - position - (1-gamma) * duration * first_velocity) / (gamma * duration)
                    impulse += duration * ((1-gamma) * first_force + gamma * last_force)
                else:
                    raise ValueError(f'Unsupported integration: {method}')
                position = endpoint
            momentum = float(self.vertical @ self.mass @ velocity)
            energy = float(.5 * (position @ self.stiffness @ position + velocity @ self.mass @ velocity))
            records.append({'time_s': sample['time-s'], 'begin_s': sample['begin-s'],
                            'force_N': impulse / (duration * refinement),
                            'velocity_m_s': momentum / self.total_mass, 'energy_J': energy,
                            'momentum_error_N_s': momentum - previous_momentum - impulse})
            previous_momentum = momentum
        force_error = sum((row['time_s'] - row['begin_s']) * abs(row['force_N'] - sample['reference-average-contact-force-N'])
                          for row, sample in zip(records, case['samples'])) / reference['total-contact-impulse-N-s']
        return {'integration': method, 'refinement': refinement,
                'force_observation_window_s': case['maximum-step-s'],
                'force_relative_L1_error': force_error,
                'peak_force_ratio': max(row['force_N'] for row in records) / reference['contact-force-N'],
                'rebound_ratio': records[-1]['velocity_m_s'] / reference['speed-m-s'],
                'energy_loss_fraction': 1 - records[-1]['energy_J'] / initial_energy,
                'maximum_stationarity_N': self.maximum_stationarity,
                'maximum_penetration_m': self.maximum_penetration,
                'maximum_complementarity_J': self.maximum_complementarity,
                'maximum_momentum_error_N_s': max(abs(row['momentum_error_N_s']) for row in records),
                'trace': records}

    def run_semidiscrete(self, case, tolerance):
        """Integrate private inertia with instantaneous static boundary contact.

        Adaptive DOP853 is independent of both native integrators. It estimates
        the time-continuous limit of this mesh, not the spatial continuum.
        """
        static = np.flatnonzero(np.diag(self.mass) == 0)
        dynamic = np.flatnonzero(np.diag(self.mass) > 0)
        assert len(static) + len(dynamic) == len(self.velocity)
        assert np.all(self.mass[static] == 0)
        static_map = {index: local for local, index in enumerate(static)}
        constrained = np.array([i for i, index in enumerate(self.contact) if index in static_map])
        boundary_contact = np.array([static_map[self.contact[i]] for i in constrained])
        kqq = self.stiffness[np.ix_(static, static)]
        kqr = self.stiffness[np.ix_(static, dynamic)]
        krr = self.stiffness[np.ix_(dynamic, dynamic)]
        inertia = self.mass[np.ix_(dynamic, dynamic)]
        factor = cho_factor(kqq)
        projection = -cho_solve(factor, kqr)
        selectors = np.eye(len(static))[:, boundary_contact]
        compliance = cho_solve(factor, selectors)
        dual = compliance[boundary_contact]
        root = np.linalg.cholesky((dual + dual.T) / 2)
        reduced = krr + kqr.T @ projection
        inverse_mass = cho_factor(inertia)
        acceleration = -cho_solve(inverse_mass, reduced)
        contact_acceleration = -cho_solve(inverse_mass, kqr.T @ compliance)
        count = inertia.shape[0]
        evaluations = 0
        reference = case['reference']
        speed = reference['speed-m-s']
        displacement_scale = speed * reference['contact-duration-s']
        scales = np.concatenate((np.full(count, displacement_scale), np.full(count, speed),
                                 [self.total_mass * speed]))

        def response(displacement):
            free = projection @ displacement
            target = solve_triangular(root, self.lower[constrained] - free[boundary_contact], lower=True)
            reactions, _ = nnls(root.T, target, maxiter=20 * len(self.contact))
            q = free + compliance @ reactions
            full = np.empty(len(self.velocity))
            full[static], full[dynamic] = q, displacement
            # This diagnostic only supports active contact on massless DOFs.
            # Reject any trajectory violating the other original plane bounds.
            assert min(full[self.contact] - self.lower) > -1e-12
            return full, reactions

        def derivative(time, state):
            nonlocal evaluations
            evaluations += 1
            state = state * scales
            displacement, velocity = state[:count], state[count:2*count]
            _, reactions = response(displacement)
            return np.concatenate((velocity, acceleration @ displacement + contact_acceleration @ reactions,
                                   [sum(reactions)])) / scales

        initial = np.concatenate((np.zeros(count), self.velocity[dynamic], [0.0])) / scales
        duration = case['samples'][-1]['time-s']
        solved = solve_ivp(derivative, (0, duration), initial, method='DOP853',
                           rtol=tolerance, atol=tolerance, dense_output=True,
                           max_step=reference['contact-duration-s'] / 1024)
        assert solved.success, solved.message
        records = []
        previous_impulse = 0.0
        vertical = self.vertical[dynamic]
        previous_momentum = float(vertical @ inertia @ self.velocity[dynamic])
        for sample in case['samples']:
            state = solved.sol(sample['time-s']) * scales
            displacement, velocity = state[:count], state[count:2*count]
            full, reactions = response(displacement)
            impulse = state[-1] - previous_impulse
            momentum = float(vertical @ inertia @ velocity)
            energy = float(.5 * (full @ self.stiffness @ full + velocity @ inertia @ velocity))
            records.append({'time_s': sample['time-s'], 'begin_s': sample['begin-s'],
                            'force_N': impulse / (sample['time-s'] - sample['begin-s']),
                            'velocity_m_s': momentum / self.total_mass, 'energy_J': energy,
                            'momentum_error_N_s': momentum - previous_momentum - impulse})
            previous_momentum, previous_impulse = momentum, state[-1]
        force_error = sum((row['time_s'] - row['begin_s']) * abs(row['force_N'] - sample['reference-average-contact-force-N'])
                          for row, sample in zip(records, case['samples'])) / reference['total-contact-impulse-N-s']
        return {'integration': 'adaptive-dop853', 'tolerance': tolerance, 'evaluations': evaluations,
                'force_observation_window_s': case['maximum-step-s'],
                'force_relative_L1_error': float(force_error),
                'rebound_ratio': records[-1]['velocity_m_s'] / reference['speed-m-s'],
                'maximum_sampled_relative_energy_drift': max(abs(row['energy_J'] / reference['initial-energy-J'] - 1)
                                                            for row in records),
                'maximum_momentum_error_N_s': max(abs(row['momentum_error_N_s']) for row in records),
                'trace': records}


def verify_rod(study_path, output, refinements, semidiscrete=False, restriction=None, trace_degree=1):
    if restriction not in {None, 'boundary-star', 'continuous-velocity'}:
        raise ValueError(f'Unknown approximation-space restriction: {restriction}')
    if trace_degree not in {1, 2} or (restriction and trace_degree != 1):
        raise ValueError('Quadratic traces cannot be combined with a linear-trace restriction')
    native = json.loads(study_path.read_text())
    assert native['status'] == 'completed' and native['cases']
    assert native['options']['benchmark'] == 'mixed-rod-impact'
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open('x'):
        pass
    report = {'status': 'running', 'scope': 'independent small-strain 3D mixed contact; not continuum qualification',
              'numpy_version': np.__version__, 'scipy_version': scipy.__version__,
              'native_input_sha256': hashlib.sha256(study_path.read_bytes()).hexdigest(),
              'source_sha256': {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in
                               [Path(__file__), Path('tools/verify.py')]}, 'cases': []}
    alternative = restriction is not None or any(case['settings'].get('trace-degree', 1) != trace_degree
                                                for case in native['cases'])
    if alternative:
        report.update(scope='alternative linear space differs from the supplied native study; not a native comparison',
                      restriction=restriction, trace_degree=trace_degree)
    try:
        for case in native['cases']:
            assert case['status'] == 'complete'
            assert case['reference']['strain-scale'] <= .001
            model = LinearMixedRod(case, trace_degree)
            restriction_audit = None
            if restriction == 'boundary-star':
                restriction_audit = model.localize_enrichment()
            elif restriction == 'continuous-velocity':
                restriction_audit = model.share_projected_velocity()
            for refinement in refinements:
                result = model.run(case, refinement)
                result['divisions'] = case['settings']['divisions']
                result['steps_per_contact'] = case['settings']['steps-per-contact'] * refinement
                comparison = sum((row['time_s'] - row['begin_s']) * abs(row['force_N'] - sample['average-contact-force-N'])
                                 for row, sample in zip(result['trace'], case['samples']))
                result['native_force_relative_L1_difference'] = float(comparison / case['reference']['total-contact-impulse-N-s'])
                if restriction_audit:
                    result['restriction_audit'] = restriction_audit
                if refinement == 1 and not alternative:
                    # The independent linearization should agree at this strain
                    # scale; it is not an exact nonlinear reference.
                    result['native_force_within_0_5_percent'] = result['native_force_relative_L1_difference'] < .005
                report['cases'].append(result)
                output.write_text(json.dumps(report, indent=2) + '\n')
                print(json.dumps({key: value for key, value in result.items() if key != 'trace'}), flush=True)
            if semidiscrete:
                for tolerance in [1e-9, 1e-11]:
                    result = model.run_semidiscrete(case, tolerance)
                    result['divisions'] = case['settings']['divisions']
                    if restriction_audit:
                        result['restriction_audit'] = restriction_audit
                    report['cases'].append(result)
                    output.write_text(json.dumps(report, indent=2) + '\n')
                    print(json.dumps({key: value for key, value in result.items() if key != 'trace'}), flush=True)
                coarse, fine = report['cases'][-2:]
                assert abs(coarse['force_relative_L1_error'] - fine['force_relative_L1_error']) < 1e-4
                assert abs(coarse['rebound_ratio'] - fine['rebound_ratio']) < 1e-5
                assert fine['maximum_sampled_relative_energy_drift'] < 1e-5
        report['status'] = ('diagnostic-complete' if alternative else
                            'verified' if all(case.get('native_force_within_0_5_percent', True)
                                              for case in report['cases']) else 'comparison-failed')
    except Exception as error:
        report.update(status='failed', error=repr(error))
        raise
    finally:
        output.write_text(json.dumps(report, indent=2) + '\n')
    return report['status'] in {'verified', 'diagnostic-complete'}


def mixed_main(argv=None):
    parser = argparse.ArgumentParser()
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument('--prepare-mode', action='store_true')
    modes.add_argument('--verify_solver-mode', action='store_true')
    modes.add_argument('--rod-study', type=Path)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--refinements', type=int, nargs='+', default=[1, 2, 4])
    parser.add_argument('--semidiscrete', action='store_true')
    parser.add_argument('--restriction', choices=['boundary-star', 'continuous-velocity'],
                        help='Diagnose an alternative linear space; never qualifies native results')
    parser.add_argument('--trace-degree', type=int, choices=[1, 2], default=1,
                        help='Two selects experimental quadratic Bernstein traces with P1 projected inertia')
    args = parser.parse_args(argv)
    if (args.restriction or args.trace_degree != 1) and not args.rod_study:
        parser.error('Approximation-space alternatives require --rod-study')
    if args.rod_study:
        if not args.output or any(value < 1 or value > 32 for value in args.refinements):
            parser.error('Rod verification needs a new --output and refinements from 1 to 32')
        if not verify_rod(args.rod_study, args.output, args.refinements, args.semidiscrete, args.restriction, args.trace_degree):
            raise SystemExit(1)
    elif args.prepare_mode:
        prepare_mode()
    elif args.verify_mode:
        verify_mode()
    else:
        verify_solver()


class Rod:
    def __init__(self, cells, formulation, clearance_m=2.5e-7):
        self.cells = cells
        self.formulation = formulation
        self.length = 0.1
        self.area = 0.02 ** 2
        self.young = 1e6
        self.density = 1000.0
        self.speed = 0.01
        self.gap = 2e-6
        self.wave_speed = np.sqrt(self.young / self.density)
        self.time_scale = self.length / self.wave_speed
        self.displacement_scale = self.speed * self.time_scale
        self.mass_scale = self.density * self.area * self.length
        self.force_scale = self.density * self.wave_speed * self.speed * self.area
        self.energy_scale = self.mass_scale * self.speed ** 2
        self.clearance = clearance_m / self.displacement_scale
        self.pressure_ratio = 1000.0 / (self.density * self.wave_speed * self.speed)
        self.impact_time = self.gap / self.displacement_scale
        self.end_time = self.impact_time + 3.0
        self.massless = formulation.startswith('redistributed')
        self.hard_contact = formulation == 'redistributed-signorini'
        self.consistent = formulation == 'consistent-barrier'
        weights = np.ones(cells)
        if self.massless:
            weights[:2] = [0.0, 2.0]
        matrix = np.zeros((cells + 1, cells + 1))
        for element, weight in enumerate(weights):
            matrix[element:element + 2, element:element + 2] += weight / (6 * cells) * np.array([[2, 1], [1, 2]])
        if not self.consistent:
            matrix = np.diag(matrix.sum(axis=1))
        self.mass = matrix[1:, 1:] if self.massless else matrix
        self.size = len(self.mass)
        self.nodal_mass = self.mass.sum(axis=1)
        self.factor = cho_factor(self.mass) if self.consistent else None
        assert abs(self.mass.sum() - 1.0) < 1e-14
        assert np.all(self.nodal_mass > 0)

    def barrier(self, gap):
        """Dimensionless energy and repulsion: IPC physical squared-distance law."""
        if gap >= self.clearance:
            return 0.0, 0.0
        if gap <= 0:
            raise ValueError('The logarithmic barrier requires positive gap')
        ratio = gap / self.clearance
        square = ratio * ratio
        delta = square - 1.0
        log_square = 2.0 * np.log(ratio)
        energy = -self.pressure_ratio * self.clearance * delta ** 2 * log_square
        force = self.pressure_ratio * (4.0 * ratio * delta * log_square + 2.0 * delta ** 2 / ratio)
        return energy, force

    def boundary(self, neighbor_displacement):
        if self.hard_contact:
            gap = max(neighbor_displacement, 0.0)
            return gap, self.cells * (gap - neighbor_displacement)
        if neighbor_displacement >= self.clearance:
            return neighbor_displacement, 0.0
        # Static balance at the massless end: barrier force = spring compression.
        def balance(gap):
            return self.barrier(gap)[1] - self.cells * (gap - neighbor_displacement)
        gap = brentq(balance, self.clearance * 1e-12, self.clearance,
                     xtol=self.clearance * 1e-13, rtol=1e-14)
        return gap, self.barrier(gap)[1]

    def unpack(self, state):
        displacement = state[:self.size].copy()
        velocity = state[self.size:2 * self.size]
        if self.massless:
            gap, contact = self.boundary(displacement[0])
            displacement = np.concatenate(([gap], displacement))
        else:
            # A logarithmic coordinate keeps all trial boundary gaps positive.
            displacement[0] = self.clearance * np.exp(displacement[0])
            gap = displacement[0]
            contact = self.barrier(gap)[1]
        return displacement, velocity, gap, contact

    def derivative(self, reference_force):
        def evaluate(t, state):
            displacement, velocity, gap, contact = self.unpack(state)
            stress = self.cells * np.diff(displacement)
            force = np.zeros(self.cells + 1)
            force[:-1] += stress
            force[1:] -= stress
            force[0] += contact
            dynamic_force = force[1:] if self.massless else force
            acceleration = (cho_solve(self.factor, dynamic_force, check_finite=False)
                            if self.consistent else dynamic_force / self.nodal_mass)
            rate = velocity.copy()
            if not self.massless:
                rate[0] /= gap
            return np.concatenate((rate, acceleration, [contact, abs(contact - reference_force)]))
        return evaluate

    def energy(self, state):
        displacement, velocity, gap, _ = self.unpack(state)
        elastic = 0.5 * self.cells * np.dot(np.diff(displacement), np.diff(displacement))
        kinetic = 0.5 * velocity @ self.mass @ velocity
        barrier = 0.0 if self.hard_contact else self.barrier(gap)[0]
        return elastic + kinetic + barrier

    def initial(self):
        displacement = np.full(self.size, self.impact_time)
        if not self.massless:
            displacement[0] = np.log(self.impact_time / self.clearance)
        return np.concatenate((displacement, -np.ones(self.size), [0.0, 0.0]))

    def run(self, relative_tolerance, steps_per_cell, samples=4096):
        started = time.perf_counter()
        initial = self.initial()
        state = initial.copy()
        intervals = [0.0, self.impact_time, self.impact_time + 2.0, self.end_time]
        records = []
        accepted = 0
        evaluations = 0
        for begin, end, reference_force in zip(intervals, intervals[1:], [0.0, 1.0, 0.0]):
            solution = solve_ivp(self.derivative(reference_force), (begin, end), state,
                                 method='DOP853', rtol=relative_tolerance,
                                 atol=relative_tolerance * 0.01,
                                 max_step=1.0 / (steps_per_cell * self.cells), dense_output=True)
            if not solution.success or solution.t[-1] != end:
                raise RuntimeError(solution.message)
            accepted += len(solution.t) - 1
            evaluations += solution.nfev
            grid = np.linspace(begin, end, max(2, int(samples * (end - begin) / self.end_time)))
            # Include adaptive endpoints in diagnostics as well as a uniform grid.
            times = np.unique(np.concatenate((grid, solution.t)))
            for timestamp, value in zip(times, solution.sol(times).T):
                _, velocity, gap, contact = self.unpack(value)
                records.append([float(timestamp * self.time_scale), float(contact),
                                float(self.nodal_mass @ velocity), float(gap * self.displacement_scale),
                                float(self.energy(value) / self.energy(initial))])
            state = solution.y[:, -1]
        trace = np.array(records)
        _, velocity, gap, _ = self.unpack(state)
        impulse = state[-2]
        momentum = self.nodal_mass @ velocity
        return {
            'formulation': self.formulation, 'cells': self.cells,
            'relative_tolerance': relative_tolerance, 'steps_per_cell': steps_per_cell,
            'total_mass_kg': float(self.mass.sum() * self.mass_scale),
            'force_relative_L1_error': float(state[-1] / 2.0),
            'rebound_speed_ratio': float(momentum / self.mass.sum()),
            'impulse_relative_error': float(impulse / 2.0 - 1.0),
            'momentum_balance_error_N_s': float((momentum + self.mass.sum() - impulse) * self.mass_scale * self.speed),
            'maximum_sampled_force_ratio': float(trace[:, 1].max()),
            'minimum_sampled_gap_m': float(trace[:, 3].min()),
            'maximum_sampled_relative_energy_drift': float(np.max(np.abs(trace[:, 4] - 1.0))),
            'accepted_steps': accepted, 'rhs_evaluations': evaluations,
            'wall_seconds': time.perf_counter() - started,
            'trace_columns': ['time_s', 'force_ratio', 'velocity_ratio', 'gap_m', 'energy_ratio'],
            'trace': trace.tolist(),
        }


def checks():
    rod = Rod(32, 'redistributed-barrier')
    for ratio in [0.1, 0.3, 0.7, 0.95]:
        gap = rod.clearance * ratio
        epsilon = rod.clearance * 1e-6
        numerical = -(rod.barrier(gap + epsilon)[0] - rod.barrier(gap - epsilon)[0]) / (2 * epsilon)
        assert np.isclose(numerical, rod.barrier(gap)[1], rtol=1e-8)
    for displacement in [-1.0, -rod.clearance, 0.0, 0.5 * rod.clearance, 2 * rod.clearance]:
        gap, contact = rod.boundary(displacement)
        assert gap > 0 and contact >= 0
        assert abs(contact - rod.cells * (gap - displacement)) < 1e-10
    for cells in [4, 8, 32]:
        for formulation in ['lumped-barrier', 'consistent-barrier', 'redistributed-barrier', 'redistributed-signorini']:
            instance = Rod(cells, formulation)
            assert np.isclose(instance.energy(instance.initial()), 0.5, atol=1e-14)
            assert np.isclose(instance.mass.sum(), 1.0, atol=1e-14)
            before_contact = instance.derivative(0.0)(0.0, instance.initial())
            assert np.max(np.abs(before_contact[instance.size:2 * instance.size])) < 1e-11
            # The reduced force must remain the negative gradient of the total
            # potential after eliminating the massless boundary (envelope rule).
            state = instance.initial()
            state[:instance.size] += np.linspace(-0.01, 0.02, instance.size)
            if not instance.massless:
                state[0] = np.log(0.7)
            _, _, gap, _ = instance.unpack(state)
            acceleration = instance.derivative(0.0)(0.0, state)[instance.size:2 * instance.size]
            force = instance.mass @ acceleration
            for index in range(instance.size):
                plus, minus = state.copy(), state.copy()
                epsilon = 1e-7
                plus[index] += epsilon
                minus[index] -= epsilon
                gradient = (instance.energy(plus) - instance.energy(minus)) / (2 * epsilon)
                coordinate_scale = gap if index == 0 and not instance.massless else 1.0
                assert abs(gradient + force[index] * coordinate_scale) < 2e-8
        # Analytic free/free linear bar eigenvalues for uniform P1 elements.
        stiffness = np.zeros((cells + 1, cells + 1))
        for element in range(cells):
            stiffness[element:element + 2, element:element + 2] += cells * np.array([[1, -1], [-1, 1]])
        angles = np.arange(cells + 1) * np.pi / cells
        for formulation in ['lumped-barrier', 'consistent-barrier']:
            instance = Rod(cells, formulation)
            measured = eigh(stiffness, instance.mass, eigvals_only=True)
            expected = (4 * cells ** 2 * np.sin(0.5 * angles) ** 2
                        if not instance.consistent else 6 * cells ** 2 * (1 - np.cos(angles)) / (2 + np.cos(angles)))
            assert np.allclose(measured, expected, atol=1e-9, rtol=1e-12)
    return {'mass_and_free_translation': 'passed', 'barrier_gradient': 'passed',
            'massless_static_balance': 'passed', 'reduced_energy_gradient': 'passed',
            'free_bar_eigenvalues': 'passed'}


def compare_refinements(coarse, fine):
    a, b = np.array(coarse['trace']), np.array(fine['trace'])
    _, ia, ib = np.intersect1d(a[:, 0], b[:, 0], return_indices=True)
    comparison = {
        'cells': fine['cells'], 'formulation': fine['formulation'],
        'force_L1_difference': abs(coarse['force_relative_L1_error'] - fine['force_relative_L1_error']),
        'rebound_ratio_difference': abs(coarse['rebound_speed_ratio'] - fine['rebound_speed_ratio']),
        'common_trace_times': len(ia),
        'maximum_common_force_ratio_difference': float(np.max(np.abs(a[ia, 1] - b[ib, 1]))),
        'maximum_common_velocity_ratio_difference': float(np.max(np.abs(a[ia, 2] - b[ib, 2]))),
    }
    assert comparison['common_trace_times'] >= 4000
    assert comparison['force_L1_difference'] < 1e-6
    assert comparison['rebound_ratio_difference'] < 1e-7
    assert comparison['maximum_common_force_ratio_difference'] < 1e-3
    assert comparison['maximum_common_velocity_ratio_difference'] < 1e-6
    assert fine['maximum_sampled_relative_energy_drift'] < 1e-6
    assert abs(fine['momentum_balance_error_N_s']) < 1e-12
    return comparison


def rod_main(argv=None):
    parser = argparse.ArgumentParser(description="Independent 1D FEM/contact oracle; never imports or modifies Pitoco's solver.")
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--cells', type=int, nargs='+', default=[32])
    parser.add_argument('--formulations', nargs='+', default=['lumped-barrier', 'redistributed-barrier', 'redistributed-signorini'],
                        choices=['lumped-barrier', 'consistent-barrier', 'redistributed-barrier', 'redistributed-signorini'])
    args = parser.parse_args(argv)
    if any(n < 3 or n > 256 for n in args.cells):
        parser.error('Use 3 through 256 cells')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open('x'):
        pass
    evidence = {'format': 'pitoco-independent-rod-v1', 'status': 'running',
                'source_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                'numpy_version': np.__version__, 'scipy_version': scipy.__version__,
                'checks': {}, 'cases': [], 'numerical_refinement': []}
    try:
        evidence['checks'] = checks()
        for cells in args.cells:
            for formulation in args.formulations:
                for tolerance, steps in [(1e-12, 64), (2.5e-14, 128)]:
                    result = Rod(cells, formulation).run(tolerance, steps)
                    evidence['cases'].append(result)
                    args.output.write_text(json.dumps(evidence, indent=2))
                    print(json.dumps({key: value for key, value in result.items() if key != 'trace'}), flush=True)
                evidence['numerical_refinement'].append(compare_refinements(*evidence['cases'][-2:]))
        evidence['status'] = 'completed'
    except Exception as error:
        evidence['status'] = 'failed'
        evidence['error'] = repr(error)
        raise
    finally:
        args.output.write_text(json.dumps(evidence, indent=2))
