import {expect, test} from "bun:test";
import {StackIntro} from "./stackIntro.svelte";

/** A fan of three, with timers short enough to wait for in a test. */
function intro(config: {wait?: number; duration?: number} = {}) {
    return new StackIntro({size: 3, wait: config.wait ?? 10_000, duration: config.duration ?? 30});
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

test("the fan waits for the batch when its cards turn up one at a time", () => {
    const fan = intro();

    // What navigating into the stack looks like: the pile announces four mails and they become
    // drawable one after the other, because every body is a request of its own.
    fan.observe([], 4);
    fan.observe(["a"], 3);
    fan.onReady("a");

    // Nothing is dealt on the strength of one card, and that card is not shown on its own either.
    expect(fan.card("a")).toEqual({shown: false, dealt: false});

    fan.observe(["a", "b"], 2);
    fan.onReady("b");
    expect(fan.card("a").shown).toBe(false);
    expect(fan.card("b").shown).toBe(false);

    fan.observe(["a", "b", "c"], 1);
    fan.onReady("c");

    // The head is as deep as the pile is drawn, so the fourth mail is not part of the fan and
    // nothing waits for it.
    expect(fan.card("a")).toEqual({shown: true, dealt: true});
    expect(fan.card("b")).toEqual({shown: true, dealt: true});
    expect(fan.card("c")).toEqual({shown: true, dealt: true});
});

test("a batch that becomes drawable in one go is dealt in one go", () => {
    const fan = intro();

    // What a reload looks like: nothing is cached, so the metadata arrives last and every body
    // is already here when it does.
    fan.observe(["a", "b", "c"], 1);
    fan.onReady("a");
    fan.onReady("b");
    fan.onReady("c");

    expect(fan.card("b")).toEqual({shown: true, dealt: true});
});

test("a pile shorter than the fan is dealt once nothing is on its way", () => {
    const fan = intro();

    fan.observe(["a", "b"], 0);
    fan.onReady("a");
    expect(fan.card("a").dealt).toBe(false);

    fan.onReady("b");
    expect(fan.card("a")).toEqual({shown: true, dealt: true});
    expect(fan.card("b")).toEqual({shown: true, dealt: true});
});

test("a mail that never arrives lets the fan go anyway", async () => {
    const fan = intro({wait: 20});

    fan.observe(["a"], 2);
    fan.onReady("a");
    expect(fan.card("a").dealt).toBe(false);

    await sleep(40);

    expect(fan.card("a")).toEqual({shown: true, dealt: true});
});

test("a card that turns up after the fan is not dealt onto it a second time", async () => {
    const fan = intro({duration: 20});

    fan.observe(["a", "b", "c"], 0);
    ["a", "b", "c"].forEach((id) => fan.onReady(id));
    expect(fan.card("a").dealt).toBe(true);

    // The next batch, once the animation has played out.
    await sleep(40);
    fan.observe(["a", "b", "c", "d", "e"], 0);
    fan.onReady("d");

    expect(fan.card("a")).toEqual({shown: true, dealt: false});
    expect(fan.card("d")).toEqual({shown: true, dealt: false});
    // Still nothing before it is laid out, fan or no fan.
    expect(fan.card("e")).toEqual({shown: false, dealt: false});
});

test("disposing stops the fan's timers", async () => {
    const fan = intro({wait: 20});

    fan.observe(["a"], 2);
    fan.onReady("a");
    fan.dispose();
    await sleep(40);

    expect(fan.card("a").dealt).toBe(false);
});
