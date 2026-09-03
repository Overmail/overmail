import {expect, test} from "bun:test";
import {greetingFor} from "./greeting";

/** Local time, so the hour is built rather than parsed from a string with a zone. */
function at(hour: number, minute = 0) {
    return new Date(2026, 8, 3, hour, minute);
}

test("the day is split into four greetings", () => {
    expect(greetingFor(at(0))).toBe("night");
    expect(greetingFor(at(4, 59))).toBe("night");
    expect(greetingFor(at(5))).toBe("morning");
    expect(greetingFor(at(10, 59))).toBe("morning");
    expect(greetingFor(at(11))).toBe("day");
    expect(greetingFor(at(17, 59))).toBe("day");
    expect(greetingFor(at(18))).toBe("evening");
    expect(greetingFor(at(22, 59))).toBe("evening");
    expect(greetingFor(at(23))).toBe("night");
    expect(greetingFor(at(23, 59))).toBe("night");
});
