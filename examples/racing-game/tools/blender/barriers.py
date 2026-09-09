"""Editable circuit containment authored in Blender, not runtime-generated art.

Run through the existing Basilisp Blender REPL. Export the authored final meshes so
the renderer and Box3D consume the same triangles in metre coordinates.
"""
import bpy
import bmesh
import math
from pathlib import Path
from mathutils import Vector
from mathutils.kdtree import KDTree


COLLECTION = "Circuit containment"


def author(rows):
    if bpy.data.collections.get(COLLECTION):
        raise ValueError("Containment exists: edit its meshes, do not overwrite them")
    scene = bpy.data.scenes["Interlagos inspection"]
    collection = bpy.data.collections.new(COLLECTION)
    scene.collection.children.link(collection)
    points = [Vector(tuple(float(v) for v in row[:3])) for row in rows][:-1]
    count = len(points)
    concrete = bpy.data.materials.get("Containment concrete")
    if concrete is None:
        concrete = bpy.data.materials.new("Containment concrete")
        concrete.use_nodes = True
        shader = concrete.node_tree.nodes.get("Principled BSDF")
        shader.inputs["Base Color"].default_value = (.64, .68, .69, 1)
        shader.inputs["Roughness"].default_value = .85
        concrete.diffuse_color = (.64, .68, .69, 1)
    for sign, name in [(-1, "Circuit right containment"), (1, "Circuit left containment")]:
        vertices = []
        for i, point in enumerate(points):
            tangent = points[(i + 1) % count] - points[(i - 1) % count]
            normal = Vector((-tangent.y, tangent.x, 0)).normalized()
            for lane, height in [(31.7, -.25), (32.3, -.25), (32.3, 1.5), (31.7, 1.5)]:
                vertices.append(tuple(point + sign * lane * normal + Vector((0, 0, height))))
        faces = []
        for i in range(count):
            a, b = 4 * i, 4 * ((i + 1) % count)
            for side in range(4):
                other = (side + 1) % 4
                faces.append((a + side, a + other, b + other, b + side))
        mesh = bpy.data.meshes.new(name)
        mesh.from_pydata(vertices, [], faces)
        mesh.validate()
        bm = bmesh.new()
        bm.from_mesh(mesh)
        bmesh.ops.recalc_face_normals(bm, faces=list(bm.faces))
        bm.to_mesh(mesh)
        bm.free()
        mesh.update()
        mesh.materials.append(concrete)
        obj = bpy.data.objects.new(name, mesh)
        collection.objects.link(obj)
        obj["collision"] = True
        obj["units"] = "metres"
    return refine_offsets(rows)


def refine_offsets(rows):
    """Bring inside barriers closer before tight bends instead of folding a
    wide parallel curve across itself. Keep the barrier outside the pit apron.
    This edits the newly authored Blender meshes; it does not alter the track.
    """
    points = [Vector(tuple(float(v) for v in row[:3])) for row in rows][:-1]
    count = len(points)
    curvature = []
    for i, p in enumerate(points):
        a, b = p - points[(i-1) % count], points[(i+1) % count] - p
        angle = math.atan2(a.x*b.y-a.y*b.x, a.x*b.x+a.y*b.y)
        curvature.append(angle / max(.001, .5*(a.length+b.length)))
    minima = {}
    for sign, name in [(-1, "Circuit right containment"), (1, "Circuit left containment")]:
        bounds = [min(32.0, .65/abs(k)) if sign*k > 0 else 32.0 for k in curvature]
        # Anticipate each narrowing; a short smoothed transition avoids a
        # sharp kink where curvature first crosses the radius threshold.
        narrow = [min(bounds[(i+j) % count] for j in range(-12, 13)) for i in range(count)]
        offsets = [min(bounds[i], sum(narrow[(i+j) % count] for j in range(-4, 5))/9)
                   for i in range(count)]
        if min(offsets) < 9.0:
            raise ValueError("Circuit bend too tight for safe containment clearance")
        obj = bpy.data.objects[name]
        for i, point in enumerate(points):
            progress = float(rows[i][3])/4309.0
            if sign > 0 and (progress > .94 or progress < .055) and offsets[i] < 29.0:
                raise ValueError("Inside barrier would obstruct the pit apron")
            tangent = points[(i+1) % count] - points[(i-1) % count]
            normal = Vector((-tangent.y, tangent.x, 0)).normalized()
            for corner, (delta, height) in enumerate([(-.3, -.25), (.3, -.25), (.3, 1.5), (-.3, 1.5)]):
                obj.data.vertices[4*i+corner].co = point + sign*(offsets[i]+delta)*normal + Vector((0, 0, height))
        obj.data.update()
        minima[name] = min(offsets)
    return {"minimum_offsets_metres": minima}


def export_mesh():
    vertices, triangles = [], []
    for obj in bpy.data.collections[COLLECTION].objects:
        # These are authored final meshes. Do not evaluate an object against
        # another scene's dependency graph (the car studio may be active).
        # Explicitly reject unapplied modifiers instead of silently omitting them.
        if obj.modifiers:
            raise ValueError(f"Apply modifiers to {obj.name} before exporting")
        mesh = obj.data
        mesh.calc_loop_triangles()
        base = len(vertices)
        vertices.extend([[round(float(v), 6) for v in obj.matrix_world @ point.co]
                         for point in mesh.vertices])
        triangles.extend([[base + int(index) for index in triangle.vertices]
                          for triangle in mesh.loop_triangles])
    return {"vertices": vertices, "triangles": triangles}


def export_file():
    # Keep large numeric arrays in Python instead of constructing/deallocating
    # tens of thousands of Basilisp sequence nodes across the nREPL boundary.
    data = export_mesh()
    def edn_rows(rows):
        return "[" + " ".join("[" + " ".join(map(str, row)) + "]" for row in rows) + "]"
    output = Path(bpy.path.abspath("//barriers.edn"))
    output.write_text('{:source "racing.blend/Circuit containment" :units :metres '
                      ':vertices ' + edn_rows(data["vertices"]) +
                      ' :triangles ' + edn_rows(data["triangles"]) + '}\n')
    return {"vertices": len(data["vertices"]), "triangles": len(data["triangles"]),
            "path": str(output)}


def clearance(rows):
    points = [Vector((float(row[0]), float(row[1]), 0)) for row in rows][:-1]
    tree = KDTree(len(points))
    for i, point in enumerate(points):
        tree.insert(point, i)
    tree.balance()
    closest = (float("inf"), None)
    for obj in bpy.data.collections[COLLECTION].objects:
        for vertex in obj.data.vertices:
            p = obj.matrix_world @ vertex.co
            _, _, distance = tree.find(Vector((p.x, p.y, 0)))
            if distance < closest[0]:
                closest = (distance, tuple(p))
    return {"minimum_centreline_distance": closest[0], "point": closest[1]}
