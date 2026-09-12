#ifndef PITOCO_EXTENSION_HOST_H
#define PITOCO_EXTENSION_HOST_H
#include "panel.h"
#include "sdk/pitoco.h"
#ifdef __cplusplus
extern "C" {
#endif
/* Called once per UI frame, after other UI command producers. */
void pitoco_tick_v1(LabPanel *panel);
#ifdef __cplusplus
}
#endif
#endif
