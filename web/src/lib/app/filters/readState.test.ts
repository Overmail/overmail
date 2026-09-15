import {expect, test} from "bun:test";
import {readStateOf} from "./readState";

test("one state alone is what the listing is cut down to", () => {
    expect(readStateOf(["read"])).toBe(true);
    expect(readStateOf(["unread"])).toBe(false);
});

test("both and neither restrict nothing", () => {
    expect(readStateOf([])).toBeNull();
    // Every mail is one or the other, so naming both is naming all of them.
    expect(readStateOf(["read", "unread"])).toBeNull();
});
