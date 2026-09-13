#ifndef PITOCO_GEOMETRY_H
#define PITOCO_GEOMETRY_H
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
/* Implemented in src/field_lab/geometry.clj, compiled by AguaFria Zig. */
typedef struct PitocoCCDPoint { double x, y, z; } PitocoCCDPoint;
uint32_t pitoco_geometry_ccd_prepare(uint32_t kind, const PitocoCCDPoint* before,
    const PitocoCCDPoint* after, double separation, double tolerance,
    double maximum_time, uint32_t maximum_iterations, double* error_bound);
uint32_t pitoco_geometry_positive_path(uint32_t nodes, uint32_t tetrahedra,
    const uint32_t* cells, const uint8_t* floor, const double* start, const double* end);
void pitoco_geometry_trial(uint32_t count, const double* rest, const double* start,
    const double* direction, double alpha, double* output);
double pitoco_geometry_safe_fraction(uint32_t nodes, uint32_t tetrahedra,
    const uint32_t* cells, const uint8_t* floor, const double* rest,
    const double* start, const double* direction, double surface_fraction, double* scratch);
uint32_t pitoco_geometry_edges(uint32_t nodes, uint32_t faces, const uint32_t* indices, uint64_t* keys);
#ifdef __cplusplus
}
#endif
#endif
