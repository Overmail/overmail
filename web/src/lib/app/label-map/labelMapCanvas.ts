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
 * What a label's name is written in on top of its colour [hex] (`#rrggbb`): dark on a light one,
 * light on a dark one. Anything that is not a hex colour is taken as dark.
 */
export function textColorOn(hex: string): string {
    const match = /^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(hex);
    if (!match) return "#ffffff";

    const [r, g, b] = match.slice(1).map((channel) => {
        const value = parseInt(channel, 16) / 255;
        return value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
    });
    const luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b;

    // Where black and white have the same contrast against the colour.
    return luminance > 0.179 ? "#111111" : "#ffffff";
}

