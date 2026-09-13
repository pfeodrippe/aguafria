// Opt-in native UI QA. Coordinates are global screen points; bring the game forward first.
// swift tools/test/studio_input.swift --target-pid <studio-jvm-pid> click 390 204
import Foundation
import CoreGraphics
import AppKit
import ApplicationServices

// A sleeping/locked desktop may retain old window images. Never wake it by
// posting input or modify its clipboard while pretending to exercise Studio.
var displayCount: UInt32 = 0
guard CGGetActiveDisplayList(0, nil, &displayCount) == .success else {
    fputs("Input refused: cannot inspect active displays\n", stderr)
    exit(4)
}
var displays = [CGDirectDisplayID](repeating: 0, count: Int(displayCount))
guard displayCount > 0,
      CGGetActiveDisplayList(displayCount, &displays, &displayCount) == .success,
      displays.prefix(Int(displayCount)).contains(where: { CGDisplayIsAsleep($0) == 0 }),
      NSWorkspace.shared.frontmostApplication?.bundleIdentifier != "com.apple.loginwindow" else {
    fputs("Input refused: desktop asleep or at login; unlock/wake it before UI QA. No input sent.\n", stderr)
    exit(4)
}

var args = Array(CommandLine.arguments.dropFirst())
var expectedPID: pid_t?
if args.first == "--target-pid" {
    guard args.count >= 3, let expected = Int32(args[1]), expected > 0 else {
        fputs("--target-pid requires a positive process ID and a command\n", stderr)
        exit(2)
    }
    // Check in this invocation, immediately before input or clipboard changes.
    // Never trust a foreground assertion made by a previous REPL/tool call.
    let actual = NSWorkspace.shared.frontmostApplication?.processIdentifier
    guard actual == expected else {
        fputs("Input refused: expected foreground PID \(expected), found \(actual ?? -1)\n", stderr)
        exit(3)
    }
    expectedPID = expected
    args.removeFirst(2)
}
func number(_ i: Int) -> Double { Double(args[i])! }
func mouse(_ kind: CGEventType, _ x: Double, _ y: Double) {
    if kind == .leftMouseDown, let expected = expectedPID {
        var element: AXUIElement?
        let status = AXUIElementCopyElementAtPosition(
            AXUIElementCreateSystemWide(), Float(x), Float(y), &element)
        var owner: pid_t = -1
        if let element = element { AXUIElementGetPid(element, &owner) }
        guard status == .success, owner == expected else {
            fputs("Click refused: point belongs to PID \(owner), expected \(expected); AX status \(status.rawValue)\n", stderr)
            exit(3)
        }
    }
    CGEvent(mouseEventSource: nil, mouseType: kind,
            mouseCursorPosition: CGPoint(x: x, y: y), mouseButton: .left)?.post(tap: .cghidEventTap)
    Thread.sleep(forTimeInterval: 0.025)
}
func systemKey(_ code: Int, _ flags: UInt64) {
    let names: [(UInt64, String)] = [(1048576, "command down"), (131072, "shift down"),
                                   (262144, "control down"), (524288, "option down")]
    let modifiers = names.filter { flags & $0.0 != 0 }.map { $0.1 }
    let suffix = modifiers.isEmpty ? "" : " using {" + modifiers.joined(separator: ", ") + "}"
    let process = Process()
    process.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
    process.arguments = ["-e", "tell application \"System Events\" to key code \(code)\(suffix)"]
    do { try process.run(); process.waitUntilExit() }
    catch { fputs("System Events input failed: \(error)\n", stderr); exit(1) }
    if process.terminationStatus != 0 { exit(process.terminationStatus) }
}
func scrollModifiers(_ flags: UInt64, down: Bool) {
    // GLFW updates held modifiers from Cocoa flagsChanged, not from flags on a
    // scroll event. System Events' "key down option" didn't change that state.
    let keys: [(UInt64, CGKeyCode)] = [(131072, 56), (524288, 58)]
    var active: UInt64 = down ? 0 : flags
    for (mask, code) in keys where flags & mask != 0 {
        if down { active |= mask } else { active &= ~mask }
        let event = CGEvent(keyboardEventSource: nil, virtualKey: code, keyDown: down)
        event?.type = .flagsChanged
        event?.flags = CGEventFlags(rawValue: active)
        event?.post(tap: .cghidEventTap)
        Thread.sleep(forTimeInterval: 0.05)
    }
}
switch args.first {
case "check-target":
    print("Input target check passed; no input sent")
case "paste", "check-copy":
    // Preserve all existing pasteboard representations; never print user contents.
    let board = NSPasteboard.general
    let saved = (board.pasteboardItems ?? []).map { item in
        item.types.compactMap { type in item.data(forType: type).map { (type, $0) } }
    }
    defer {
        board.clearContents()
        let items = saved.map { entries in
            let item = NSPasteboardItem()
            for (type, data) in entries { item.setData(data, forType: type) }
            return item
        }
        board.writeObjects(items)
    }
    if args.first == "paste" {
        board.clearContents(); board.setString(args[1], forType: .string)
        systemKey(9, 1048576)
        Thread.sleep(forTimeInterval: 0.6)
    } else {
        systemKey(8, 1048576)
        Thread.sleep(forTimeInterval: 0.6)
        print("Clipboard matches expected test text: \(board.string(forType: .string) == args[1])")
    }
case "click":
    mouse(.mouseMoved, number(1), number(2))
    mouse(.leftMouseDown, number(1), number(2))
    mouse(.leftMouseUp, number(1), number(2))
case "click-sequence":
    // A bounded burst through the OS event queue, not direct native hit testing.
    guard args.count >= 5, args.count <= 65, (args.count - 1) % 2 == 0 else {
        fputs("click-sequence requires 2–32 x/y pairs\n", stderr)
        exit(2)
    }
    for i in stride(from: 1, to: args.count, by: 2) {
        guard Double(args[i]) != nil, Double(args[i + 1]) != nil else {
            fputs("click-sequence coordinates must be numbers\n", stderr)
            exit(2)
        }
    }
    for i in stride(from: 1, to: args.count, by: 2) {
        mouse(.mouseMoved, number(i), number(i + 1))
        mouse(.leftMouseDown, number(i), number(i + 1))
        mouse(.leftMouseUp, number(i), number(i + 1))
    }
case "double":
    mouse(.mouseMoved, number(1), number(2))
    for _ in 0..<2 {
        mouse(.leftMouseDown, number(1), number(2))
        mouse(.leftMouseUp, number(1), number(2))
    }
case "drag", "drag-hold":
    let x = number(1), y = number(2), dx = number(3) - x, dy = number(4) - y
    mouse(.mouseMoved, x, y)
    mouse(.leftMouseDown, x, y)
    for i in 1...20 { mouse(.leftMouseDragged, x + dx * Double(i)/20, y + dy * Double(i)/20) }
    if args.first == "drag-hold" {
        // Keep the button held at the edge so the app, not synthetic movement,
        // drives autoscroll. Bound the opt-in hold to five seconds.
        Thread.sleep(forTimeInterval: max(0, min(5, number(5))))
    }
    mouse(.leftMouseUp, number(3), number(4))
case "key":
    // System Events reaches the Java-hosted GLFW keyboard responder reliably.
    // Keep raw-key below for diagnosing differences in event injection.
    systemKey(Int(number(1)), args.count > 2 ? UInt64(number(2)) : 0)
case "scroll":
    let flags = args.count > 5 ? UInt64(number(5)) : 0
    scrollModifiers(flags, down: true)
    defer { scrollModifiers(flags, down: false) }
    mouse(.mouseMoved, number(1), number(2))
    // Pixel scrolling is a trackpad gesture. AppKit may discard an orphaned
    // phase-less pixel event after a new app/window launch; send a complete
    // begin/change/end sequence. Only the changed event carries movement.
    // https://developer.apple.com/documentation/coregraphics/cgeventfield/scrollwheeleventscrollphase
    for phase in [NSEvent.Phase.began, .changed, .ended] {
        let event = CGEvent(scrollWheelEvent2Source: nil, units: .pixel, wheelCount: 2,
                            wheel1: phase == .changed ? Int32(number(4)) : 0,
                            wheel2: phase == .changed ? Int32(number(3)) : 0, wheel3: 0)
        event?.location = CGPoint(x: number(1), y: number(2))
        event?.flags = CGEventFlags(rawValue: flags)
        event?.setIntegerValueField(.scrollWheelEventScrollPhase, value: Int64(phase.rawValue))
        event?.post(tap: .cghidEventTap)
        Thread.sleep(forTimeInterval: 0.05)
    }
case "hold-key":
    // Verify edge-triggered navigation does not repeat while a key stays down.
    guard args.count == 3, let code = UInt16(args[1]),
          let seconds = Double(args[2]), seconds.isFinite,
          seconds >= 0.05, seconds <= 2 else {
        fputs("hold-key requires a keycode and 0.05–2 seconds\n", stderr)
        exit(2)
    }
    let release = CGEvent(keyboardEventSource: nil, virtualKey: code, keyDown: false)
    defer { release?.post(tap: .cghidEventTap) }
    CGEvent(keyboardEventSource: nil, virtualKey: code, keyDown: true)?.post(tap: .cghidEventTap)
    Thread.sleep(forTimeInterval: seconds)
case "raw-key":
    for down in [true, false] {
        let event = CGEvent(keyboardEventSource: nil, virtualKey: CGKeyCode(number(1)), keyDown: down)
        event?.flags = CGEventFlags(rawValue: args.count > 2 ? UInt64(number(2)) : 0)
        event?.post(tap: .cghidEventTap)
        Thread.sleep(forTimeInterval: 0.025)
    }
case "text":
    let text = Array(args[1].utf16)
    for down in [true, false] {
        let event = CGEvent(keyboardEventSource: nil, virtualKey: 0, keyDown: down)
        event?.flags = []
        event?.keyboardSetUnicodeString(stringLength: text.count, unicodeString: text)
        event?.post(tap: .cghidEventTap)
    }
default:
    fputs("Usage: studio_input.swift [--target-pid PID] check-target | click x y | click-sequence x y x2 y2 [...] | double x y | drag x y x2 y2 | drag-hold x y x2 y2 seconds | key keycode [flags] | raw-key keycode [flags] | hold-key keycode seconds | scroll x y dx dy [flags] | paste string | check-copy expected | text string\n", stderr)
    exit(2)
}
Thread.sleep(forTimeInterval: 0.4)
