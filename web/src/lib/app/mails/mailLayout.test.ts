import {expect, test} from "bun:test";
import {buildLayout, MailLayout, type MailGroupCount} from "./mailLayout";
import type {ViewGrouping} from "$lib/repository/ViewSocket";

const level = (kind: ViewGrouping["kind"], reversed = false): ViewGrouping => ({kind, reversed});

/** The rows of a layout as short strings, which is what a table draws. */
function rows(layout: MailLayout): string[] {
    return Array.from({length: layout.length}, (_, index) => {
        const row = layout.rowAt(index)!;

        return row.kind === "header"
            ? `h${row.node.level}:${row.node.path.join("/")}`
            : `m:${row.path.join("/")}#${row.offset}`;
    });
}

test("one level is a header over its mails", () => {
    const groups: MailGroupCount[] = [
        {keys: ["1"], count: 2},
        {keys: ["2"], count: 1},
    ];

    const layout = buildLayout(groups, [level("date_smart")]);

    expect(rows(layout)).toEqual(["h0:1", "m:1#0", "m:1#1", "h0:2", "m:2#0"]);
    expect(layout.mailCount).toBe(3);
});

test("a second level is a header under the first, and the first opens once", () => {
    const groups: MailGroupCount[] = [
        {keys: ["1", "a"], count: 1},
        {keys: ["1", "b"], count: 2},
        {keys: ["2", "a"], count: 1},
    ];

    const layout = buildLayout(groups, [level("date_smart"), level("sender")]);

    // The day's header stands over both correspondents; each of them brings its own.
    expect(rows(layout)).toEqual([
        "h0:1",
        "h1:1/b",
        "m:1/b#0",
        "m:1/b#1",
        "h1:1/a",
        "m:1/a#0",
        "h0:2",
        "h1:2/a",
        "m:2/a#0",
    ]);
});

test("the smart date names its stretches first and then counts back by month", () => {
    const groups: MailGroupCount[] = [
        {keys: ["202601"], count: 1},
        {keys: ["2"], count: 1},
        {keys: ["202602"], count: 1},
        {keys: ["1"], count: 1},
    ];

    const layout = buildLayout(groups, [level("date_smart")]);

    expect(layout.roots.map((node) => node.path[0])).toEqual(["1", "2", "202602", "202601"]);
    expect(layout.roots[2].label).toEqual({kind: "calendarMonth", year: 2026, month: 2});
});

test("reversed turns a level around", () => {
    const groups: MailGroupCount[] = [
        {keys: ["2024"], count: 1},
        {keys: ["2026"], count: 1},
    ];

    expect(buildLayout(groups, [level("year")]).roots.map((node) => node.path[0])).toEqual([
        "2026",
        "2024",
    ]);
    expect(buildLayout(groups, [level("year", true)]).roots.map((node) => node.path[0])).toEqual([
        "2024",
        "2026",
    ]);
});

test("unread comes before read, the inbox before the archive", () => {
    const read = buildLayout(
        [{keys: ["true"], count: 1}, {keys: ["false"], count: 1}],
        [level("read")]
    );
    expect(read.roots.map((node) => node.path[0])).toEqual(["false", "true"]);

    const archived = buildLayout(
        [{keys: ["Spam"], count: 1}, {keys: ["Archive"], count: 1}, {keys: ["Unarchive"], count: 1}],
        [level("archived")]
    );
    expect(archived.roots.map((node) => node.path[0])).toEqual(["Unarchive", "Archive", "Spam"]);
});

test("correspondents are ordered by how much mail they hold", () => {
    const layout = buildLayout(
        [{keys: ["a"], count: 1}, {keys: ["b"], count: 5}],
        [level("sender")]
    );

    // Their names are not here, and an order by id would be no order at all.
    expect(layout.roots.map((node) => node.path[0])).toEqual(["b", "a"]);
});

test("a parent counts what is under it", () => {
    const layout = buildLayout(
        [{keys: ["1", "a"], count: 2}, {keys: ["1", "b"], count: 3}],
        [level("date_smart"), level("sender")]
    );

    expect(layout.roots[0].count).toBe(5);
});

test("without levels the listing is one stretch of mails", () => {
    const layout = buildLayout([{keys: [], count: 3}], []);

    expect(rows(layout)).toEqual(["m:#0", "m:#1", "m:#2"]);
    expect(layout.depth).toBe(0);
});

test("a row is found the same way from both ends", () => {
    const layout = buildLayout(
        [{keys: ["1"], count: 2}, {keys: ["2"], count: 2}],
        [level("date_smart")]
    );

    // Mail 2 is the first of the second stretch: its own header sits above it.
    expect(layout.rowOfMail(2)).toBe(4);
    expect(layout.rowAt(4)).toEqual({kind: "mail", path: ["2"], offset: 0});
    expect(layout.mailsIn(0, layout.length)).toHaveLength(4);
});
