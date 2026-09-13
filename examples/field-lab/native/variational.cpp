// Scientific contact and sparse linear algebra backend. The time integrator and
// FEM assembly are authored in AguaFria Zig; this ABI has no JVM callbacks.
#include <ipc/ipc.hpp>
#include <ipc/collisions/normal/normal_collisions.hpp>
#include <ipc/potentials/barrier_potential.hpp>
#include <ipc/potentials/friction_potential.hpp>
#include <Eigen/SparseCholesky>
#include "geometry.h"
#include <cstdint>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#define PITOCO_API extern "C" __attribute__((visibility("default")))

namespace {
using Rows = Eigen::Matrix<double, Eigen::Dynamic, 3, Eigen::RowMajor>;
using Sparse = Eigen::SparseMatrix<double>;
using Block = Eigen::Matrix<double, 12, 12, Eigen::RowMajor>;
thread_local std::string last_error;

struct Context {
    Eigen::MatrixXd rest;
    ipc::CollisionMesh mesh;
    ipc::NormalCollisions collisions;
    Sparse contact_hessian;
    std::vector<Eigen::Triplet<double>> entries;
    double acceleration_scale = 1.0;
    double clearance = 0.0;
    Eigen::MatrixXd step_start;
    Eigen::VectorXd friction;
    ipc::TangentialCollisions tangential;
    double slip_threshold = 1e-8;
    bool has_friction = false;
    std::vector<std::array<uint32_t, 4>> tetrahedra;
    std::vector<uint8_t> floor;

    Context(Eigen::MatrixXd vertices, const Eigen::MatrixXi& edges, const Eigen::MatrixXi& faces)
        : rest(std::move(vertices)), mesh(ipc::CollisionMesh::build_from_full_mesh(rest, edges, faces))
    {
        collisions.set_use_area_weighting(true);
        collisions.set_collision_set_type(ipc::NormalCollisions::CollisionSetType::IMPROVED_MAX_APPROX);
    }

    Eigen::MatrixXd positions(const double* data) const
    {
        if (!data) throw std::invalid_argument("Missing positions");
        Eigen::MatrixXd result = Eigen::Map<const Rows>(data, rest.rows(), 3);
        if (!result.allFinite()) throw std::invalid_argument("Non-finite positions");
        return result;
    }
};

Context& context(void* handle)
{
    if (!handle) throw std::invalid_argument("Missing variational context");
    return *static_cast<Context*>(handle);
}

bool positive_tet_path(const Context& state, const Eigen::MatrixXd& start, const Eigen::MatrixXd& end);

void build_contacts(Context& state, const Eigen::MatrixXd& vertices, double clearance)
{
    state.clearance = clearance;
    state.collisions.build(state.mesh, vertices, clearance);
    for (int i = 0; i < vertices.rows(); ++i) {
        if (!state.floor[state.mesh.to_full_vertex_id(i)]) continue;
        if (vertices(i, 1) <= 0.0) throw std::runtime_error("Vertex is not above the ground barrier");
        if (vertices(i, 1) < clearance)
            state.collisions.pv_collisions.emplace_back(
                Eigen::Hyperplane<double, 3>(Eigen::Vector3d::UnitY(), 0.0), i,
                state.mesh.vertex_area(i), Eigen::SparseVector<double>(vertices.size()));
    }
}

void update_friction(Context& state, const Eigen::MatrixXd& vertices, double clearance, double pressure)
{
    build_contacts(state, vertices, clearance);
    state.tangential.clear();
    if (state.has_friction)
        state.tangential.build(state.mesh, vertices, state.collisions,
                               ipc::BarrierPotential(clearance, pressure, true), state.friction, state.friction,
                               [](double a, double b) { return std::min(a, b); });
}
}

PITOCO_API const char* pitoco_aguafria_variational_error() noexcept { return last_error.c_str(); }

PITOCO_API void* pitoco_aguafria_variational_create(uint32_t nodes, const double* rest, const double* initial,
                                          uint32_t face_count, const uint32_t* indices,
                                          uint32_t tet_count, const uint32_t* cells, const uint8_t* floor) noexcept
{
    try {
        if (!nodes || !rest || !face_count || !indices || nodes > 1000000 || face_count > 2000000)
            throw std::invalid_argument("Invalid collision mesh dimensions");
        Eigen::MatrixXd vertices = Eigen::Map<const Rows>(rest, nodes, 3);
        if (!vertices.allFinite()) throw std::invalid_argument("Non-finite reference mesh");
        Eigen::MatrixXi faces(face_count, 3);
        std::vector<uint64_t> edge_keys(3 * face_count);
        const uint32_t edge_count = pitoco_geometry_edges(nodes, face_count, indices, edge_keys.data());
        if (!edge_count) throw std::invalid_argument("Invalid face index");
        for (uint32_t f = 0; f < face_count; ++f)
            for (int j = 0; j < 3; ++j) faces(f, j) = indices[3 * f + j];
        Eigen::MatrixXi edges(edge_count, 2);
        for (uint32_t row = 0; row < edge_count; ++row) {
            edges(row, 0) = static_cast<int>(edge_keys[row] >> 32);
            edges(row, 1) = static_cast<int>(edge_keys[row] & 0xffffffffu);
        }
        auto result = std::make_unique<Context>(vertices, edges, faces);
        if ((tet_count && !cells) || tet_count > 4000000) throw std::invalid_argument("Invalid tetrahedra");
        result->floor.resize(nodes, 0);
        if (floor) std::copy(floor, floor + nodes, result->floor.begin());
        for (uint32_t i = 0; i < tet_count; ++i) {
            std::array<uint32_t, 4> tet;
            for (int j = 0; j < 4; ++j) {
                if (cells[4 * i + j] >= nodes) throw std::invalid_argument("Invalid tetrahedron index");
                tet[j] = cells[4 * i + j];
            }
            result->tetrahedra.push_back(tet);
        }
        const Eigen::MatrixXd current = result->positions(initial);
        if (!positive_tet_path(*result, current, current))
            throw std::invalid_argument("Initial elements must have positive volume and floor vertices must be above ground");
        if (ipc::has_intersections(result->mesh, result->mesh.vertices(current)))
            throw std::invalid_argument("Initial surface intersections");
        last_error.clear();
        return result.release();
    } catch (const std::exception& error) { last_error = error.what(); return nullptr; }
    catch (...) { last_error = "Unknown variational allocation failure"; return nullptr; }
}

PITOCO_API void pitoco_aguafria_variational_destroy(void* handle) noexcept { delete static_cast<Context*>(handle); }

PITOCO_API uint32_t pitoco_aguafria_variational_evaluate(void* handle, const double* positions,
                                               double clearance, double pressure, uint32_t derivatives,
                                               double* gradient, double* energy, double* friction_energy) noexcept
{
    try {
        auto& state = context(handle);
        if (!energy || !friction_energy || derivatives > 2 || (derivatives && !gradient)
            || !std::isfinite(clearance) || clearance <= 0.0
            || !std::isfinite(pressure) || pressure <= 0.0)
            throw std::invalid_argument("Invalid barrier evaluation parameters");
        const Eigen::MatrixXd vertices = state.mesh.vertices(state.positions(positions));
        build_contacts(state, vertices, clearance);
        const ipc::BarrierPotential potential(clearance, pressure, true);
        const ipc::FrictionPotential dissipation(state.slip_threshold);
        Eigen::MatrixXd displacement = Eigen::MatrixXd::Zero(vertices.rows(), 3);
        if (state.has_friction) displacement = vertices - state.step_start;
        *friction_energy = dissipation(state.tangential, state.mesh, displacement);
        *energy = potential(state.collisions, state.mesh, vertices);
        if (!std::isfinite(*energy) || !std::isfinite(*friction_energy)) throw std::runtime_error("Non-finite barrier energy");
        if (derivatives) {
            const Eigen::VectorXd full = state.mesh.to_full_dof(
                potential.gradient(state.collisions, state.mesh, vertices)
                + dissipation.gradient(state.tangential, state.mesh, displacement));
            if (!full.allFinite()) throw std::runtime_error("Non-finite barrier gradient");
            Eigen::Map<Eigen::VectorXd>(gradient, full.size()) = full;
        }
        if (derivatives == 2)
            state.contact_hessian = state.mesh.to_full_dof(potential.hessian(
                state.collisions, state.mesh, vertices, ipc::PSDProjectionMethod::CLAMP)
                + dissipation.hessian(state.tangential, state.mesh, displacement, ipc::PSDProjectionMethod::CLAMP));
        return 0;
    } catch (const std::exception& error) { last_error = error.what(); return 1; }
    catch (...) { last_error = "Unknown barrier evaluation failure"; return 1; }
}

PITOCO_API uint32_t pitoco_aguafria_variational_begin_step(void* handle, const double* start, const double* coefficients,
                                                double duration, double clearance, double pressure) noexcept
{
    try {
        auto& state = context(handle);
        if (!coefficients || !std::isfinite(duration) || duration <= 0.0
            || !std::isfinite(clearance) || clearance <= 0.0 || !std::isfinite(pressure) || pressure <= 0.0)
            throw std::invalid_argument("Invalid friction step parameters");
        state.has_friction = false;
        state.step_start = state.mesh.vertices(state.positions(start));
        state.slip_threshold = duration * 1e-5; // Metres: epsilon_v = 10 micrometres/second.
        state.friction.resize(state.mesh.num_vertices());
        for (int i = 0; i < state.friction.size(); ++i) {
            const double coefficient = coefficients[state.mesh.to_full_vertex_id(i)];
            if (!std::isfinite(coefficient) || coefficient < 0.0)
                throw std::invalid_argument("Invalid friction coefficient");
            state.friction[i] = coefficient;
            state.has_friction = state.has_friction || coefficient > 0.0;
        }
        update_friction(state, state.step_start, clearance, pressure);
        return 0;
    } catch (const std::exception& error) { last_error = error.what(); return 1; }
    catch (...) { last_error = "Unknown friction step failure"; return 1; }
}

PITOCO_API uint32_t pitoco_aguafria_variational_lag_friction(void* handle, const double* positions,
                                                  double clearance, double pressure) noexcept
{
    try {
        auto& state = context(handle);
        if (!std::isfinite(clearance) || clearance <= 0.0 || !std::isfinite(pressure) || pressure <= 0.0)
            throw std::invalid_argument("Invalid friction lag parameters");
        update_friction(state, state.mesh.vertices(state.positions(positions)), clearance, pressure);
        return 0;
    } catch (const std::exception& error) { last_error = error.what(); return 1; }
    catch (...) { last_error = "Unknown friction lag failure"; return 1; }
}

PITOCO_API uint32_t pitoco_aguafria_variational_matrix_begin(void* handle, const double* masses, double scale) noexcept
{
    try {
        auto& state = context(handle);
        if (!masses || !std::isfinite(scale) || scale <= 0.0) throw std::invalid_argument("Invalid matrix scale");
        state.entries.clear();
        state.acceleration_scale = scale;
        for (int i = 0; i < state.rest.rows(); ++i) {
            if (!std::isfinite(masses[i]) || masses[i] <= 0.0) throw std::invalid_argument("Invalid nodal mass");
            for (int j = 0; j < 3; ++j) state.entries.emplace_back(3 * i + j, 3 * i + j, masses[i]);
        }
        return 0;
    } catch (const std::exception& error) { last_error = error.what(); return 1; }
    catch (...) { last_error = "Unknown matrix allocation failure"; return 1; }
}

PITOCO_API uint32_t pitoco_aguafria_variational_add_projected_element(void* handle, const uint32_t* nodes, const double* values) noexcept
{
    try {
        auto& state = context(handle);
        if (!nodes || !values) throw std::invalid_argument("Missing element block");
        for (int i = 0; i < 4; ++i)
            if (nodes[i] >= state.rest.rows()) throw std::invalid_argument("Invalid element node");
        const Block block = Eigen::Map<const Block>(values);
        if (!block.allFinite()) throw std::invalid_argument("Non-finite element tangent");
        // AguaFria supplies the projected block. This adapter only inserts it
        // into Eigen's sparse triplet container for the external sparse solver.
        for (int i = 0; i < 12; ++i)
            for (int j = 0; j < 12; ++j)
                state.entries.emplace_back(3 * nodes[i / 3] + i % 3, 3 * nodes[j / 3] + j % 3,
                                            state.acceleration_scale * block(i, j));
        return 0;
    } catch (const std::exception& error) { last_error = error.what(); return 1; }
    catch (...) { last_error = "Unknown element assembly failure"; return 1; }
}

PITOCO_API uint32_t pitoco_aguafria_variational_solve(void* handle, const double* gradient, double* direction) noexcept
{
    try {
        auto& state = context(handle);
        if (!gradient || !direction) throw std::invalid_argument("Missing Newton vectors");
        const int count = 3 * state.rest.rows();
        Sparse matrix(count, count);
        matrix.setFromTriplets(state.entries.begin(), state.entries.end());
        matrix += state.acceleration_scale * state.contact_hessian;
        const Eigen::VectorXd rhs = -Eigen::Map<const Eigen::VectorXd>(gradient, count);
        if (!rhs.allFinite()) throw std::invalid_argument("Non-finite Newton gradient");
        Eigen::SimplicialLDLT<Sparse> factor(matrix);
        if (factor.info() != Eigen::Success || factor.vectorD().minCoeff() <= 0.0)
            throw std::runtime_error("Newton factorization is not positive definite");
        const Eigen::VectorXd solution = factor.solve(rhs);
        if (factor.info() != Eigen::Success || !solution.allFinite()) throw std::runtime_error("Newton solve failed");
        const Eigen::VectorXd residual = matrix * solution - rhs;
        const double scale = (matrix.cwiseAbs() * solution.cwiseAbs() + rhs.cwiseAbs()).maxCoeff();
        if (residual.cwiseAbs().maxCoeff() > 1e-10 * scale)
            throw std::runtime_error("Newton solve failed its backward-error check");
        Eigen::Map<Eigen::VectorXd>(direction, count) = solution;
        return 0;
    } catch (const std::exception& error) { last_error = error.what(); return 1; }
    catch (...) { last_error = "Unknown Newton solve failure"; return 1; }
}

namespace {
Eigen::MatrixXd trial_positions(const Context& state, const Eigen::MatrixXd& start,
                               const Eigen::MatrixXd& direction, double alpha)
{
    Eigen::MatrixXd result(start.rows(), 3);
    pitoco_geometry_trial(static_cast<uint32_t>(result.size()), state.rest.data(),
                           start.data(), direction.data(), alpha, result.data());
    return result;
}

bool positive_tet_path(const Context& state, const Eigen::MatrixXd& start, const Eigen::MatrixXd& end)
{
    return pitoco_geometry_positive_path(static_cast<uint32_t>(start.rows()),
        static_cast<uint32_t>(state.tetrahedra.size()),
        state.tetrahedra.empty() ? nullptr : state.tetrahedra.front().data(),
        state.floor.data(), start.data(), end.data()) != 0;
}
}

PITOCO_API double pitoco_aguafria_variational_safe_step(void* handle, const double* start, const double* direction) noexcept
{
    try {
        auto& state = context(handle);
        const Eigen::MatrixXd before = state.positions(start);
        const Eigen::MatrixXd displacement = state.positions(direction);
        const Eigen::MatrixXd end = trial_positions(state, before, displacement, 1.0);
        const Eigen::MatrixXd surface = state.mesh.vertices(before);
        // Keep Newton candidates away from the barrier singularity, including
        // trajectories that nearly touch without crossing. This fraction is
        // recomputed at every iterate; it does not impose a fixed physical gap.
        const double distance = std::sqrt(state.collisions.compute_minimum_distance(state.mesh, surface));
        const double minimum_distance = 0.2 * std::min(state.clearance, distance);
        double alpha = ipc::compute_collision_free_stepsize(
            state.mesh, surface, state.mesh.vertices(end), minimum_distance);
        Eigen::MatrixXd scratch(before.rows(), 3);
        return pitoco_geometry_safe_fraction(static_cast<uint32_t>(before.rows()),
            static_cast<uint32_t>(state.tetrahedra.size()),
            state.tetrahedra.empty() ? nullptr : state.tetrahedra.front().data(),
            state.floor.data(), state.rest.data(), before.data(), displacement.data(), alpha, scratch.data());
    } catch (const std::exception& error) { last_error = error.what(); }
    catch (...) { last_error = "Unknown collision line-search failure"; }
    return std::numeric_limits<double>::quiet_NaN();
}

PITOCO_API uint32_t pitoco_aguafria_variational_trial(void* handle, const double* start, const double* direction,
                                            double alpha, double* output) noexcept
{
    try {
        auto& state = context(handle);
        if (!output || !std::isfinite(alpha) || alpha <= 0.0 || alpha > 1.0)
            throw std::invalid_argument("Invalid line-search fraction");
        const Eigen::MatrixXd before = state.positions(start);
        const Eigen::MatrixXd trial = trial_positions(state, before, state.positions(direction), alpha);
        if (!positive_tet_path(state, before, trial)
            || !ipc::is_step_collision_free(state.mesh, state.mesh.vertices(before), state.mesh.vertices(trial))) return 1;
        Eigen::Map<Rows>(output, state.rest.rows(), 3) = trial;
        return 0;
    } catch (const std::exception& error) { last_error = error.what(); return 2; }
    catch (...) { last_error = "Unknown candidate-path failure"; return 2; }
}
