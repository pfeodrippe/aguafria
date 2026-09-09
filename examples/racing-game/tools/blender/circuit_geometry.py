"""Build a metre-scale inspection scene from the editable Interlagos spline.

Executed inside Blender, through the Basilisp modeling REPL. Not game code.
The layout is traced; elevation is illustrative, not surveyed track data.
"""
import math
import bpy
from mathutils import Vector


def material(name, color, roughness=.75):
    mat = bpy.data.materials.get(name) or bpy.data.materials.new(name)
    mat.use_nodes = True
    shader = mat.node_tree.nodes.get("Principled BSDF")
    shader.inputs["Base Color"].default_value = (*color, 1)
    shader.inputs["Roughness"].default_value = roughness
    mat.diffuse_color = (*color, 1)
    return mat


def build(rows):
    if bpy.data.scenes.get("Interlagos inspection"):
        raise ValueError("Inspection scene exists; edit it instead of duplicating")
    rows = [tuple(float(x) for x in row) for row in rows]
    scene = bpy.data.scenes.new("Interlagos inspection")
    scene.unit_settings.system = "METRIC"
    scene.unit_settings.scale_length = 1
    asphalt = material("Circuit asphalt", (.055, .064, .071))
    grass = material("Circuit grass", (.095, .19, .07))
    white = material("Circuit white", (.8, .83, .79))
    green = material("Circuit green", (.06, .31, .14))
    yellow = material("Circuit yellow", (.9, .62, .035))
    points = [Vector(row[:3]) for row in rows]
    # Strip normals are computed from the curve, not independently guessed.
    count = len(points)-1
    normals = []
    for i in range(count):
        tangent = points[(i+1)%count]-points[(i-1)%count]
        normals.append(Vector((-tangent.y, tangent.x, 0)).normalized())
    normals.append(normals[0])

    def strip(name, left, right, height, materials, alternate=False):
        vertices = []
        for p, n in zip(points, normals):
            for offset in (left, right):
                vertices.append(tuple(p+n*offset+Vector((0,0,height))))
        faces = [(2*i, 2*i+1, 2*i+3, 2*i+2) for i in range(count)]
        mesh = bpy.data.meshes.new(name)
        mesh.from_pydata(vertices, [], faces)
        mesh.update()
        for mat in materials:
            mesh.materials.append(mat)
        if alternate:
            for i, polygon in enumerate(mesh.polygons):
                polygon.material_index = int(rows[i][3]/4) % len(materials)
        obj = bpy.data.objects.new(name, mesh)
        scene.collection.objects.link(obj)
        return obj

    strip("Rolling terrain shoulders", -45,45,-.15,[grass])
    strip("13 metre racing surface", -6.5,6.5,0,[asphalt])
    strip("Left white edge", -6.5,-6.35,.018,[white])
    strip("Right white edge", 6.35,6.5,.018,[white])
    strip("Left kerb", -7.4,-6.5,.10,[green,yellow,white],True)
    strip("Right kerb", 6.5,7.4,.10,[green,yellow,white],True)
    # This dedicated scene leaves the car authoring studio untouched.
    world = bpy.data.worlds.new("Circuit daylight")
    world.use_nodes = True
    world.node_tree.nodes["Background"].inputs[0].default_value = (.32,.45,.65,1)
    world.node_tree.nodes["Background"].inputs[1].default_value = .45
    scene.world = world
    light = bpy.data.lights.new("Afternoon sun", "SUN")
    light.energy, light.angle = 2.4, math.radians(2)
    sun = bpy.data.objects.new("Afternoon sun", light)
    sun.rotation_euler = (math.radians(28), math.radians(-22), math.radians(-30))
    scene.collection.objects.link(sun)
    camera_data = bpy.data.cameras.new("Circuit overview")
    camera_data.type, camera_data.ortho_scale = 'ORTHO', 1550
    camera_data.clip_end = 10000
    camera = bpy.data.objects.new("Circuit overview", camera_data)
    camera.location = (0,0,1600)
    scene.collection.objects.link(camera)
    scene.camera = camera
    scene.render.engine = 'CYCLES'
    scene.cycles.samples = 24
    scene.cycles.use_denoising = True
    scene.render.resolution_x, scene.render.resolution_y = 1500,1100
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = 'PNG'
    scene.render.filepath = bpy.path.abspath('//../../build/interlagos-blender-overview.png')
    scene["length_metres"] = 4309.0
    scene["survey_accurate"] = False
    return {"scene": scene.name, "samples": len(rows), "road_width_metres": 13}
