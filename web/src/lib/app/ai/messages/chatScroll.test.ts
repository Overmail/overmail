import {expect, test} from "bun:test";
import {AT_BOTTOM_TOLERANCE, isAtBottom} from "./chatScroll";

const box = (scrollTop: number, scrollHeight = 1000, clientHeight = 400) => ({
    scrollTop,
    scrollHeight,
    clientHeight,
});

test("the end, and a hair short of it", () => {
    expect(isAtBottom(box(600))).toBe(true);
    expect(isAtBottom(box(600 - AT_BOTTOM_TOLERANCE))).toBe(true);
    // Rounding can put it past the end; that is still the end.
    expect(isAtBottom(box(600.4))).toBe(true);
});

test("anywhere above it is not", () => {
    expect(isAtBottom(box(600 - AT_BOTTOM_TOLERANCE - 1))).toBe(false);
    expect(isAtBottom(box(0))).toBe(false);
});

test("a conversation that fits on screen is at its end", () => {
    expect(isAtBottom(box(0, 400, 400))).toBe(true);
});
