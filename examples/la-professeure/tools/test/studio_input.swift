// Opt-in native UI QA. Coordinates are global screen points; bring the game forward first.
// swift tools/test/studio_input.swift click 390 204
import Foundation
import CoreGraphics
import AppKit

let args = Array(CommandLine.arguments.dropFirst())
func number(_ i: Int) -> Double { Double(args[i])! }
func mouse(_ kind: CGEventType, _ x: Double, _ y: Double) {
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
case "double":
    mouse(.mouseMoved, number(1), number(2))
    for _ in 0..<2 {
        mouse(.leftMouseDown, number(1), number(2))
        mouse(.leftMouseUp, number(1), number(2))
    }
case "drag":
    let x = number(1), y = number(2), dx = number(3) - x, dy = number(4) - y
    mouse(.mouseMoved, x, y)
    mouse(.leftMouseDown, x, y)
    for i in 1...20 { mouse(.leftMouseDragged, x + dx * Double(i)/20, y + dy * Double(i)/20) }
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
    let event = CGEvent(scrollWheelEvent2Source: nil, units: .pixel, wheelCount: 2,
                        wheel1: Int32(number(4)), wheel2: Int32(number(3)), wheel3: 0)
    event?.flags = CGEventFlags(rawValue: flags)
    event?.post(tap: .cghidEventTap)
    Thread.sleep(forTimeInterval: 0.15)
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
    fputs("Usage: studio_input.swift click x y | double x y | drag x y x2 y2 | key keycode [flags] | raw-key keycode [flags] | scroll x y dx dy [flags] | paste string | check-copy expected | text string\n", stderr)
    exit(2)
}
Thread.sleep(forTimeInterval: 0.4)
