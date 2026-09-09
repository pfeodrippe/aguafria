"""Run with Blender --background --python tools/model_racing.py.

Author editable low-poly meshes and export triangles for the native renderer.
Blender is never required by the game. +X is forward; +Z is up.
"""
import bpy
import json
import math
from pathlib import Path
from mathutils import Vector

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "resources" / "geometry"
OUTPUT.mkdir(parents=True, exist_ok=True)
bpy.ops.object.select_all(action="SELECT")
bpy.ops.object.delete(use_global=False)


def material(name, color, tint=False):
    mat = bpy.data.materials.new(name)
    mat.diffuse_color = (*((0.02, 0.63, 0.83) if tint else color), 1)
    mat["runtime_color"] = color
    mat.use_nodes = True
    shader = mat.node_tree.nodes.get("Principled BSDF")
    shader.inputs["Base Color"].default_value = mat.diffuse_color
    shader.inputs["Roughness"].default_value = 0.34
    mat["racer_tint"] = tint
    return mat


body = material("Racer identity paint", (0.9, 0.9, 0.9), True)
rubber = material("Tire rubber", (0.035, 0.045, 0.06))
metal = material("Graphite chassis", (0.16, 0.20, 0.25))
alloy = material("Brushed alloy", (0.58, 0.65, 0.71))
alloy.node_tree.nodes.get("Principled BSDF").inputs["Metallic"].default_value = 0.75
glass = material("Visor", (0.06, 0.12, 0.18))
white = material("Ceramic white", (0.83, 0.88, 0.92))
yellow = material("Safety yellow", (1.0, 0.68, 0.025))


def finish(obj, name, mat):
    obj.name = name
    obj.data.materials.append(mat)
    return obj


def box(name, loc, size, mat, bevel=0):
    bpy.ops.mesh.primitive_cube_add(size=1, location=loc)
    obj = finish(bpy.context.object, name, mat)
    obj.scale = size
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    if bevel:
        mod = obj.modifiers.new("Machined edges", "BEVEL")
        mod.width, mod.segments = bevel, 1
        bpy.ops.object.modifier_apply(modifier=mod.name)
    return obj


def mesh(name, vertices, faces, mat, smooth=False):
    data = bpy.data.meshes.new(name)
    data.from_pydata(vertices, [], faces)
    data.update()
    obj = bpy.data.objects.new(name, data)
    bpy.context.collection.objects.link(obj)
    finish(obj, name, mat)
    for polygon in data.polygons:
        polygon.use_smooth = smooth
    return obj


def tube(name, a, b, radius, mat, segments=10):
    direction = Vector(b) - Vector(a)
    bpy.ops.mesh.primitive_cylinder_add(vertices=segments, radius=radius,
        depth=direction.length, location=(Vector(a) + Vector(b)) / 2)
    obj = finish(bpy.context.object, name, mat)
    obj.rotation_euler = direction.to_track_quat('Z', 'Y').to_euler()
    for polygon in obj.data.polygons:
        polygon.use_smooth = len(polygon.vertices) == 4
    return obj


def ellipsoid(name, location, scale, mat, segments=16, rings=8):
    bpy.ops.mesh.primitive_uv_sphere_add(segments=segments, ring_count=rings,
                                       radius=1, location=location)
    obj = finish(bpy.context.object, name, mat)
    obj.scale = scale
    for polygon in obj.data.polygons:
        polygon.use_smooth = True
    return obj


def loft(name, sections, mat, offset=0):
    # Eight-sided section contours form an actual tapered panel, not a cube.
    vertices, faces = [], []
    for x, width, bottom, top in sections:
        inset = min(0.035, width * 0.2)
        for y, z in [(-width+inset,bottom), (width-inset,bottom),
                     (width,bottom+inset), (width,top-inset),
                     (width-inset,top), (-width+inset,top),
                     (-width,top-inset), (-width,bottom+inset)]:
            vertices.append((x, y+offset, z))
    faces += [tuple(reversed(range(8))), tuple(range(len(vertices)-8,len(vertices)))]
    for ring in range(len(sections)-1):
        for j in range(8):
            a = ring*8+j; b = ring*8+(j+1)%8
            faces.append((a,b,b+8,a+8))
    return mesh(name, vertices, faces, mat)


def wheel(name, x, y):
    # Revolved shoulder profile gives the slick a rounded sidewall.
    profile = [(-.16,.19),(-.16,.255),(-.135,.302),(-.09,.318),
               (.09,.318),(.135,.302),(.16,.255),(.16,.19)]
    vertices, faces, count = [], [], 16
    for axial, radius in profile:
        vertices.extend((x+radius*math.cos(j*math.tau/count), y+axial,
                         .32+radius*math.sin(j*math.tau/count)) for j in range(count))
    for ring in range(len(profile)-1):
        for j in range(count):
            a=ring*count+j; b=ring*count+(j+1)%count
            faces.append((a,b,b+count,a+count))
    mesh(name + " slick", vertices, faces, rubber, True)
    sign = 1 if y > 0 else -1
    outside=y+sign*.165
    tube(name+" rim",(x,y-sign*.165,.32),(x,outside,.32),.193,alloy,16)
    tube(name+" recess",(x,outside,.32),(x,outside+sign*.008,.32),.158,rubber,16)
    for spoke in range(5):
        angle=spoke*math.tau/5
        tube(name+" spoke", (x,outside+sign*.014,.32),
             (x+.143*math.cos(angle),outside+sign*.014,.32+.143*math.sin(angle)),
             .021,alloy,6)
    tube(name+" hub",(x,outside,.32),(x,outside+sign*.027,.32),.055,metal,10)


box("Floor pan", (0,0,.21),(1.95,.77,.10),metal,.03)
for side in (-1,1):
    tube("Tubular side frame",(-.85,side*.39,.24),(.78,side*.39,.24),.035,metal)
    tube("Front suspension",(.62,side*.19,.32),(.70,side*.66,.32),.030,alloy)
    loft("Sculpted side pod",[(-.69,.13,.24,.48),(-.49,.18,.23,.53),
         (.27,.16,.24,.45),(.46,.095,.25,.37)],body,side*.43)
    box("Pod intake",(-.32,side*.612,.39),(.38,.014,.12),rubber,.025)
    tube("Wing upright",(-.74,side*.29,.37),(-.98,side*.29,.90),.027,metal)
    box("Wing endplate",(-1.01,side*.66,.94),(.32,.045,.24),body,.025)
loft("Sloping nose",[(.04,.225,.30,.68),(.25,.245,.27,.59),
                     (.75,.18,.24,.37),(1.02,.14,.22,.29)],body)
loft("White nose stripe",[(.04,.035,.682,.687),(.25,.035,.592,.597),
                          (.75,.03,.372,.377),(1.02,.027,.292,.297)],white)
box("Front bumper",(1.02,0,.245),(.19,1.06,.19),body,.065)
box("Bumper stripe",(1.02,0,.344),(.12,.07,.008),white)
for side in (-1,1):
    box("Bumper intake",(1.117,side*.34,.235),(.012,.23,.065),rubber,.012)
box("Rear wing",(-1.01,0,.90),(.31,1.32,.075),body,.026)
box("Wing stripe",(-1.01,0,.942),(.28,.21,.008),white)
box("Bucket seat",(-.32,0,.44),(.43,.43,.21),rubber,.065)
seat=box("Seat back",(-.51,0,.68),(.14,.46,.53),rubber,.06)
seat.rotation_euler.y=-.18
ellipsoid("Driver torso",(-.30,0,.71),(.20,.215,.28),body)
for side in (-1,1):
    tube("Driver thigh",(-.20,side*.12,.50),(.16,side*.14,.38),.080,body)
    tube("Driver boot",(.13,side*.14,.36),(.40,side*.14,.33),.072,rubber)
    tube("Upper arm",(-.25,side*.20,.83),(-.04,side*.28,.66),.063,body)
    tube("Forearm",(-.04,side*.28,.66),(.14,side*.18,.73),.057,body)
    ellipsoid("Glove",(.14,side*.18,.73),(.065,.057,.059),rubber,12,6)
tube("Steering column",(.22,0,.34),(.14,0,.71),.022,metal)
bpy.ops.mesh.primitive_torus_add(major_segments=16,minor_segments=6,
    location=(.14,0,.71),rotation=(0,math.pi/2-.35,0),major_radius=.18,minor_radius=.021)
finish(bpy.context.object,"Steering wheel",rubber)
ellipsoid("Helmet shell",(-.20,0,1.07),(.255,.252,.29),white,16,8)
# Curved visor follows the forward hemisphere of the helmet.
vertices=[]
for latitude in (-.22,.06,.34):
    for j in range(13):
        angle=-1.15+j*2.30/12
        vertices.append((-.20+.26*math.cos(latitude)*math.cos(angle),
                         .258*math.cos(latitude)*math.sin(angle),
                         1.07+.296*math.sin(latitude)))
mesh("Curved visor",vertices,[(i*13+j,i*13+j+1,(i+1)*13+j+1,(i+1)*13+j)
     for i in range(2) for j in range(12)],glass,True)
box("Engine block",(-.73,0,.49),(.28,.40,.35),metal,.035)
for height in (.40,.45,.50,.55,.60,.65):
    box("Engine cooling fin",(-.73,0,height),(.32,.43,.018),alloy)
tube("Exhaust",(-.61,-.26,.40),(-1.01,-.26,.44),.057,alloy,12)
tube("Rear axle",(-.67,-.72,.32),(-.67,.72,.32),.038,alloy)
for x in (-.67,.70):
    for y in (-.66,.66):
        wheel("Rear wheel" if x<0 else "Front wheel",x,y)
kart = list(bpy.context.scene.objects)

box("Pit back", (0, 1.0, 0.8), (2.6, 0.12, 1.6), metal)
box("Pit roof", (0, 0, 1.65), (2.9, 2.3, 0.18), body, 0.03)
for x in (-1.25, 1.25):
    box("Pit pillar", (x, -0.9, 0.8), (0.12, 0.12, 1.6), white)
box("Pit sign", (0, -1.12, 1.45), (2.5, 0.08, 0.27), yellow)
box("Pit concrete pad",(0,0,.035),(2.9,2.4,.07),metal,.025)
box("Pit tool cabinet",(.88,.69,.44),(.55,.40,.78),body,.03)
for height in (.20,.36,.52,.68):
    box("Tool drawer",(.88,.477,height),(.42,.018,.075),alloy,.005)
for side in (-1,1):
    box("Bay stop marker",(side*.88,-.42,.076),(.07,1.08,.01),yellow)
box("Pit light",(0,-.88,1.55),(1.8,.11,.06),white,.015)
garage = [obj for obj in bpy.context.scene.objects if obj not in kart]


def triangles(objects):
    result = []
    for obj in sorted(objects, key=lambda obj: obj.name):
        obj.data.calc_loop_triangles()
        transform = obj.matrix_world
        normal_transform = transform.to_3x3().inverted().transposed()
        for face in obj.data.loop_triangles:
            mat = obj.data.materials[face.material_index]
            for index, loop_index in zip(face.vertices, face.loops):
                normal = (normal_transform @ obj.data.corner_normals[loop_index].vector).normalized()
                position = transform @ obj.data.vertices[index].co
                result.append([round(float(v), 6) for v in
                               (*position, *normal, *mat["runtime_color"],
                                float(mat.get("racer_tint", False)))])
    return result


data = {"kart": triangles(kart), "garage": triangles(garage)}
print("MESH BUDGET", {key: len(value) for key, value in data.items()}, flush=True)
assert len(data["kart"])*8 + len(data["garage"])*4 + 20000 < 131072, "Frame vertex budget exceeded"
# EDN vectors accept commas as whitespace; JSON's numeric vectors are EDN too.
(OUTPUT / "racing.edn").write_text(
    "{\n" + "\n".join(":" + key + " " + json.dumps(value)
                         for key, value in data.items()) + "\n}\n")
for obj in garage:
    obj.location.x += 4.0
# Studio scene is authoring-only; none of these objects enter the game export.
bpy.ops.object.camera_add(location=(3.5,-4.8,3.1))
camera=bpy.context.object
camera.rotation_euler=(Vector((0,0,.52))-camera.location).to_track_quat('-Z','Y').to_euler()
camera.data.type='ORTHO'; camera.data.ortho_scale=3.8
bpy.context.scene.camera=camera
for name,location,energy,size in [('Key',(2,-3,5),650,4),('Fill',(0,3,3),480,3),('Rim',(-3,-1,4),700,3)]:
    bpy.ops.object.light_add(type='AREA', location=location)
    light=bpy.context.object; light.name=name
    light.data.energy=energy; light.data.shape='DISK'; light.data.size=size
    light.rotation_euler=(Vector((0,0,.4))-light.location).to_track_quat('-Z','Y').to_euler()
scene=bpy.context.scene
scene.render.engine='CYCLES'; scene.cycles.samples=32
scene.render.resolution_x=1200; scene.render.resolution_y=1000; scene.render.resolution_percentage=100
scene.world.color=(.16,.16,.16)
scene.render.image_settings.file_format='PNG'
scene.render.filepath=str(ROOT/'build'/'kart-studio.png')
bpy.ops.wm.save_as_mainfile(filepath=str(OUTPUT / "racing.blend"))
print("EXPORTED", {key: len(value) for key, value in data.items()})
bpy.ops.render.render(write_still=True)
