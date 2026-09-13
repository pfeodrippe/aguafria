// Opt-in macOS 15+ QA: capture ONE verified window, never a display or microphone.
// swift tools/test/window_video.swift <window-id> <owner-pid> <seconds:1-15> <new.mp4>
// https://developer.apple.com/documentation/screencapturekit/sccontentfilter
import Foundation
import ScreenCaptureKit
import AVFoundation
import AppKit

final class RecordingStatus: NSObject, SCRecordingOutputDelegate {
    private let lock = NSLock()
    private var finished = false
    private var failure: Error?

    func recordingOutputDidStartRecording(_ output: SCRecordingOutput) {
        print("RECORDING")
        fflush(stdout)
    }

    func recordingOutputDidFinishRecording(_ output: SCRecordingOutput) {
        lock.lock()
        finished = true
        lock.unlock()
    }

    func recordingOutput(_ output: SCRecordingOutput, didFailWithError error: Error) {
        lock.lock()
        failure = error
        finished = true
        lock.unlock()
    }

    func state() -> (Bool, Error?) {
        lock.lock()
        defer { lock.unlock() }
        return (finished, failure)
    }
}

let args = Array(CommandLine.arguments.dropFirst())
guard args.count == 4, let windowID = UInt32(args[0]),
      let pid = Int32(args[1]), pid > 0,
      let seconds = Double(args[2]), seconds.isFinite, seconds >= 1, seconds <= 15,
      args[3].hasPrefix("/"), args[3].hasSuffix(".mp4"),
      !FileManager.default.fileExists(atPath: args[3]) else {
    fputs("Usage: window_video.swift window-id owner-pid seconds:1-15 /absolute/new.mp4\n", stderr)
    exit(2)
}

// ScreenCaptureKit's window filter needs an initialized WindowServer connection.
// This headless helper must not become the foreground application.
NSApplication.shared.setActivationPolicy(.prohibited)
// Encoder startup alone is not evidence of a live desktop. WindowServer can
// retain a stale image while the GPU keeps submitting frames during sleep.
var displayCount: UInt32 = 0
guard CGGetActiveDisplayList(0, nil, &displayCount) == .success else {
    fputs("Window recording refused: cannot inspect active displays\n", stderr)
    exit(4)
}
var displays = [CGDirectDisplayID](repeating: 0, count: Int(displayCount))
guard displayCount > 0,
      CGGetActiveDisplayList(displayCount, &displays, &displayCount) == .success,
      displays.prefix(Int(displayCount)).contains(where: { CGDisplayIsAsleep($0) == 0 }),
      NSWorkspace.shared.frontmostApplication?.bundleIdentifier != "com.apple.loginwindow" else {
    fputs("Window recording refused: desktop asleep or at login; no current visual evidence available.\n", stderr)
    exit(4)
}
// Bound even an OS await that never calls its completion handler. This kills
// only this opt-in helper process, not the app/window being inspected.
DispatchQueue.global().asyncAfter(deadline: .now() + seconds + 15) {
    fputs("Window recording exceeded its deadline; no completed-video claim\n", stderr)
    exit(1)
}
Task { @MainActor in
    do {
        let content = try await SCShareableContent.excludingDesktopWindows(true, onScreenWindowsOnly: false)
        guard let window = content.windows.first(where: {
            $0.windowID == windowID && $0.owningApplication?.processID == pid
        }) else { throw NSError(domain: "QA window/owner not found", code: 1) }
        let filter = SCContentFilter(desktopIndependentWindow: window)
        let config = SCStreamConfiguration()
        config.width = Int(window.frame.width * 2)
        config.height = Int(window.frame.height * 2)
        // Motion evidence at a bounded encoder rate, not an application FPS benchmark.
        config.minimumFrameInterval = CMTime(value: 1, timescale: 60)
        config.queueDepth = 5
        config.showsCursor = false
        config.ignoreShadowsSingleWindow = true
        config.capturesAudio = false
        config.captureMicrophone = false

        let recording = SCRecordingOutputConfiguration()
        recording.outputURL = URL(fileURLWithPath: args[3])
        recording.outputFileType = .mp4
        recording.videoCodecType = .h264
        let status = RecordingStatus()
        let output = SCRecordingOutput(configuration: recording, delegate: status)
        let stream = SCStream(filter: filter, configuration: config, delegate: nil)
        try stream.addRecordingOutput(output)
        print("WINDOW \(windowID) PID \(pid) \(config.width)x\(config.height)")
        fflush(stdout)
        try await stream.startCapture()
        try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
        try await stream.stopCapture()
        let deadline = Date().addingTimeInterval(10)
        while !status.state().0 && Date() < deadline {
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        let (finished, failure) = status.state()
        if let failure { throw failure }
        guard finished else { throw NSError(domain: "Recording finalization timed out", code: 2) }
        print("SAVED \(args[3])")
        exit(0)
    } catch {
        fputs("Window recording failed: \(error)\n", stderr)
        exit(1)
    }
}
NSApplication.shared.run()
