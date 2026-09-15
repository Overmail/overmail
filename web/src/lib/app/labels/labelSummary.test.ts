import {expect, test} from "bun:test";
import {summariseLabels} from "./labelSummary";

test("everything that fits is named", () => {
    expect(summariseLabels([])).toEqual({shown: [], rest: 0});
    expect(summariseLabels(["Studium"])).toEqual({shown: ["Studium"], rest: 0});
    expect(summariseLabels(["Studium", "HPI"])).toEqual({shown: ["Studium", "HPI"], rest: 0});
});

test("one over the limit is named as well rather than summarised", () => {
    // "A, B und 1 weiteres" is longer than "A, B, C" and says less.
    expect(summariseLabels(["Studium", "HPI", "Rechnung"])).toEqual({
        shown: ["Studium", "HPI", "Rechnung"],
        rest: 0,
    });
});

test("beyond that the rest is a number", () => {
    expect(summariseLabels(["Studium", "HPI", "Rechnung", "Reise"])).toEqual({
        shown: ["Studium", "HPI"],
        rest: 2,
    });
});

test("how many are named is the caller's to say", () => {
    expect(summariseLabels(["a", "b", "c", "d", "e"], 1)).toEqual({shown: ["a"], rest: 4});
});
