import {expect, test} from "bun:test";
import {sentAtLabel} from "./sentAt";

/** Local time, so the day boundaries are the ones the function counts in. */
const at = (year: number, month: number, day: number, hour = 12, minute = 0) =>
    new Date(year, month - 1, day, hour, minute);

const now = at(2026, 9, 4, 10, 0);

test("today is a time", () => {
    expect(sentAtLabel(at(2026, 9, 4, 8, 30), now)).toEqual({kind: "time"});
    // Same day, later than now: still today.
    expect(sentAtLabel(at(2026, 9, 4, 23, 59), now)).toEqual({kind: "time"});
});

test("the days after that are named", () => {
    expect(sentAtLabel(at(2026, 9, 3), now)).toEqual({kind: "relative", days: 1});
    expect(sentAtLabel(at(2026, 9, 2), now)).toEqual({kind: "relative", days: 2});
    expect(sentAtLabel(at(2026, 9, 1), now)).toEqual({kind: "relative", days: 3});
});

test("a calendar day, not a 24 hour window", () => {
    // Half an hour old and already yesterday, which is what a reader means by it.
    expect(sentAtLabel(at(2026, 9, 3, 23, 30), at(2026, 9, 4, 0, 0))).toEqual({
        kind: "relative",
        days: 1,
    });
});

test("older than that is a date, with the year once it is another one", () => {
    expect(sentAtLabel(at(2026, 8, 31), now)).toEqual({kind: "date", withYear: false});
    expect(sentAtLabel(at(2026, 1, 1), now)).toEqual({kind: "date", withYear: false});
    expect(sentAtLabel(at(2025, 12, 31), now)).toEqual({kind: "date", withYear: true});
});

test("a send time in the future is a date", () => {
    expect(sentAtLabel(at(2026, 9, 5), now)).toEqual({kind: "date", withYear: false});
    expect(sentAtLabel(at(2027, 1, 2), now)).toEqual({kind: "date", withYear: true});
});
