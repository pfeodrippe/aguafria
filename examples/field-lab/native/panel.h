#ifndef FIELD_LAB_PANEL_H
#define FIELD_LAB_PANEL_H
#include "sdk/pitoco.h"
#ifdef __cplusplus
extern "C" {
#endif
typedef struct LabPanel {
  double radius, mass, height, gravity, restitution, friction, rolling, vx, vz,
      spin;
  double stiffness, compression, volume_ratio, duration, clearance;
  double time, energy, kinetic, speed, impulse, px, py, pz;
  float yaw, pitch, distance, rate;
  int paused, action, cursor, count, impacts, revision, selected, exported,
      mode, deform, baking, loop;
} LabPanel;
void lab_render(unsigned long long command, LabPanel *panel);
void lab_render_fem(unsigned long long command, LabPanel *panel);
void lab_render_mesh(unsigned long long command, LabPanel *panel, int nodes, int tetrahedra);
void *lab_export_begin(double radius, double mass, double height,
                       double gravity, double restitution, double friction,
                       double rolling, double vx, double vz, double spin,
                       int bodies, double dt, int deform, double stiffness);
void *lab_export_begin_fem(double radius, double mass, double height,
                           double gravity, double restitution, double friction,
                           double rolling, double vx, double vz, double spin,
                           int bodies, double dt, int deform, double stiffness);
void *lab_export_begin_mesh(double radius, double mass, double height,
                            double gravity, double restitution, double friction,
                            double rolling, double vx, double vz, double spin,
                            int bodies, double dt, int deform, double stiffness);
int lab_export_end_mesh(void *file);
int lab_export_sample(void *file, int body, double time, double x, double y,
                      double z, double vx, double vy, double vz, double wx,
                      double wy, double wz, double qx, double qy, double qz,
                      double qw, double energy, double impulse);
int lab_export_particle(void *file, int body, int particle, double time,
                        double x, double y, double z, double vx, double vy, double vz);
int lab_export_end(void *file);
int lab_export_reference(void *file, int node, double x, double y, double z);
int lab_export_cell(void *file, int cell, int a, int b, int c, int d);
int lab_export_reference_body(void *file, int body, int node, double x, double y, double z);
int lab_export_cell_body(void *file, int body, int cell, int a, int b, int c, int d);
#ifdef __cplusplus
}
#endif
#endif
