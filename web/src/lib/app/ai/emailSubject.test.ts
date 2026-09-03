import {expect, test} from "bun:test";
import {shortSubject} from "./emailSubject";

test("keeps a subject that is not too long", () => {
    const subject = "a".repeat(50);

    expect(shortSubject(subject)).toBe(subject);
});

test("cuts a longer subject after 40 characters", () => {
    const subject = "a".repeat(51);

    expect(shortSubject(subject)).toBe(`${"a".repeat(40)}...`);
});

test("does not leave whitespace in front of the dots", () => {
    const subject = `${"a".repeat(39)}    ${"b".repeat(20)}`;

    expect(shortSubject(subject)).toBe(`${"a".repeat(39)}...`);
});
