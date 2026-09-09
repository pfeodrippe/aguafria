"""Bulk Blender geometry operations, called from the live Basilisp REPL.

Python iterators avoid Python-backed Lisp lazy-sequence deallocation chains
overflowing Blender's native UI-thread stack on high-resolution voxel meshes.
All model data comes from editable bpy objects; this is not a game renderer.
"""
import math
import bpy
from mathutils import Vector
from mathutils.bvhtree import BVHTree


def group_for(name, x, y):
    if name.startswith(("Driver", "Helmet", "Curved visor", "Forearm", "Upper arm", "Glove")):
        return "driver"
    if name.startswith(("Front wheel", "Rear wheel")):
        return "wheel-" + ("front" if x > 0 else "rear") + ("-left" if y > 0 else "-right")
    return "body"


def sample(source, size, only_group=None, origin=(0, 0, 0)):
    cells = {}
    # Surface details must be laid over their enclosing solids. Collection
    # alphabetical order otherwise lets the helmet erase its own visor.
    objects = sorted(bpy.data.collections[source].objects,
                     key=lambda o: (o.name.startswith(("Curved visor", "White nose stripe")), o.name))
    for obj in objects:
        data = obj.data
        vertices = [obj.matrix_basis @ v.co for v in data.vertices]
        center = [(min(v[a] for v in vertices) + max(v[a] for v in vertices)) / 2
                  for a in range(3)]
        group = group_for(obj.name, *center[:2])
        if only_group is not None and group != only_group:
            continue
        vertices = [v - Vector(origin) for v in vertices]
        tree = BVHTree.FromPolygons(vertices, [list(p.vertices) for p in data.polygons])
        lo = [math.floor(min(v[a] for v in vertices) / size) for a in range(3)]
        hi = [math.ceil(max(v[a] for v in vertices) / size) for a in range(3)]
        for x in range(lo[0], hi[0]):
            for y in range(lo[1], hi[1]):
                for z in range(lo[2], hi[2]):
                    point = Vector(((x + .5) * size, (y + .5) * size, (z + .5) * size))
                    near, normal, index, distance = tree.find_nearest(point)
                    # Winding-independent ray parity is essential: mirrored
                    # wheel/visor surfaces need not have outward face normals.
                    # Signed nearest-normal tests incorrectly fill their AABBs.
                    ray = Vector((1.0, .371, .193)).normalized()
                    cursor, crossings = point.copy(), 0
                    for _ in range(64):
                        hit, _, _, _ = tree.ray_cast(cursor, ray)
                        if hit is None:
                            break
                        crossings += 1
                        cursor = hit + ray * .00001
                    if near is not None and (crossings % 2 == 1 or distance < size * .35):
                        mat = data.materials[data.polygons[index].material_index]
                        cells[x, y, z] = (group, mat.name)
    return cells


def rectangles(tiles):
    remaining = set(tiles)
    while remaining:
        u, v = min(remaining)
        w = h = 1
        while (u + w, v) in remaining:
            w += 1
        while all((u + x, v + h) in remaining for x in range(w)):
            h += 1
        for x in range(u, u + w):
            for y in range(v, v + h):
                remaining.remove((x, y))
        yield u, v, w, h


def build(source="Formula source", size=.06, only_group=None, origin=(0, 0, 0),
          target="Voxel kart"):
    if target in bpy.data.collections:
        raise ValueError(f"Inspect/archive the existing {target} before building")
    cells = sample(source, size, only_group, origin)
    collection = bpy.data.collections.new(target)
    bpy.context.scene.collection.children.link(collection)
    summaries = []
    for group in sorted({part for part, _ in cells.values()}):
        planes = {}
        names = sorted({mat for part, mat in cells.values() if part == group})
        for pos, (part, mat) in cells.items():
            if part != group:
                continue
            for axis in range(3):
                for sign in (-1, 1):
                    neighbor = list(pos)
                    neighbor[axis] += sign
                    if cells.get(tuple(neighbor), (None,))[0] == part:
                        continue
                    level = pos[axis] + (1 if sign > 0 else 0)
                    key = axis, sign, level, mat
                    planes.setdefault(key, set()).add((pos[(axis+1)%3], pos[(axis+2)%3]))
        vertices, faces, materials = [], [], []
        for (axis, sign, level, mat), tiles in sorted(planes.items()):
            for u, v, w, h in rectangles(tiles):
                start = len(vertices)
                for a, b in ((u,v), (u+w,v), (u+w,v+h), (u,v+h)):
                    p = [0., 0., 0.]
                    p[axis], p[(axis+1)%3], p[(axis+2)%3] = level*size, a*size, b*size
                    vertices.append(p)
                order = (0,1,2,3) if sign > 0 else (3,2,1,0)
                faces.append([start+i for i in order])
                materials.append(names.index(mat))
        data = bpy.data.meshes.new("Voxel " + group)
        data.from_pydata(vertices, [], faces)
        data.update()
        for name in names:
            data.materials.append(bpy.data.materials[name])
        for poly, material in zip(data.polygons, materials):
            poly.material_index = material
        obj = bpy.data.objects.new("Voxel " + group, data)
        obj["damage_group"], obj["voxel_size"] = group, size
        obj.location = origin
        collection.objects.link(obj)
        summaries.append((group, len(faces)*2))
    bpy.context.view_layer.update()
    return {"cells": len(cells), "triangles": sum(n for _, n in summaries), "parts": summaries}


def rebuild_wheels(source="Formula source", size=.08):
    """Sample each wheel on an axle-centered grid, not the world's grid phase.

    Preserve prior objects in a hidden collection for recovery. Geometry remains
    editable in Blender; the game receives mesh coordinates and authored axles.
    """
    active = bpy.data.collections["Voxel kart"]
    sources = [o for o in bpy.data.collections[source].objects
               if o.name.startswith(("Front wheel slick", "Rear wheel slick"))]
    if len(sources) != 4:
        raise ValueError(f"Expected four authored tire solids, got {len(sources)}")
    archive = bpy.data.collections.new("Wheel voxel history")
    bpy.context.scene.collection.children.link(archive)
    archive.hide_render = archive.hide_viewport = True
    results = []
    for solid in sources:
        vertices = [solid.matrix_basis @ v.co for v in solid.data.vertices]
        center = [(min(v[a] for v in vertices) + max(v[a] for v in vertices)) / 2
                  for a in range(3)]
        group = group_for(solid.name, *center[:2])
        target = "Axle grid " + group
        if target in bpy.data.collections:
            # Preserve generated staging collections too; never delete user art.
            bpy.data.collections[target].name = target + " history"
        result = build(source, size, group, center, target)
        staging = bpy.data.collections[target]
        replacement, = list(staging.objects)
        local = [v.co for v in replacement.data.vertices]
        diameter = [max(v[a] for v in local) - min(v[a] for v in local)
                    for a in range(3)]
        if abs(diameter[0] - diameter[2]) > .00001:
            raise ValueError(f"Unequal rolling diameters for {group}: {diameter}")
        replacement["axle_local"] = (0., 0., 0.)
        replacement["rolling_radius"] = diameter[2] / 2
        replacement["tire_width"] = max(v.y for v in vertices) - min(v.y for v in vertices)
        for old in list(active.objects):
            if old.get("damage_group") == group:
                archive.objects.link(old)
                active.objects.unlink(old)
        active.objects.link(replacement)
        staging.objects.unlink(replacement)
        results.append({"part": group, "axle": center, "diameters": diameter,
                        "triangles": result["triangles"]})
    bpy.context.view_layer.update()
    return results


def export(path, capacity=131072):
    meshes = {}
    axles = []
    for obj in bpy.data.collections["Voxel kart"].objects:
        if obj.get("damage_group", "").startswith("wheel-"):
            scale = obj.matrix_basis.to_scale()
            if max(scale) - min(scale) > .00001:
                raise ValueError(f"Non-uniform wheel scaling would wobble: {obj.name}")
            axles.append((obj["damage_group"],
                          list(obj.matrix_basis @ Vector(obj["axle_local"])),
                          obj["rolling_radius"] * scale.x,
                          obj["tire_width"] * scale.y))
    if len(axles) != 4:
        raise ValueError("Export requires four Blender-authored wheel axles")
    targets = [(o["damage_group"], [o], Vector((0,0,0)))
               for o in bpy.data.collections["Voxel kart"].objects]
    targets.append(("garage", bpy.data.collections["Garage"].objects, Vector((4,0,0))))
    for name, objects, origin in targets:
        rows = []
        for obj in objects:
            if obj.parent is not None or obj.constraints:
                raise ValueError(f"Evaluate parenting/constraints before exporting {obj.name}")
            data, transform = obj.data, obj.matrix_basis.copy()
            normal_transform = transform.to_3x3().inverted().transposed()
            data.calc_loop_triangles()
            for face in data.loop_triangles:
                mat = data.materials[face.material_index]
                color = list(mat.get("runtime_color", mat.diffuse_color[:3]))
                for vertex_index, loop_index in zip(face.vertices, face.loops):
                    p = transform @ data.vertices[vertex_index].co - origin
                    n = (normal_transform @ data.corner_normals[loop_index].vector).normalized()
                    rows.append([*p, *n, *color, float(mat.get("racer_tint", False))])
        meshes[name] = rows
    # Current 192-segment road, curbs, pit markings and bounded item effects
    # use fewer than 10k vertices; native frame tests verify the complete stream.
    shadow_vertices = sum(3 for name, rows in meshes.items() if name != "garage"
                          for i in range(0, len(rows), 3) if rows[i][5] > .5)
    budget = (sum(len(v) * (4 if k == "garage" else 8) for k, v in meshes.items())
              + 8 * shadow_vertices + 10000)
    if budget >= capacity:
        raise ValueError(f"Frame budget {budget} exceeds {capacity}; simplify before export")
    # EDN numeric data only. No Python interpreter or Blender dependency ships.
    with open(path, "w", encoding="utf-8") as output:
        output.write("{\n")
        for name, rows in sorted(meshes.items()):
            output.write(":" + name + " [\n")
            for row in rows:
                output.write("[" + " ".join(format(float(v), ".9g") for v in row) + "]\n")
            output.write("]\n")
        output.write("}\n")
    from pathlib import Path
    with open(Path(path).with_name("wheel-axes.edn"), "w", encoding="utf-8") as output:
        output.write("{\n")
        for name, center, radius, width in sorted(axles):
            output.write(":" + name + " {:center [" +
                         " ".join(format(v, ".9g") for v in center) +
                         f"] :radius {radius:.9g} :width {width:.9g}" + "}\n")
        output.write("}\n")
    return {"budget": budget, "vertices": {k: len(v) for k, v in meshes.items()}}
