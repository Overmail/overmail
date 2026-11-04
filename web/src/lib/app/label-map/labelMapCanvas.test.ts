import {expect, test} from "bun:test";
import {contrast, fontSizeFor, nodeAt, parseHex, relativeLuminance, screenToWorld, textColorOn} from "./labelMapCanvas";
import type {LabelMapNode} from "./LabelMapSimulation";

function node(id: string, x: number, y: number, radius: number): LabelMapNode {
    return {id, x, y, radius, label: {id, name: id, color: "#000000", email_count: 1, relations: []}};
}

test("the middle of the viewport is the camera's offset away from the origin", () => {
    expect(screenToWorld(400, 300, 800, 600, {cameraX: 0, cameraY: 0, zoom: 1})).toEqual([0, 0]);
    expect(screenToWorld(400, 300, 800, 600, {cameraX: 100, cameraY: -50, zoom: 1})).toEqual([-100, 50]);
});

test("zooming in makes a screen distance a shorter one in the world", () => {
    expect(screenToWorld(600, 300, 800, 600, {cameraX: 0, cameraY: 0, zoom: 2})).toEqual([100, 0]);
});

test("a point inside a large circle is that label, even when a small one's centre is nearer", () => {
    const large = node("large", 0, 0, 100);
    const small = node("small", 130, 0, 20);

    // 95 from the large centre, 35 from the small one.
    expect(nodeAt([small, large], 95, 0)?.id).toBe("large");
});

test("a point between the circles is no label", () => {
    expect(nodeAt([node("a", 0, 0, 10), node("b", 50, 0, 10)], 25, 0)).toBeNull();
});

function contrastOf(text: string, background: string): number {
    return contrast(relativeLuminance(parseHex(text)!), relativeLuminance(parseHex(background)!));
}

test("names go dark on light colours and light on dark ones", () => {
    const onYellow = parseHex(textColorOn("#ffeb3b"))!;
    const onNavy = parseHex(textColorOn("#1a237e"))!;

    expect(relativeLuminance(onYellow)).toBeLessThan(relativeLuminance(parseHex("#ffeb3b")!));
    expect(relativeLuminance(onNavy)).toBeGreaterThan(relativeLuminance(parseHex("#1a237e")!));
    expect(textColorOn("not a colour")).toBe("#ffffff");
});

test("a name keeps the hue of its label", () => {
    // A red label's name is a dark red, not black: red stays the strongest channel.
    const [r, g, b] = parseHex(textColorOn("#ff8a80"))!;

    expect(r).toBeGreaterThan(g);
    expect(r).toBeGreaterThan(b);
    expect(textColorOn("#ff8a80")).not.toBe("#000000");
});

test("a name is readable on every colour", () => {
    for (const color of ["#ffeb3b", "#1a237e", "#767676", "#4caf50", "#e91e63", "#00bcd4", "#ffffff", "#000000"]) {
        expect(contrastOf(textColorOn(color), color)).toBeGreaterThanOrEqual(4.5);
    }
});

test("a name's corners stay inside its circle, with room to spare", () => {
    for (const widthPerPixel of [0.5, 2, 6, 15]) {
        const radius = 40;
        const size = fontSizeFor(radius, widthPerPixel);
        const corner = Math.hypot((size * widthPerPixel) / 2, (size * 0.8) / 2);

        expect(corner).toBeLessThan(radius * 0.9);
    }
});

test("a longer name is written smaller, a larger circle larger", () => {
    expect(fontSizeFor(40, 8)).toBeLessThan(fontSizeFor(40, 4));
    expect(fontSizeFor(80, 4)).toBeCloseTo(fontSizeFor(40, 4) * 2);
});

test("a short name does not grow taller than the circle has room for", () => {
    expect(fontSizeFor(40, 0.5)).toBeCloseTo(40 * 0.82);
});
