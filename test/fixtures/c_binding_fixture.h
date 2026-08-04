#ifndef AGUAFRIA_C_BINDING_FIXTURE_H
#define AGUAFRIA_C_BINDING_FIXTURE_H

/// A point documented in the original C header.
typedef struct agua_point_t {
    int x;
    int y;
} agua_point_t;

/// Add two signed integers in the external C library.
int agua_add(int left, int right);

/// A global owned by the external C library.
extern int agua_external_counter;

#endif
