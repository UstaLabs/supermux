#import "WVGlue.h"
#include <math.h>
#include <stdio.h>
#include <stdlib.h>

/** Deeper than this, a result isn't written (D5): the writer recurses, and AppKit's thread has a stack to keep. */
static const int WVMaxDepth = 256;

/** string as a JSON string, escaped as kotlinx.serialization escapes (the iOS engine's results): ", \, controls. */
static void WVAppendString(NSMutableString *out, NSString *string) {
    [out appendString:@"\""];
    NSUInteger length = string.length;
    for (NSUInteger i = 0; i < length; i++) {
        unichar c = [string characterAtIndex:i];
        switch (c) {
            case '"': [out appendString:@"\\\""]; break;
            case '\\': [out appendString:@"\\\\"]; break;
            case '\n': [out appendString:@"\\n"]; break;
            case '\r': [out appendString:@"\\r"]; break;
            case '\t': [out appendString:@"\\t"]; break;
            case '\b': [out appendString:@"\\b"]; break;
            case '\f': [out appendString:@"\\f"]; break;
            default:
                if (c < 0x20) {
                    [out appendFormat:@"\\u%04x", (unsigned int)c];
                } else {
                    [out appendFormat:@"%C", c];
                }
        }
    }
    [out appendString:@"\""];
}

/** A number as iOS's engine writes it: integral without a fraction, else the shortest form that reads back; nil if not finite. */
static NSString *WVNumber(double value) {
    if (!isfinite(value)) return nil;
    if (value == floor(value) && fabs(value) < 9007199254740992.0) return [NSString stringWithFormat:@"%lld", (long long)value];
    char buffer[40];
    for (int precision = 1; precision <= 17; precision++) {
        snprintf(buffer, sizeof buffer, "%.*g", precision, value);
        if (strtod(buffer, NULL) == value) break;
    }
    return [NSString stringWithUTF8String:buffer];
}

static BOOL WVAppend(NSMutableString *out, id value, int depth) {
    if (depth > WVMaxDepth) return NO;
    if (value == nil || value == [NSNull null]) {
        [out appendString:@"null"];
        return YES;
    }
    if ([value isKindOfClass:[NSString class]]) {
        WVAppendString(out, value);
        return YES;
    }
    if ([value isKindOfClass:[NSNumber class]]) {
        if (CFGetTypeID((__bridge CFTypeRef)value) == CFBooleanGetTypeID()) {
            [out appendString:[value boolValue] ? @"true" : @"false"];
            return YES;
        }
        NSString *number = WVNumber([value doubleValue]);
        if (!number) return NO;
        [out appendString:number];
        return YES;
    }
    if ([value isKindOfClass:[NSArray class]]) {
        [out appendString:@"["];
        BOOL first = YES;
        for (id item in (NSArray *)value) {
            if (!first) [out appendString:@","];
            first = NO;
            if (!WVAppend(out, item, depth + 1)) return NO;
        }
        [out appendString:@"]"];
        return YES;
    }
    if ([value isKindOfClass:[NSDictionary class]]) {
        [out appendString:@"{"];
        __block BOOL first = YES;
        __block BOOL ok = YES;
        [(NSDictionary *)value enumerateKeysAndObjectsUsingBlock:^(id key, id item, BOOL *stop) {
            if (![key isKindOfClass:[NSString class]]) {
                ok = NO;
                *stop = YES;
                return;
            }
            if (!first) [out appendString:@","];
            first = NO;
            WVAppendString(out, key);
            [out appendString:@":"];
            if (!WVAppend(out, item, depth + 1)) {
                ok = NO;
                *stop = YES;
            }
        }];
        if (!ok) return NO;
        [out appendString:@"}"];
        return YES;
    }
    return NO;
}

NSString *WVJsonOf(id value) {
    NSMutableString *out = [NSMutableString string];
    return WVAppend(out, value, 0) ? out : nil;
}

/** WVJsonOf over fixed inputs, one line each ("nil" where it writes nothing): MacNativeLibraryMacTest pins them. */
JNIEXPORT jstring JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_jsonSelfTest(JNIEnv *env, jclass type) {
    id deep = @[];
    for (int i = 0; i < 300; i++) deep = @[ deep ];
    NSArray *inputs = @[
        [NSString stringWithFormat:@"a\"b\\c\nd%Cé", (unichar)1],
        @YES,
        @NO,
        @42.0,
        @0.5,
        @(NAN),
        [NSNull null],
        @[ @1, @"x", @[ @YES ] ],
        @{ @"k" : @"v" },
        [NSDate date],
        deep,
        @1e21,
        @(-3.0),
    ];
    NSMutableArray<NSString *> *lines = [NSMutableArray array];
    for (id input in inputs) [lines addObject:WVJsonOf(input) ?: @"nil"];
    return WVJavaString(env, [lines componentsJoinedByString:@"\n"]);
}
