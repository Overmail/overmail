import {expect, test} from "bun:test";
import {MailLayout, foldGroups, labelFor} from "./grouping";

/** 2026-09-04 was a Friday, so the week stretch exists and starts on the 31st of August. */
const friday = new Date(2026, 8, 4, 10, 0);

test("today and yesterday are their own stretches", () => {
    expect(labelFor("2026-09-04", friday)).toEqual({kind: "today"});
    expect(labelFor("2026-09-03", friday)).toEqual({kind: "yesterday"});
    // A day in the future is clock skew, not a stretch of its own.
    expect(labelFor("2026-09-05", friday)).toEqual({kind: "today"});
});

test("the rest of the week from wednesday on", () => {
    // Friday: Wednesday and Thursday are the rest of the week, Monday and Tuesday too.
    expect(labelFor("2026-09-02", friday)).toEqual({kind: "week"});
    expect(labelFor("2026-08-31", friday)).toEqual({kind: "week"});
    // The Sunday before is last week, and September starts mid-week, so it is that month's.
    expect(labelFor("2026-08-30", friday)).toEqual({kind: "calendarMonth", year: 2026, month: 8});
});

test("no week stretch on a monday or a tuesday", () => {
    const tuesday = new Date(2026, 8, 1, 10, 0);
    // Everything of that week is already today or yesterday, so the day before is last month.
    expect(labelFor("2026-09-01", tuesday)).toEqual({kind: "today"});
    expect(labelFor("2026-08-31", tuesday)).toEqual({kind: "yesterday"});
    expect(labelFor("2026-08-30", tuesday)).toEqual({kind: "calendarMonth", year: 2026, month: 8});
});

test("the rest of the month, unless the week already reaches the 1st", () => {
    // Wednesday the 16th: the week starts on the 14th, so the 1st to the 13th are this month.
    const midMonth = new Date(2026, 8, 16, 10, 0);
    expect(labelFor("2026-09-13", midMonth)).toEqual({kind: "month"});
    expect(labelFor("2026-09-01", midMonth)).toEqual({kind: "month"});
    expect(labelFor("2026-08-31", midMonth)).toEqual({kind: "calendarMonth", year: 2026, month: 8});

    // Thursday the 3rd: the week started on the 31st of August, so there is no rest of the month.
    const earlyMonth = new Date(2026, 8, 3, 10, 0);
    expect(labelFor("2026-09-01", earlyMonth)).toEqual({kind: "week"});
    expect(labelFor("2026-08-30", earlyMonth)).toEqual({kind: "calendarMonth", year: 2026, month: 8});
});

test("days that belong together add up", () => {
    const groups = foldGroups(
        [
            {key: "2026-09-04", count: 3},
            {key: "2026-09-03", count: 1},
            {key: "2026-09-02", count: 2},
            {key: "2026-09-01", count: 4},
            {key: "2026-08-20", count: 5},
            {key: "2026-08-02", count: 1},
            {key: "2026-07-30", count: 2},
        ],
        friday
    );

    expect(groups).toEqual([
        {label: {kind: "today"}, count: 3},
        {label: {kind: "yesterday"}, count: 1},
        // The 2nd and the 1st are both the rest of this week.
        {label: {kind: "week"}, count: 6},
        {label: {kind: "calendarMonth", year: 2026, month: 8}, count: 6},
        {label: {kind: "calendarMonth", year: 2026, month: 7}, count: 2},
    ]);
});

test("an ungrouped listing is one stretch without a header", () => {
    expect(foldGroups([{key: null, count: 12}], friday)).toEqual([{label: null, count: 12}]);
});

test("the layout names every row", () => {
    const layout = new MailLayout([
        {label: {kind: "today"}, count: 2},
        {label: {kind: "yesterday"}, count: 1},
    ]);

    expect(layout.length).toBe(5);
    expect(layout.mailCount).toBe(3);
    expect(layout.rowAt(0)).toEqual({kind: "header", label: {kind: "today"}, count: 2});
    expect(layout.rowAt(1)).toEqual({kind: "mail", index: 0});
    expect(layout.rowAt(2)).toEqual({kind: "mail", index: 1});
    expect(layout.rowAt(3)).toEqual({kind: "header", label: {kind: "yesterday"}, count: 1});
    expect(layout.rowAt(4)).toEqual({kind: "mail", index: 2});
    expect(layout.rowAt(5)).toBeUndefined();
});

test("a flat layout is mails and nothing else", () => {
    const layout = MailLayout.flat(3);

    expect(layout.length).toBe(3);
    expect(layout.rowAt(0)).toEqual({kind: "mail", index: 0});
    expect(layout.rowAt(2)).toEqual({kind: "mail", index: 2});

    expect(MailLayout.flat(0).length).toBe(0);
});

test("a row is found in a long layout", () => {
    const layout = new MailLayout(
        Array.from({length: 500}, (_, index) => ({
            label: {kind: "calendarMonth" as const, year: 2020, month: (index % 12) + 1},
            count: 10,
        }))
    );

    expect(layout.length).toBe(500 * 11);
    // The 300th stretch: its header, then its first mail.
    expect(layout.rowAt(300 * 11)).toEqual({
        kind: "header",
        label: {kind: "calendarMonth", year: 2020, month: (300 % 12) + 1},
        count: 10,
    });
    expect(layout.rowAt(300 * 11 + 1)).toEqual({kind: "mail", index: 3000});
});
