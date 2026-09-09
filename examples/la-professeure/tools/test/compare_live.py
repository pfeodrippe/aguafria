"""Offline evidence for the explicit live-signal hardware QA. Requires numpy.

Usage: python compare_live.py effect-dry.wav effect-wet.wav bypass-dry.wav bypass-wet.wav
Periodic fixture correlation is a routing/polarity check, not a latency calibration.
"""
import json
import struct
import sys
from pathlib import Path
import numpy as np


def wav(path):
    data = Path(path).read_bytes()
    assert data[:4] == b"RIFF" and data[8:12] == b"WAVE"
    offset = 12
    while offset + 8 <= len(data):
        kind, size = struct.unpack_from("<4sI", data, offset)
        payload = data[offset + 8:offset + 8 + size]
        if kind == b"fmt ":
            fmt, channels, rate = struct.unpack_from("<HHI", payload)
            assert (fmt, channels, rate) == (3, 2, 48000)
        if kind == b"data":
            return np.frombuffer(payload, dtype="<f4").reshape(-1, 2).astype(float)
        offset += 8 + size + size % 2
    raise ValueError("No PCM data")


def measure(dry_path, wet_path):
    dry, wet = wav(dry_path), wav(wet_path)
    n = min(len(dry), len(wet), 96000)
    size = 1 << (2 * n - 1).bit_length()
    correlation = np.fft.irfft(np.fft.rfft(wet[:n, 0], size)
                              * np.conj(np.fft.rfft(dry[:n, 0], size)), size)
    lag = int(np.argmax(np.abs(correlation[:24000])))
    count = min(len(dry), len(wet) - lag, 96000)
    x, y = dry[24000:count], wet[24000 + lag:count + lag]
    gain = np.sum(x * y, axis=0) / np.sum(x * x, axis=0)
    rho = [float(np.corrcoef(x[:, c], y[:, c])[0, 1]) for c in range(2)]
    return dict(dry_frames=len(dry), wet_frames=len(wet),
                peak=float(np.max(np.abs(wet))), finite=bool(np.all(np.isfinite(wet))),
                clipped=int(np.sum(np.abs(wet) >= 1)),
                alignment_samples=lag, gain=gain.tolist(), correlation=rho,
                rms_ratio=(np.sqrt(np.mean(y*y, axis=0) / np.mean(x*x, axis=0))).tolist())


if __name__ == "__main__":
    effect, bypass = measure(*sys.argv[1:3]), measure(*sys.argv[3:5])
    ratio = 20 * np.log10(np.array(effect["rms_ratio"]) / np.array(bypass["rms_ratio"]))
    print(json.dumps(dict(effect=effect, bypass=bypass, effect_vs_bypass_db=ratio.tolist()), indent=2))
    assert effect["finite"] and bypass["finite"]
    assert not effect["clipped"] and not bypass["clipped"]
    assert all(abs(v) > 0.98 for r in [effect, bypass] for v in r["correlation"])
    assert all(v < 0 for v in effect["gain"]) and all(v > 0 for v in bypass["gain"])
    assert np.all(np.abs(ratio + 6) < 0.1)
