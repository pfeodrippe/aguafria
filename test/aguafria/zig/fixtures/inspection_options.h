#define AGUAFRIA_INSPECTION_VALUE 42
#define AGUAFRIA_INSPECTION_UNUSABLE_EXPRESSION _Nonnull

typedef struct InspectionPoint {
    int x;
    int y;
} InspectionPoint;

typedef struct InspectionSize {
    unsigned int width;
} InspectionSize;

typedef unsigned int InspectionFormat;
enum { INSPECTION_FORMAT = 7, INSPECTION_ALTERNATE_FORMAT = 11 };
static unsigned int inspection_mutable_format = 8;

static inline unsigned int inspection_format_value(InspectionFormat format) {
    return format;
}
