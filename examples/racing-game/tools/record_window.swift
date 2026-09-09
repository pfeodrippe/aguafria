// macOS 15+ QA only; not linked into the game. Capture exactly one selected
// window, never whichever unrelated application happens to be foreground.
// swiftc -parse-as-library tools/record_window.swift -o /tmp/racing-record-window
// /tmp/racing-record-window WINDOW_ID /tmp/new-recording.mov SECONDS
import Foundation
import AppKit
import ScreenCaptureKit
import AVFoundation

enum RecordingError: Error {
    case arguments, existingOutput, missingWindow, timeout
}

@available(macOS 15.0, *)
final class Completion: NSObject, SCRecordingOutputDelegate {
    private let lock = NSLock()
    private var result: Result<Void, Error>?

    private func finish(_ value: Result<Void, Error>) {
        lock.lock()
        defer { lock.unlock() }
        if result == nil { result = value }
    }

    func outcome() -> Result<Void, Error>? {
        lock.lock()
        defer { lock.unlock() }
        return result
    }

    func recordingOutputDidFinishRecording(_ recordingOutput: SCRecordingOutput) {
        finish(.success(()))
    }

    func recordingOutput(_ recordingOutput: SCRecordingOutput, didFailWithError error: Error) {
        finish(.failure(error))
    }
}

@main
struct WindowRecorder {
    @MainActor
    static func main() async throws {
        guard #available(macOS 15.0, *) else { throw RecordingError.arguments }
        let args = CommandLine.arguments
        guard args.count == 4, let id = UInt32(args[1]),
              let seconds = Double(args[3]), seconds.isFinite,
              seconds >= 1, seconds <= 120, args[2].hasPrefix("/") else {
            throw RecordingError.arguments
        }
        guard !FileManager.default.fileExists(atPath: args[2]) else {
            throw RecordingError.existingOutput
        }
        // Initialize the WindowServer connection for this CLI process without
        // creating/activating a window or changing the foreground application.
        _ = NSApplication.shared
        let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
        guard let window = content.windows.first(where: { $0.windowID == id }) else {
            throw RecordingError.missingWindow
        }
        let filter = SCContentFilter(desktopIndependentWindow: window)
        let config = SCStreamConfiguration()
        let scale = Double(filter.pointPixelScale)
        config.width = max(2, Int(window.frame.width * scale) / 2 * 2)
        config.height = max(2, Int(window.frame.height * scale) / 2 * 2)
        config.minimumFrameInterval = CMTime(value: 1, timescale: 60)
        config.queueDepth = 6
        config.showsCursor = false
        config.capturesAudio = false
        config.captureMicrophone = false
        let recordingConfig = SCRecordingOutputConfiguration()
        recordingConfig.outputURL = URL(fileURLWithPath: args[2])
        recordingConfig.videoCodecType = .h264
        recordingConfig.outputFileType = .mov
        let completion = Completion()
        let recording = SCRecordingOutput(configuration: recordingConfig, delegate: completion)
        let stream = SCStream(filter: filter, configuration: config, delegate: nil)
        try stream.addRecordingOutput(recording)
        print("Recording window \(id): \(window.title ?? "") at \(config.width)x\(config.height), requested 60 FPS")
        try await stream.startCapture()
        try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
        try await stream.stopCapture()
        for _ in 0..<100 {
            if let outcome = completion.outcome() {
                try outcome.get()
                print("Saved \(recording.recordedDuration.seconds)s, \(recording.recordedFileSize) bytes to \(args[2])")
                return
            }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        throw RecordingError.timeout
    }
}
