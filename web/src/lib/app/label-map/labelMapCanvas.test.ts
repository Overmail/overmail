import {expect, test} from "bun:test";
import {screenToWorld, textColorOn} from "./labelMapCanvas";

test("the middle of the viewport is the camera's offset away from the origin", () => {
    expect(screenToWorld(400, 300, 800, 600, {cameraX: 0, cameraY: 0, zoom: 1})).toEqual([0, 0]);
    expect(screenToWorld(400, 300, 800, 600, {cameraX: 100, cameraY: -50, zoom: 1})).toEqual([-100, 50]);
});

test("zooming in makes a screen distance a shorter one in the world", () => {
    expect(screenToWorld(600, 300, 800, 600, {cameraX: 0, cameraY: 0, zoom: 2})).toEqual([100, 0]);
});

test("names go dark on light colours and light on dark ones", () => {
    expect(textColorOn("#ffeb3b")).toBe("#111111");
    expect(textColorOn("#1a237e")).toBe("#ffffff");
    expect(textColorOn("not a colour")).toBe("#ffffff");
});
