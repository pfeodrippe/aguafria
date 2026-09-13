#import <AppKit/AppKit.h>
#import <CoreAudio/CoreAudio.h>
#include <math.h>
#include <pthread.h>
#include "studio_gestures.h"

/* UI-thread only. No callbacks into reloadable Zig, JVM work, or audio state.
 * The stable dylib owns the AppKit monitor; Zig drains gestures after PollEvents.
 * Other app windows and other event types are never intercepted. */
static NSWindow *target;
static id monitor;
static lp_studio_pinch events[64];
static unsigned read_index;
static unsigned count;
static pthread_mutex_t queue_lock = PTHREAD_MUTEX_INITIALIZER;

/* Each content-addressed dylib has a distinct Objective-C class name, so hot
 * reload never replaces methods on a still-live class from an earlier build. */
#ifndef LP_STUDIO_IME_CLASS
#define LP_STUDIO_IME_CLASS LPStudioComposition
#endif

@interface LP_STUDIO_IME_CLASS : NSView<NSTextInputClient>
@property(nonatomic, weak) NSView<NSTextInputClient> *destination;
@property(nonatomic, strong) NSAttributedString *marked;
@property(nonatomic) NSRange selection;
@property(nonatomic, strong) NSEvent *keyEvent;
@end

@implementation LP_STUDIO_IME_CLASS
- (BOOL)acceptsFirstResponder { return YES; }
- (BOOL)isFlipped { return YES; }
- (NSView *)hitTest:(NSPoint)point { return nil; }
- (BOOL)hasMarkedText { return self.marked.length > 0; }
- (NSRange)markedRange {
    return self.hasMarkedText ? NSMakeRange(0, self.marked.length)
                              : NSMakeRange(NSNotFound, 0);
}
- (NSRange)selectedRange { return self.selection; }
- (NSArray<NSAttributedStringKey> *)validAttributesForMarkedText {
    return @[NSUnderlineStyleAttributeName, NSMarkedClauseSegmentAttributeName];
}
- (void)setMarkedText:(id)value selectedRange:(NSRange)selection
     replacementRange:(NSRange)replacement {
    NSString *text = [value isKindOfClass:NSAttributedString.class] ? [value string] : value;
    if (![text isKindOfClass:NSString.class]) return;
    NSMutableAttributedString *marked = [[NSMutableAttributedString alloc]
        initWithString:text attributes:@{
            NSFontAttributeName: [NSFont systemFontOfSize:14],
            NSForegroundColorAttributeName: [NSColor colorWithSRGBRed:0.09 green:0.15 blue:0.22 alpha:1],
            NSUnderlineStyleAttributeName: @(NSUnderlineStyleSingle)}];
    NSUInteger start = MIN(selection.location, marked.length);
    self.selection = NSMakeRange(start, MIN(selection.length, marked.length - start));
    if (self.selection.length)
        [marked addAttribute:NSBackgroundColorAttributeName value:NSColor.selectedTextBackgroundColor
                       range:self.selection];
    self.marked = marked;
    NSRect frame = self.frame;
    frame.size.width = MIN(marked.size.width + 6.0,
                           MIN(280.0, MAX(1.0, NSWidth(self.superview.bounds) - frame.origin.x)));
    self.frame = frame;
    self.needsDisplay = YES;
    [self.inputContext invalidateCharacterCoordinates];
}
- (void)unmarkText {
    self.marked = nil;
    self.selection = NSMakeRange(NSNotFound, 0);
    self.needsDisplay = YES;
}
- (void)insertText:(id)value replacementRange:(NSRange)replacement {
    /* Only committed characters reach the existing GLFW character callback.
     * Its grapheme-aware edit limit and undo checkpoint remain authoritative. */
    [self unmarkText];
    [self.destination insertText:value replacementRange:NSMakeRange(NSNotFound, 0)];
}
- (NSAttributedString *)attributedSubstringForProposedRange:(NSRange)range
                                              actualRange:(NSRangePointer)actual {
    if (!self.hasMarkedText || range.location >= self.marked.length) {
        if (actual) *actual = NSMakeRange(NSNotFound, 0);
        return nil;
    }
    range.length = MIN(range.length, self.marked.length - range.location);
    if (actual) *actual = range;
    return [self.marked attributedSubstringFromRange:range];
}
- (NSRect)firstRectForCharacterRange:(NSRange)range actualRange:(NSRangePointer)actual {
    if (actual) *actual = self.markedRange;
    return [self.window convertRectToScreen:[self convertRect:self.bounds toView:nil]];
}
- (NSUInteger)characterIndexForPoint:(NSPoint)point { return NSNotFound; }
- (void)drawRect:(NSRect)dirty {
    if (!self.hasMarkedText) return;
    [[NSColor colorWithSRGBRed:1 green:0.97 blue:0.90 alpha:1] setFill];
    NSRectFill(self.bounds);
    [self.marked drawInRect:NSInsetRect(self.bounds, 3, 2)];
}
- (void)doCommandBySelector:(SEL)selector {
    if (self.hasMarkedText) {
        if (selector == @selector(cancelOperation:)) [self unmarkText];
        return;
    }
    if (self.keyEvent) [self.destination keyDown:self.keyEvent];
}
- (void)keyDown:(NSEvent *)event {
    /* Application shortcuts/edit commands retain the existing GLFW path, but
     * AppKit owns all keys during marked text, including Enter and Escape. */
    BOOL editing = event.keyCode == 36 || event.keyCode == 48 || event.keyCode == 51 ||
                   event.keyCode == 53 || event.keyCode == 76 || event.keyCode == 117 ||
                   (event.keyCode >= 115 && event.keyCode <= 126);
    if (!self.hasMarkedText && (editing ||
        (event.modifierFlags & (NSEventModifierFlagCommand | NSEventModifierFlagControl)))) {
        [self.destination keyDown:event];
        return;
    }
    self.keyEvent = event;
    [self interpretKeyEvents:@[event]];
    self.keyEvent = nil;
}
- (void)keyUp:(NSEvent *)event { [self.destination keyUp:event]; }
- (void)flagsChanged:(NSEvent *)event { [self.destination flagsChanged:event]; }
@end

static LP_STUDIO_IME_CLASS *composition;

void lp_studio_gestures_detach(void) {
    lp_studio_ime_cancel((__bridge void *)target);
    if (monitor) [NSEvent removeMonitor:monitor];
    monitor = nil;
    target = nil;
    pthread_mutex_lock(&queue_lock);
    read_index = count = 0;
    pthread_mutex_unlock(&queue_lock);
}

bool lp_studio_gestures_submit(void *cocoa_window, double amount, double x, double y) {
    if (!target || (__bridge NSWindow *)cocoa_window != target ||
        !isfinite(amount) || !isfinite(x) || !isfinite(y) || amount == 0)
        return false;
    pthread_mutex_lock(&queue_lock);
    /* Exponential zoom in Zig makes additive overflow coalescing preserve scale. */
    if (count == 64) {
        unsigned last = (read_index + count - 1) % 64;
        events[last].magnification += amount;
        events[last].x = x;
        events[last].y = y;
    } else {
        events[(read_index + count) % 64] = (lp_studio_pinch){amount, x, y};
        count++;
    }
    pthread_mutex_unlock(&queue_lock);
    return true;
}

bool lp_studio_gestures_attach(void *cocoa_window) {
    NSWindow *window = (__bridge NSWindow *)cocoa_window;
    if (!window) return false;
    if (target == window && monitor) return true;
    lp_studio_gestures_detach();
    target = window;
    monitor = [NSEvent addLocalMonitorForEventsMatchingMask:NSEventMaskMagnify
                                                  handler:^NSEvent *(NSEvent *event) {
        if (event.window != target) return event;
        double amount = event.magnification;
        if (!isfinite(amount) || amount == 0 || event.phase == NSEventPhaseCancelled)
            return event;
        NSView *view = target.contentView;
        NSPoint point = [view convertPoint:event.locationInWindow fromView:nil];
        double y = view.isFlipped ? point.y : NSHeight(view.bounds) - point.y;
        if (!isfinite(point.x) || !isfinite(y)) return event;
        lp_studio_gestures_submit((__bridge void *)target, amount, point.x, y);
        return nil;
    }];
    return monitor != nil;
}

bool lp_studio_gestures_poll(lp_studio_pinch *event) {
    if (!event) return false;
    pthread_mutex_lock(&queue_lock);
    if (count == 0) {
        pthread_mutex_unlock(&queue_lock);
        return false;
    }
    *event = events[read_index];
    read_index = (read_index + 1) % 64;
    count--;
    pthread_mutex_unlock(&queue_lock);
    return true;
}

bool lp_studio_ime_active(void *cocoa_window) {
    NSWindow *window = (__bridge NSWindow *)cocoa_window;
    if (window && composition.window == window && composition.hasMarkedText) return true;
    NSView *view = window.contentView;
    return [view conformsToProtocol:@protocol(NSTextInputClient)] &&
           [(id<NSTextInputClient>)view hasMarkedText];
}

void lp_studio_ime_cancel(void *cocoa_window) {
    NSWindow *window = (__bridge NSWindow *)cocoa_window;
    if (window && composition.window == window) {
        [composition.inputContext discardMarkedText];
        [composition unmarkText];
        if (window.firstResponder == composition)
            [window makeFirstResponder:window.contentView];
        [composition removeFromSuperview];
        composition = nil;
    }
    NSView *view = window.contentView;
    if ([view conformsToProtocol:@protocol(NSTextInputClient)]) {
        [view.inputContext discardMarkedText];
        [(id<NSTextInputClient>)view unmarkText];
    }
}

bool lp_studio_ime_focus(void *cocoa_window, double x, double y, double height) {
    NSWindow *window = (__bridge NSWindow *)cocoa_window;
    NSView *view = window.contentView;
    if (!window || !view || !isfinite(x) || !isfinite(y) || !isfinite(height) || height <= 0 ||
        ![view conformsToProtocol:@protocol(NSTextInputClient)]) return false;
    if (composition && composition.window != window)
        lp_studio_ime_cancel((__bridge void *)composition.window);
    if (!composition) {
        composition = [[LP_STUDIO_IME_CLASS alloc] initWithFrame:NSZeroRect];
        composition.destination = (NSView<NSTextInputClient> *)view;
        composition.selection = NSMakeRange(NSNotFound, 0);
        [view addSubview:composition];
    }
    double width = MIN(MAX(1.0, composition.marked.size.width + 6.0),
                       MIN(280.0, NSWidth(view.bounds)));
    x = MAX(0, MIN(x, NSWidth(view.bounds) - width));
    y = MAX(0, MIN(y, NSHeight(view.bounds) - height));
    NSRect frame = NSMakeRect(x, view.isFlipped ? y : NSHeight(view.bounds) - y - height,
                              width, height);
    if (!NSEqualRects(frame, composition.frame)) {
        composition.frame = frame;
        [composition.inputContext invalidateCharacterCoordinates];
    }
    return window.firstResponder == composition || [window makeFirstResponder:composition];
}

int lp_studio_output_mute(const char *device_uid) {
    if (!device_uid || !device_uid[0]) return -1;
    NSString *uid = [NSString stringWithUTF8String:device_uid];
    if (!uid) return -1;
    CFStringRef key = (__bridge CFStringRef)uid;
    AudioDeviceID device = kAudioObjectUnknown;
    AudioValueTranslation translation = {&key, sizeof(key), &device, sizeof(device)};
    AudioObjectPropertyAddress address = {kAudioHardwarePropertyDeviceForUID,
                                         kAudioObjectPropertyScopeGlobal,
                                         kAudioObjectPropertyElementMain};
    UInt32 size = sizeof(translation);
    if (AudioObjectGetPropertyData(kAudioObjectSystemObject, &address, 0, NULL,
                                  &size, &translation) != noErr ||
        device == kAudioObjectUnknown) return -1;
    address = (AudioObjectPropertyAddress){kAudioDevicePropertyMute,
                                          kAudioDevicePropertyScopeOutput,
                                          kAudioObjectPropertyElementMain};
    if (!AudioObjectHasProperty(device, &address)) return -1;
    UInt32 muted = 0;
    size = sizeof(muted);
    if (AudioObjectGetPropertyData(device, &address, 0, NULL, &size, &muted) != noErr)
        return -1;
    return muted ? 1 : 0;
}
