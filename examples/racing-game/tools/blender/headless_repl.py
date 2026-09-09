"""Blender/Basilisp authoring when the desktop is unavailable.

blender --background resources/geometry/racing.blend --python tools/blender/headless_repl.py
All bpy evaluation runs on Blender's main thread, never the socket thread.
"""
import time
from pathlib import Path
from basilisp_blender.nrepl import server_thread_async_start

work, shutdown = server_thread_async_start(
    nrepl_port_filepath=str(Path(__file__).with_name(".nrepl-port")))
try:
    while True:
        work()
        time.sleep(.01)
finally:
    shutdown()
