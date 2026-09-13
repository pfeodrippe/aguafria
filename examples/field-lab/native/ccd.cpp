// Thin C ABI adapter for the pinned Tight-Inclusion C++ library.
// Validation, error bounds and geometric certificates are AguaFria Zig.
#include <tight_inclusion/ccd.hpp>
#include "geometry.h"
#include <cmath>
#include <limits>

struct PitocoCCDResult {
    uint32_t status, reserved;
    double time, achieved_tolerance;
};

extern "C" void pitoco_aguafria_ccd_query(
    uint32_t kind, const PitocoCCDPoint* start, const PitocoCCDPoint* end,
    double separation, double tolerance, double maximum_time,
    uint32_t maximum_iterations, PitocoCCDResult* result) noexcept
{
    if (!result) return;
    *result = {2, 0, 0.0, 0.0};
    double error_bound[3];
    const uint32_t preparation = pitoco_geometry_ccd_prepare(kind, start, end,
        separation, tolerance, maximum_time, maximum_iterations, error_bound);
    if (preparation == 3) return;
    if (preparation != 0) {
        *result = {0, preparation, std::numeric_limits<double>::infinity(), 0.0};
        return;
    }
    ticcd::Vector3 before[4], after[4];
    for (int i = 0; i < 4; ++i) {
        before[i] = ticcd::Vector3(start[i].x, start[i].y, start[i].z);
        after[i] = ticcd::Vector3(end[i].x, end[i].y, end[i].z);
    }
    try {
        double toi = std::numeric_limits<double>::infinity();
        double achieved = tolerance;
        const ticcd::Array3 error(error_bound[0], error_bound[1], error_bound[2]);
        const auto method = ticcd::CCDRootFindingMethod::BREADTH_FIRST_SEARCH;
        const bool possible = kind == 0
            ? ticcd::vertexFaceCCD(before[0], before[1], before[2], before[3],
                                  after[0], after[1], after[2], after[3], error,
                                  separation, toi, tolerance, maximum_time,
                                  maximum_iterations, achieved, false, method)
            : ticcd::edgeEdgeCCD(before[0], before[1], before[2], before[3],
                                after[0], after[1], after[2], after[3], error,
                                separation, toi, tolerance, maximum_time,
                                maximum_iterations, achieved, false, method);
        if (possible && (!std::isfinite(toi) || toi < 0.0 || toi > maximum_time
                         || !std::isfinite(achieved) || achieved < 0.0)) {
            result->status = 3;
            return;
        }
        *result = {possible ? 1u : 0u, 0, possible ? toi : std::numeric_limits<double>::infinity(),
                   possible ? achieved : 0.0};
    } catch (...) {
        result->status = 3;
    }
}
