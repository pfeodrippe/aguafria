#include <stddef.h>

typedef struct AguafriaFile AguafriaFile;

AguafriaFile *fopen(const char *path, const char *mode);
size_t fread(void *buffer, size_t size, size_t count, AguafriaFile *file);
int fclose(AguafriaFile *file);
int fseek(AguafriaFile *file, long offset, int origin);
long ftell(AguafriaFile *file);

void *malloc(size_t size);
void free(void *pointer);
