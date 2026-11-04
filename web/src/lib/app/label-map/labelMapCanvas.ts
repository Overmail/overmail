import type {LabelMapNode} from "$lib/app/label-map/LabelMapSimulation";

/**
 * Where the camera of the map stands, in the terms `Freecam` keeps it: an offset of the world's
 * origin from the middle of the viewport, and a scale around that point.
 */
export type LabelMapCamera = {
    cameraX: number;
    cameraY: number;
    zoom: number;
};

/**
 * The point of the world under [offsetX]/[offsetY], a position relative to the top left corner of
 * a viewport [width] by [height] -- the inverse of what `Freecam`'s transform does.
 */
export function screenToWorld(
    offsetX: number,
    offsetY: number,
    width: number,
    height: number,
    camera: LabelMapCamera,
): [number, number] {
    return [
        (offsetX - width / 2 - camera.cameraX) / camera.zoom,
        (offsetY - height / 2 - camera.cameraY) / camera.zoom,
    ];
}

/**
 * The node whose circle holds the world point [x]/[y], or null.
 *
 * Not d3's `simulation.find`: that is the nearest centre, and next to a large label a small one's
 * centre can be nearer than the large one's although the point is inside the large circle.
 */
export function nodeAt(nodes: readonly LabelMapNode[], x: number, y: number): LabelMapNode | null {
    for (const node of nodes) {
        const dx = x - (node.x ?? 0);
        const dy = y - (node.y ?? 0);
        if (dx * dx + dy * dy <= node.radius * node.radius) return node;
    }

    return null;
}

/**
 * What a label's name is written in on top of its colour [hex] (`#rrggbb`): the same colour, much
 * darker on a light label and much lighter on a dark one, so the name keeps the label's hue.
 *
 * It goes only as far towards black or white as it takes to be readable -- a contrast of
 * [MIN_CONTRAST] -- and all the way where nothing short of that is. Anything that is not a hex
 * colour is taken as dark.
 */
export function textColorOn(hex: string): string {
    const background = parseHex(hex);
    if (background === null) return "#ffffff";

    const backgroundLuminance = relativeLuminance(background);
    // Where black and white have the same contrast against the colour.
    const target = backgroundLuminance > 0.179 ? [0, 0, 0] : [255, 255, 255];

    let text = target;
    for (let amount = 0.6; amount < 1; amount += 0.05) {
        const mixed = background.map((channel, i) => Math.round(channel + (target[i] - channel) * amount));
        if (contrast(relativeLuminance(mixed), backgroundLuminance) >= MIN_CONTRAST) {
            text = mixed;
            break;
        }
    }

    return "#" + text.map((channel) => channel.toString(16).padStart(2, "0")).join("");
}

/** What WCAG asks of normal text. */
const MIN_CONTRAST = 4.5;

export function parseHex(hex: string): number[] | null {
    const match = /^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(hex);

    return match ? match.slice(1).map((channel) => parseInt(channel, 16)) : null;
}

/** The relative luminance of an sRGB colour with channels from 0 to 255. */
export function relativeLuminance(rgb: number[]): number {
    const [r, g, b] = rgb.map((channel) => {
        const value = channel / 255;
        return value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
    });

    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/** The contrast ratio between two relative luminances. */
export function contrast(a: number, b: number): number {
    return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
}

/** How much of a circle's radius stays free around its name. */
const NAME_PADDING = 0.18;

/** How tall a line of text is for its font size, as far as fitting it into a circle goes. */
const LINE_HEIGHT = 0.8;

/**
 * The font size a name is written in so that it fills a circle of [radius], with some room around
 * it. [widthPerPixel] is how wide the name is per pixel of font size.
 *
 * The name is a box centred in the circle, and the box is as large as its corners allow. A short
 * name would grow taller than looks right, so the size is capped at the free radius.
 */
export function fontSizeFor(radius: number, widthPerPixel: number): number {
    const free = radius * (1 - NAME_PADDING);
    const halfWidth = widthPerPixel / 2;
    const halfHeight = LINE_HEIGHT / 2;

    return Math.min(free, free / Math.hypot(halfWidth, halfHeight));
}
