import {expect, test} from "bun:test";
import {MailListViewModel, type MailScope} from "./MailListViewModel.svelte";
import type {EmailRepository} from "$lib/repository/EmailRepository.svelte";

type Request = {scope: string; before: string | null; beforeId: string | null; limit: string | null};

/** `yyyy-mm-dd` of a day, [daysBack] days ago in local time -- the zone the server cuts days in. */
function day(daysBack: number): string {
    const date = new Date();
    date.setDate(date.getDate() - daysBack);
    return `${date.getFullYear()}-${`${date.getMonth() + 1}`.padStart(2, "0")}-${`${date.getDate()}`.padStart(2, "0")}`;
}

const startOfDayAfter = (key: string) => {
    const [year, month, dayOfMonth] = key.split("-").map(Number);
    return new Date(year, month - 1, dayOfMonth + 1).getTime() / 1000;
};

/**
 * A mailbox of days, per scope. Ids are `<scope>-<row>`, so a test can see which listing a row
 * came out of, and a page starts wherever its cursor says.
 */
function mailbox(scopes: Record<MailScope, {key: string; count: number}[]>) {
    const requests: Request[] = [];

    /** Every mail of a scope in row order, with the day it sits in. */
    const rows = (scope: MailScope) =>
        scopes[scope].flatMap((entry, dayIndex) =>
            Array.from({length: entry.count}, () => ({day: entry.key, dayIndex}))
        );

    globalThis.fetch = (async (url: string) => {
        const target = new URL(url, "http://localhost");
        const scope = (target.searchParams.get("scope") ?? "unarchived") as MailScope;
        const days = scopes[scope];
        const all = rows(scope);

        if (target.pathname.endsWith("/groups")) {
            return new Response(JSON.stringify({grouping: "date", groups: days}), {status: 200});
        }

        const before = target.searchParams.get("before");
        const beforeId = target.searchParams.get("before_id");
        const limit = Number(target.searchParams.get("limit") ?? 100);
        requests.push({
            scope,
            before,
            beforeId,
            limit: target.searchParams.get("limit"),
        });

        // Where the page starts: the first row that is older than the cursor. A day boundary
        // means "the first row of that day"; a row cursor means "the row after that one".
        let start = 0;
        if (beforeId !== null) {
            start = all.findIndex((_, index) => `${scope}-${index}` === beforeId) + 1;
        } else if (before !== null) {
            start = all.findIndex((row) => startOfDayAfter(row.day) <= Number(before));
            if (start < 0) start = all.length;
        }

        const ids = all.slice(start, start + limit).map((_, offset) => `${scope}-${start + offset}`);
        const next =
            start + ids.length < all.length && ids.length === limit
                ? {before: startOfDayAfter(all[start + ids.length - 1].day), before_id: ids.at(-1)}
                : null;

        return new Response(JSON.stringify({total: all.length, ids, next}), {status: 200});
    }) as unknown as typeof fetch;

    return requests;
}

/** Only [subscribe] is asked of the repository here; what a row shows is its business. */
function repository() {
    const held = new Set<string>();
    const subscribes: string[] = [];
    const stub = {
        subscribe(id: string) {
            held.add(id);
            subscribes.push(id);
            return () => held.delete(id);
        },
    };
    return {held, subscribes, repository: stub as unknown as EmailRepository};
}

const settle = () => new Promise((resolve) => setTimeout(resolve, 10));

test("the first window reports the length and fills the top of the list", async () => {
    const requests = mailbox({
        unarchived: [{key: day(0), count: 3}, {key: day(1), count: 2}],
        all: [{key: day(0), count: 3}],
    });
    const {repository: mails} = repository();
    const list = new MailListViewModel(mails);

    list.window(0, 20);
    await settle();

    expect(list.total).toBe(5);
    expect(list.initialized).toBe(true);
    expect(list.idAt(0)).toBe("unarchived-0");
    // Two days, so the layout is a header, three mails, a header, two mails.
    expect(list.layout.length).toBe(7);
    // The top of a list needs no cursor.
    expect(requests[0]).toEqual({scope: "unarchived", before: null, beforeId: null, limit: "100"});
});

test("a jump lands on the day it jumped to, not on a walk down from the top", async () => {
    // 150 mails today, 5 yesterday: the second day starts past the first page.
    const requests = mailbox({
        unarchived: [{key: day(0), count: 150}, {key: day(1), count: 5}],
        all: [],
    });
    const {repository: mails} = repository();
    const list = new MailListViewModel(mails);

    list.window(0, 5);
    await settle();

    // The header of the second day sits at row 151; the rows under it are its mails.
    list.window(151, 156);
    await settle();

    expect(list.idAt(150)).toBe("unarchived-150");
    // One request for the top, one anchored at the day boundary -- no walk through the 150.
    expect(requests.length).toBe(2);
    expect(requests[1].before).toBe(String(startOfDayAfter(day(1))));
});

test("a gap inside a long day carries on from where the last page ended", async () => {
    const requests = mailbox({unarchived: [{key: day(0), count: 250}], all: []});
    const {repository: mails} = repository();
    const list = new MailListViewModel(mails);

    list.window(1, 5);
    await settle();
    expect(list.idAt(99)).toBe("unarchived-99");

    // Row 100 is the first one the page did not reach, and it is the same day -- so the cursor
    // of that page is what continues, not the day boundary again.
    list.window(101, 105);
    await settle();

    expect(list.idAt(100)).toBe("unarchived-100");
    expect(requests.at(-1)!.beforeId).toBe("unarchived-99");
});

test("switching the scope keeps both listings and the subscriptions", async () => {
    mailbox({
        unarchived: [{key: day(0), count: 2}],
        all: [{key: day(0), count: 2}, {key: day(1), count: 3}],
    });
    const {held, subscribes, repository: mails} = repository();
    const list = new MailListViewModel(mails);

    list.window(0, 10);
    await settle();
    list.window(0, 10);
    expect(list.total).toBe(2);
    expect([...held].sort()).toEqual(["unarchived-0", "unarchived-1"]);

    list.setScope("all");
    list.window(0, 10);
    await settle();
    list.window(0, 10);
    expect(list.total).toBe(5);
    expect(list.idAt(4)).toBe("all-4");

    const askedSoFar = subscribes.length;

    // Back again: the listing is still there, so nothing is fetched and nothing is subscribed
    // that was not subscribed before.
    list.setScope("unarchived");
    list.window(0, 10);
    await settle();

    expect(list.total).toBe(2);
    expect(list.idAt(1)).toBe("unarchived-1");
    expect(subscribes.length).toBe(askedSoFar + 2);
});

test("the same page is not asked for twice", async () => {
    const requests = mailbox({unarchived: [{key: day(0), count: 250}], all: []});
    const {repository: mails} = repository();
    const list = new MailListViewModel(mails);

    list.window(0, 5);
    list.window(0, 5);
    list.window(1, 6);
    await settle();

    // One page request, plus the one for the days.
    expect(requests.length).toBe(1);
});

test("a failed page is reported and asked for again", async () => {
    globalThis.fetch = (async () => new Response("nope", {status: 500})) as unknown as typeof fetch;
    const {repository: mails} = repository();
    const list = new MailListViewModel(mails);

    list.window(0, 20);
    await settle();

    expect(list.failed).toBe(true);
    expect(list.initialized).toBe(false);

    mailbox({unarchived: [{key: day(0), count: 2}], all: []});
    list.retry();
    await settle();

    expect(list.failed).toBe(false);
    expect(list.idAt(0)).toBe("unarchived-0");
});

test("a mailbox whose shape cannot be read stays a flat list", async () => {
    globalThis.fetch = (async (url: string) => {
        if (new URL(url, "http://localhost").pathname.endsWith("/groups")) {
            return new Response(JSON.stringify({grouping: "date"}), {status: 200});
        }
        return new Response(
            JSON.stringify({total: 2, ids: ["m-0", "m-1"], next: null}),
            {status: 200}
        );
    }) as unknown as typeof fetch;

    const {repository: mails} = repository();
    const list = new MailListViewModel(mails);

    list.window(0, 20);
    await settle();

    // Without days there are no headers, and the rows are the mailbox itself.
    expect(list.layout.length).toBe(2);
    expect(list.layout.rowAt(0)).toEqual({kind: "mail", index: 0});
});

test("letting go releases every row", async () => {
    mailbox({unarchived: [{key: day(0), count: 2}], all: []});
    const {held, repository: mails} = repository();
    const list = new MailListViewModel(mails);

    list.window(0, 10);
    await settle();
    list.window(0, 10);
    expect(held.size).toBe(2);

    list.dispose();
    expect(held.size).toBe(0);
});

test("stepping is the row above and the row below, and asks for a page it does not hold", async () => {
    mailbox({
        unarchived: [{key: day(0), count: 150}],
        all: [{key: day(0), count: 2}],
    });
    const {repository: mails} = repository();
    const list = new MailListViewModel(mails);

    list.window(0, 20);
    await settle();

    expect(list.indexOf("unarchived-5")).toBe(5);
    expect(list.step("unarchived-5", -1)).toBe("unarchived-4");
    expect(list.step("unarchived-5", 1)).toBe("unarchived-6");

    // Either end of the listing.
    expect(list.canStep("unarchived-0", -1)).toBe(false);
    expect(list.step("unarchived-0", -1)).toBeUndefined();
    expect(list.canStep("unarchived-0", 1)).toBe(true);
    expect(list.canStep("unarchived-149", 1)).toBe(false);

    // A mail of the other scope is not in this listing at all.
    expect(list.indexOf("all-0")).toBeUndefined();
    expect(list.canStep("all-0", 1)).toBe(false);

    // Past the first page: nothing to hand out yet, but that page is on its way and the same
    // step answers once it landed.
    expect(list.canStep("unarchived-99", 1)).toBe(true);
    expect(list.step("unarchived-99", 1)).toBeUndefined();
    await settle();
    expect(list.step("unarchived-99", 1)).toBe("unarchived-100");
});

test("a mail arriving is read again: the length, the days and the rows on screen", async () => {
    // The fixture is read per request, so growing this array is a mail arriving.
    const days = [{key: day(0), count: 3}];
    mailbox({unarchived: days, all: [{key: day(0), count: 1}]});
    const {repository: mails, held} = repository();
    const list = new MailListViewModel(mails);

    list.window(0, 20);
    await settle();

    expect(list.total).toBe(3);
    // A header and three mails.
    expect(list.layout.length).toBe(4);

    days[0] = {key: day(0), count: 4};
    list.refresh();

    // Nothing goes blank in between: the shape and the rows stand until the answer is here, so a
    // table does not collapse under a cursor while the mailbox is being re-read.
    expect(list.total).toBe(3);
    expect(list.idAt(0)).toBe("unarchived-0");

    await settle();

    expect(list.total).toBe(4);
    expect(list.layout.length).toBe(5);
    expect(list.idAt(3)).toBe("unarchived-3");
    expect(held.has("unarchived-3")).toBe(false);

    // The rows on screen are subscribed again as they are read, which is what a table asking for
    // its window does; here nothing asked, so the new row is only in the listing.
    list.window(0, 20);
    await settle();
    expect(held.has("unarchived-3")).toBe(true);
});

test("the scope that was not on screen is read again when it is", async () => {
    const all = [{key: day(0), count: 2}];
    mailbox({unarchived: [{key: day(0), count: 2}], all});
    const {repository: mails} = repository();
    const list = new MailListViewModel(mails);

    list.window(0, 20);
    await settle();
    list.setScope("all");
    list.window(0, 20);
    await settle();
    expect(list.total).toBe(2);

    // Two arrive while the other scope is on screen.
    list.setScope("unarchived");
    all[0] = {key: day(0), count: 4};
    list.refresh();
    await settle();

    list.setScope("all");
    list.window(0, 20);
    await settle();
    expect(list.total).toBe(4);
    expect(list.idAt(3)).toBe("all-3");
});

test("a stretch answers with the mails it holds, not with the ones it has not asked for", async () => {
    // 150 mails today: the first page covers 100 of them, the rest of the day is still a gap.
    mailbox({unarchived: [{key: day(0), count: 150}, {key: day(1), count: 2}], all: []});
    const {repository: mails} = repository();
    const list = new MailListViewModel(mails);

    list.window(0, 5);
    await settle();

    const today = list.idsIn(0, 150);
    expect(today.length).toBe(100);
    expect(today[0]).toBe("unarchived-0");
    expect(today.at(-1)).toBe("unarchived-99");

    // Scrolling into the gap is what fills it, and the stretch then names those too.
    list.window(101, 120);
    await settle();
    expect(list.idsIn(0, 150).length).toBe(150);

    // The next stretch is its own mails and stops where it ends.
    expect(list.idsIn(150, 2)).toEqual(["unarchived-150", "unarchived-151"]);
});
