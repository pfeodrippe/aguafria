// Opt-in native UI QA. Coordinates are global screen points; bring the game forward first.
// swift tools/test/studio_input.swift click 390 204
import Foundation
import CoreGraphics

let args = Array(CommandLine.arguments.dropFirst())
func number(_ i: Int) -> Double { Double(args[i])! }
func mouse(_ kind: CGEventType, _ x: Double, _ y: Double) {
    CGEvent(mouseEventSource: nil, mouseType: kind,
            mouseCursorPosition: CGPoint(x: x, y: y), mouseButton: .left)?.post(tap: .cghidEventTap)
    Thread.sleep(forTimeInterval: 0.025)
}
switch args.first {
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
    fputs("Usage: studio_input.swift click x y | double x y | drag x y x2 y2 | key keycode | text string\n", stderr)
    exit(2)
}
Thread.sleep(forTimeInterval: 0.4)
