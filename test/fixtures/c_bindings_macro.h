typedef struct native_point { int x; int y; } native_point;
static inline int point_sum(native_point point) { return point.x + point.y; }
