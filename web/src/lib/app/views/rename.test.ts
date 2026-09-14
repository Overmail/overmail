import {expect, test} from "bun:test";
import {renamedTo} from "./rename";

test("a new name is trimmed", () => {
    expect(renamedTo("First", "  Wichtiges  ")).toBe("Wichtiges");
});

test("an empty name keeps the old one", () => {
    expect(renamedTo("First", "")).toBeNull();
    expect(renamedTo("First", "   ")).toBeNull();
});

test("the name it already has is no change", () => {
    expect(renamedTo("First", "First")).toBeNull();
    // Trimmed first, so whitespace around the same name is not a rename either.
    expect(renamedTo("First", " First ")).toBeNull();
});
