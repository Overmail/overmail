import {expect, test} from "bun:test";
import {MailSelection} from "./mailSelection";

test("the first tick puts the table into selection mode and the last one taken back ends it", () => {
    const selection = new MailSelection();

    expect(selection.active).toBe(false);

    selection.set("a", true);
    expect(selection.active).toBe(true);
    expect(selection.has("a")).toBe(true);

    selection.set("a", false);
    expect(selection.active).toBe(false);
    expect(selection.ids).toEqual([]);
});

test("ticking the same mail twice is one tick, and untick is the way back", () => {
    const selection = new MailSelection();

    selection.set("a", true);
    selection.set("a", true);
    expect(selection.count).toBe(1);

    selection.toggle("a");
    expect(selection.has("a")).toBe(false);

    selection.toggle("a");
    expect(selection.has("a")).toBe(true);
});

test("a stretch counts how much of it is ticked, which is what the third state is", () => {
    const selection = new MailSelection();
    const stretch = ["a", "b", "c"];

    expect(selection.countOf(stretch)).toBe(0);

    selection.set("b", true);
    expect(selection.countOf(stretch)).toBe(1);

    // A mail outside the stretch is not part of it.
    selection.set("z", true);
    expect(selection.countOf(stretch)).toBe(1);

    selection.setAll(stretch, true);
    expect(selection.countOf(stretch)).toBe(3);

    // And unticking the stretch leaves what was ticked beside it alone.
    selection.setAll(stretch, false);
    expect(selection.countOf(stretch)).toBe(0);
    expect(selection.ids).toEqual(["z"]);
});

test("clearing drops everything", () => {
    const selection = new MailSelection();

    selection.setAll(["a", "b"], true);
    selection.clear();

    expect(selection.active).toBe(false);
    expect(selection.count).toBe(0);
});
