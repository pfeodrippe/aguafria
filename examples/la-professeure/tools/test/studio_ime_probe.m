#import <AppKit/AppKit.h>
#include "studio_ime_probe.h"
#include <math.h>

bool lp_studio_ime_probe_mark(void *cocoa_window, const char *text) {
    NSWindow *window = (__bridge NSWindow *)cocoa_window;
    id view = [window.firstResponder conformsToProtocol:@protocol(NSTextInputClient)]
                ? window.firstResponder : window.contentView;
    NSString *value = text ? [NSString stringWithUTF8String:text] : nil;
    if (!value || ![view conformsToProtocol:@protocol(NSTextInputClient)]) return false;
    [(id<NSTextInputClient>)view setMarkedText:value
                               selectedRange:NSMakeRange(value.length, 0)
                            replacementRange:NSMakeRange(NSNotFound, 0)];
    return true;
}

bool lp_studio_ime_probe_commit(void *cocoa_window, const char *text) {
    NSWindow *window = (__bridge NSWindow *)cocoa_window;
    id view = [window.firstResponder conformsToProtocol:@protocol(NSTextInputClient)]
                ? window.firstResponder : window.contentView;
    NSString *value = text ? [NSString stringWithUTF8String:text] : nil;
    if (!value || ![view conformsToProtocol:@protocol(NSTextInputClient)]) return false;
    [(id<NSTextInputClient>)view insertText:value replacementRange:NSMakeRange(NSNotFound, 0)];
    [(id<NSTextInputClient>)view unmarkText];
    return true;
}

bool lp_studio_ime_probe_presentation(void *cocoa_window, const char *text,
                                    double x, double y, const char *png_path) {
    NSWindow *window = (__bridge NSWindow *)cocoa_window;
    id client = window.firstResponder;
    if (![client isKindOfClass:NSView.class] ||
        ![client conformsToProtocol:@protocol(NSTextInputClient)]) return false;
    NSString *expected = text ? [NSString stringWithUTF8String:text] : nil;
    if (!expected || ![client hasMarkedText]) return false;
    NSRange actual;
    NSAttributedString *marked = [client attributedSubstringForProposedRange:
        NSMakeRange(0, expected.length) actualRange:&actual];
    if (![marked.string isEqualToString:expected] || actual.location != 0 ||
        actual.length != expected.length) return false;
    NSRect rect = [client firstRectForCharacterRange:actual actualRange:NULL];
    NSView *view = window.contentView;
    NSRect local = [view convertRect:[window convertRectFromScreen:rect] fromView:nil];
    double top = view.isFlipped ? local.origin.y : NSHeight(view.bounds) - NSMaxY(local);
    if (fabs(local.origin.x - x) > 0.5 || fabs(top - y) > 0.5 || rect.size.height < 20 ||
        fabs(rect.size.width - MIN(280.0, marked.size.width + 6.0)) > 0.5)
        return false;
    if (png_path) {
        NSView *native = client;
        NSBitmapImageRep *bitmap = [native bitmapImageRepForCachingDisplayInRect:native.bounds];
        [native cacheDisplayInRect:native.bounds toBitmapImageRep:bitmap];
        NSData *png = [bitmap representationUsingType:NSBitmapImageFileTypePNG properties:@{}];
        NSString *path = [NSString stringWithUTF8String:png_path];
        if (!png || !path || ![png writeToFile:path atomically:YES]) return false;
    }
    return true;
}

bool lp_studio_ime_probe_key(void *cocoa_window, unsigned short code, const char *text) {
    NSWindow *window = (__bridge NSWindow *)cocoa_window;
    NSString *characters = text ? [NSString stringWithUTF8String:text] : nil;
    if (!window || !characters || ![window.firstResponder isKindOfClass:NSView.class]) return false;
    NSEvent *event = [NSEvent keyEventWithType:NSEventTypeKeyDown location:NSZeroPoint
        modifierFlags:0 timestamp:0 windowNumber:window.windowNumber context:nil
        characters:characters charactersIgnoringModifiers:characters isARepeat:NO keyCode:code];
    [window.firstResponder keyDown:event];
    return true;
}
