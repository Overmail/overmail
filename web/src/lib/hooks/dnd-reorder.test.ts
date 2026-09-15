import {expect, test} from "bun:test";
import {locate, moveTo, project, sameOrder, slotFor} from "./dnd-reorder";

const order = {left: ["a", "b", "c"], right: ["d"]};

test("locate finds the zone and the index", () => {
    expect(locate(order, "a")).toEqual({zone: "left", index: 0});
    expect(locate(order, "c")).toEqual({zone: "left", index: 2});
    expect(locate(order, "d")).toEqual({zone: "right", index: 0});
    expect(locate(order, "nope")).toBeNull();
});

test("moveTo reorders within a zone", () => {
    const move = {id: "a", from: {zone: "left", index: 0}, to: {zone: "left", index: 2}};

    expect(moveTo(order, move)).toEqual({left: ["b", "c", "a"], right: ["d"]});
});

test("moveTo counts the target index among the other ids", () => {
    // "a" out first, so slot 1 is between "b" and "c" -- not after "c".
    const move = {id: "a", from: {zone: "left", index: 0}, to: {zone: "left", index: 1}};

    expect(moveTo(order, move)).toEqual({left: ["b", "a", "c"], right: ["d"]});
});

test("moveTo carries an id into another zone", () => {
    const move = {id: "b", from: {zone: "left", index: 1}, to: {zone: "right", index: 0}};

    expect(moveTo(order, move)).toEqual({left: ["a", "c"], right: ["b", "d"]});
});

test("moveTo puts an index past the end at the end", () => {
    const move = {id: "d", from: {zone: "right", index: 0}, to: {zone: "left", index: 99}};

    expect(moveTo(order, move)).toEqual({left: ["a", "b", "c", "d"], right: []});
});

test("moveTo leaves an order a zone of that name is not in alone", () => {
    const move = {id: "a", from: {zone: "left", index: 0}, to: {zone: "nope", index: 0}};

    expect(moveTo(order, move)).toBe(order);
});

test("slotFor counts the midpoints before the cursor", () => {
    const rects = [
        {start: 0, size: 10},
        {start: 10, size: 10},
        {start: 20, size: 10},
    ];

    expect(slotFor(rects, 0)).toBe(0);
    expect(slotFor(rects, 4)).toBe(0);
    expect(slotFor(rects, 6)).toBe(1);
    expect(slotFor(rects, 16)).toBe(2);
    expect(slotFor(rects, 100)).toBe(3);
    expect(slotFor([], 100)).toBe(0);
});

test("sameOrder compares zones and positions", () => {
    expect(sameOrder(order, {left: ["a", "b", "c"], right: ["d"]})).toBeTrue();
    expect(sameOrder(order, {left: ["a", "c", "b"], right: ["d"]})).toBeFalse();
    expect(sameOrder(order, {left: ["a", "b", "c"], right: []})).toBeFalse();
    expect(sameOrder(order, {left: ["a", "b", "c"]})).toBeFalse();
});

type Item = {key: string; name: string};

const key = (item: Item) => item.key;

test("project hands out the items in the order's order", () => {
    const live = {left: [{key: "a", name: "A"}, {key: "b", name: "B"}], right: []};

    expect(project({left: ["b", "a"], right: []}, live, key)).toEqual({
        left: [{key: "b", name: "B"}, {key: "a", name: "A"}],
        right: [],
    });
});

test("project follows an id the drag moved into another zone", () => {
    const live = {left: [{key: "a", name: "A"}], right: []};

    expect(project({left: [], right: ["a"]}, live, key)).toEqual({
        left: [],
        right: [{key: "a", name: "A"}],
    });
});

test("project puts an item that arrived mid-drag at the end of its live zone", () => {
    const live = {left: [{key: "a", name: "A"}, {key: "new", name: "N"}], right: []};

    expect(project({left: ["a"], right: []}, live, key)).toEqual({
        left: [{key: "a", name: "A"}, {key: "new", name: "N"}],
        right: [],
    });
});

test("project drops an id nothing is live for any more", () => {
    const live = {left: [{key: "a", name: "A"}], right: []};

    expect(project({left: ["a", "gone"], right: []}, live, key)).toEqual({
        left: [{key: "a", name: "A"}],
        right: [],
    });
});

test("project renders a zone the order says nothing about", () => {
    const live = {left: [{key: "a", name: "A"}]};

    expect(project({}, live, key)).toEqual({left: [{key: "a", name: "A"}]});
});
