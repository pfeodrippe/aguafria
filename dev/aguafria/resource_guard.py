#!/usr/bin/env python3
"""Bound owned compiler experiments, including compressed memory on macOS.

The child gets a new process group and a kernel CPU limit. An independent
supervisor samples the group's aggregate footprint every 50 ms, immediately
kills the entire group on a memory/time breach, and writes a diagnostic receipt.
This is not an OS address-space sandbox: bounded sampling can briefly overshoot
and trusted children must not escape the process group. Keep limits conservative.
No existing/user process group is ever targeted.
"""
import argparse
import ctypes
import errno
import json
import math
import os
from pathlib import Path
import resource
import signal
import subprocess
import sys
import time


class RusageInfoV0(ctypes.Structure):
    # Apple's sys/resource.h, RUSAGE_INFO_V0. Footprint includes compressed pages.
    _fields_ = [("uuid", ctypes.c_uint8 * 16)] + [
        (name, ctypes.c_uint64) for name in (
            "user_time", "system_time", "idle_wakeups", "interrupt_wakeups",
            "pageins", "wired_size", "resident_size", "phys_footprint",
            "start_abstime", "exit_abstime")]


def memory_reader():
    if sys.platform == "darwin":
        library = ctypes.CDLL("/usr/lib/libproc.dylib", use_errno=True)
        usage = library.proc_pid_rusage
        usage.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_void_p]
        usage.restype = ctypes.c_int

        def read(pid):
            info = RusageInfoV0()
            if usage(pid, 0, ctypes.byref(info)) != 0:
                error = ctypes.get_errno()
                if error == errno.ESRCH:
                    return 0, 0
                raise OSError(error, "Cannot read owned process footprint")
            return info.resident_size, info.phys_footprint
        return read
    if sys.platform.startswith("linux"):
        def read(pid):
            try:
                with open(f"/proc/{pid}/status") as stream:
                    fields = dict(line.split(":", 1) for line in stream if ":" in line)
                # Linux does not charge swapped pages to RSS either.
                rss = int(fields.get("VmRSS", "0 kB").split()[0]) * 1024
                swap = int(fields.get("VmSwap", "0 kB").split()[0]) * 1024
                return rss, rss + swap
            except FileNotFoundError:
                return 0, 0
        return read
    raise RuntimeError("No reliable memory accounting on this platform")


def members(group):
    result = subprocess.run(["ps", "-axo", "pid=,pgid="], check=True,
                            capture_output=True, text=True, timeout=2)
    return [int(pid) for pid, pgid in (line.split() for line in result.stdout.splitlines())
            if int(pgid) == group]


def kill_owned_group(child):
    # start_new_session=True established this group; never derive it from a PID
    # supplied by a user or from an unrelated process lookup.
    try:
        os.killpg(child.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass


def supervise(command, max_bytes, timeout, report, interval=0.05):
    if max_bytes <= 0 or timeout <= 0 or interval <= 0:
        raise ValueError("Resource limits must be positive")
    read_memory = memory_reader()  # Fail before launching on unsupported hosts.
    read_memory(os.getpid())
    started = time.monotonic()
    peak_rss = peak_footprint = 0
    reason = "completed"
    child = None
    failure = None

    def child_limits():
        # This program is single-threaded; preexec_fn is used only for rlimits.
        cpu_seconds = max(1, math.ceil(timeout))
        resource.setrlimit(resource.RLIMIT_CPU, (cpu_seconds, cpu_seconds))
        os.nice(10)

    def interrupted(signum, _frame):
        raise KeyboardInterrupt(f"Supervisor received signal {signum}")

    old_handlers = {sig: signal.signal(sig, interrupted)
                    for sig in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP)}
    try:
        child = subprocess.Popen(command, start_new_session=True, preexec_fn=child_limits)
        while True:
            root_status = child.poll()
            pids = members(child.pid)
            if root_status is not None and not pids:
                break
            readings = [read_memory(pid) for pid in pids]
            rss = sum(item[0] for item in readings)
            footprint = sum(item[1] for item in readings)
            peak_rss = max(peak_rss, rss)
            peak_footprint = max(peak_footprint, footprint)
            if footprint > max_bytes:
                reason = "memory-limit"
                break
            if time.monotonic() - started > timeout:
                reason = "timeout"
                break
            time.sleep(interval)
    except KeyboardInterrupt as error:
        reason, failure = "interrupted", str(error)
    except Exception as error:
        reason, failure = "guard-error", repr(error)
    finally:
        if child is not None:
            # Also removes orphaned group members if the root exited first.
            if reason != "completed":
                kill_owned_group(child)
            child.wait()
        for sig, handler in old_handlers.items():
            signal.signal(sig, handler)
        receipt = {"command": command, "reason": reason, "error": failure,
                   "child_exit": None if child is None else child.returncode,
                   "elapsed_seconds": time.monotonic() - started,
                   "peak_rss_bytes": peak_rss, "peak_footprint_bytes": peak_footprint,
                   "limit_bytes": max_bytes, "timeout_seconds": timeout,
                   "sampling_interval_seconds": interval}
        report = Path(report)
        report.parent.mkdir(parents=True, exist_ok=True)
        temporary = report.with_name(report.name + f".{os.getpid()}.tmp")
        temporary.write_text(json.dumps(receipt, indent=2) + "\n")
        temporary.replace(report)
    if reason != "completed":
        return {"timeout": 124, "memory-limit": 125, "guard-error": 126,
                "interrupted": 130}[reason]
    status = child.returncode
    return status if status >= 0 else 128 - status


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--max-mib", type=int, required=True)
    parser.add_argument("--timeout", type=float, required=True)
    parser.add_argument("--report", required=True)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command:
        parser.error("a child command is required")
    return supervise(command, args.max_mib * 1024 * 1024, args.timeout, args.report)


if __name__ == "__main__":
    sys.exit(main())
