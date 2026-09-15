import {expect, test} from "bun:test";
import {neighbourInOrder, reordered} from "./reorder";

const order = ["a", "b", "c"];

test("the view a row sits behind", () => {
    expect(neighbourInOrder(order, "b")).toBe("a");
    expect(neighbourInOrder(order, "c")).toBe("b");
});

test("nothing in front of the first row, and none for a row that is not there", () => {
    expect(neighbourInOrder(order, "a")).toBeNull();
    expect(neighbourInOrder(order, "zzz")).toBeNull();
});

test("what the list reads as right after the drop", () => {
    expect(reordered(order, "c", "a")).toEqual(["a", "c", "b"]);
    expect(reordered(order, "c", null)).toEqual(["c", "a", "b"]);
    expect(reordered(order, "a", "c")).toEqual(["b", "c", "a"]);
    // Where it already is: the same list, not a list with it moved by one.
    expect(reordered(order, "b", "a")).toEqual(["a", "b", "c"]);
});

test("a neighbour nobody has leaves the list alone", () => {
    expect(reordered(order, "a", "zzz")).toEqual(order);
});
