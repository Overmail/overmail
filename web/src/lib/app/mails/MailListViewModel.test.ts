import {expect, test} from "bun:test";
import {MailListViewModel} from "./MailListViewModel.svelte";
import type {MailGrouping} from "./grouping";
import type {EmailRepository} from "$lib/repository/EmailRepository.svelte";

/**
 * The two answers the list is built from: the stretches, and pages of ids (`m-<position>`,
 * newest first).
 */
function mailbox(options: {
    total: number;
    days?: {key: string; count: number}[];
    /** What the mailbox looks like with the archived mails switched in. */
    archived?: {total: number};
}) {
    const pages: {offset: number; limit: number; archived: string | null}[] = [];

    globalThis.fetch = (async (url: string) => {
        const target = new URL(url, "http://localhost");
        const withArchived = target.searchParams.get("archived") === "true";
        const total = withArchived ? (options.archived?.total ?? options.total) : options.total;

        if (target.pathname.endsWith("/groups")) {
            const groups = withArchived ? [{key: null, count: total}] : options.days ?? [{key: null, count: total}];
            return new Response(JSON.stringify({grouping: "date", groups}), {status: 200});
        }

        const offset = Number(target.searchParams.get("offset"));
        const limit = Number(target.searchParams.get("limit"));
        pages.push({offset, limit, archived: target.searchParams.get("archived")});

        const prefix = withArchived ? "a" : "m";
        const ids = Array.from({length: Math.max(0, Math.min(limit, total - offset))}, (_, index) =>
            `${prefix}-${offset + index}`
        );
        return new Response(JSON.stringify({total, offset, ids}), {status: 200});
    }) as unknown as typeof fetch;

    return pages;
}

/** Only [subscribe] is asked of the repository here; what a row shows is its business. */
function repository() {
    const held = new Set<string>();
    const stub = {
        subscribe(id: string) {
            held.add(id);
            return () => held.delete(id);
        },
    };
    return {held, repository: stub as unknown as EmailRepository};
}

const settle = () => new Promise((resolve) => setTimeout(resolve, 10));

test("the first window reports the length and fills the first page", async () => {
    const pages = mailbox({total: 250});
    const {repository: mails} = repository();
    const list = new MailListViewModel(mails, "none");

    list.window(0, 20);
    await settle();

    expect(list.total).toBe(250);
    expect(list.initialized).toBe(true);
    expect(list.idAt(0)).toBe("m-0");
    expect(list.idAt(99)).toBe("m-99");
    // Not fetched yet, so the row is a gap the table holds open.
    expect(list.idAt(100)).toBeUndefined();
    expect(pages).toEqual([{offset: 0, limit: 100, archived: "false"}]);
});

test("scrolling into a page that is missing asks for it once", async () => {
    const pages = mailbox({total: 250});
    const {repository: mails} = repository();
    const list = new MailListViewModel(mails, "none");

    list.window(0, 20);
    await settle();

    list.window(140, 160);
    await settle();
    // The same range again must not turn into a second request.
    list.window(140, 160);
    await settle();

    expect(list.idAt(150)).toBe("m-150");
    expect(pages).toEqual([
        {offset: 0, limit: 100, archived: "false"},
        {offset: 100, limit: 100, archived: "false"},
    ]);
});

test("a window that straddles two pages asks for both", async () => {
    const pages = mailbox({total: 250});
    const {repository: mails} = repository();
    const list = new MailListViewModel(mails, "none");

    list.window(0, 20);
    await settle();
    list.window(95, 105);
    await settle();

    expect(pages.map((page) => page.offset)).toEqual([0, 100]);
});

test("the rows on screen are subscribed, and the ones behind are let go of", async () => {
    mailbox({total: 250});
    const {held, repository: mails} = repository();
    const list = new MailListViewModel(mails, "none");

    list.window(0, 2);
    await settle();
    list.window(0, 2);

    expect([...held].sort()).toEqual(["m-0", "m-1", "m-2"]);

    list.window(1, 3);
    expect([...held].sort()).toEqual(["m-1", "m-2", "m-3"]);

    list.dispose();
    expect(held.size).toBe(0);
});

test("headers shift the rows, not the pages behind them", async () => {
    const today = new Date();
    const iso = (date: Date) =>
        `${date.getFullYear()}-${`${date.getMonth() + 1}`.padStart(2, "0")}-${`${date.getDate()}`.padStart(2, "0")}`;
    const yesterday = new Date(today.getFullYear(), today.getMonth(), today.getDate() - 1);

    mailbox({
        total: 4,
        days: [
            {key: iso(today), count: 2},
            {key: iso(yesterday), count: 2},
        ],
    });
    const {held, repository: mails} = repository();
    const list = new MailListViewModel(mails, "date");

    list.window(0, 10);
    await settle();
    list.window(0, 10);

    // Two stretches of two, so the layout is header, two mails, header, two mails.
    expect(list.layout.length).toBe(6);
    expect(list.layout.rowAt(0)).toEqual({kind: "header", label: {kind: "today"}, count: 2});
    expect(list.layout.rowAt(3)).toEqual({kind: "header", label: {kind: "yesterday"}, count: 2});
    expect(list.layout.rowAt(4)).toEqual({kind: "mail", index: 2});

    // The mails of those rows are the first four of the mailbox, headers or not.
    expect([...held].sort()).toEqual(["m-0", "m-1", "m-2", "m-3"]);

    // Only the second stretch on screen: the header among them is not a mail to subscribe.
    list.window(3, 5);
    expect([...held].sort()).toEqual(["m-2", "m-3"]);
});

test("a page past the end is empty and changes nothing", async () => {
    mailbox({total: 3});
    const {repository: mails} = repository();
    const list = new MailListViewModel(mails, "none");

    list.window(0, 20);
    await settle();

    expect(list.total).toBe(3);
    expect(list.idAt(2)).toBe("m-2");
    expect(list.idAt(3)).toBeUndefined();
});

test("a failed page is reported and asked for again", async () => {
    globalThis.fetch = (async () => new Response("nope", {status: 500})) as unknown as typeof fetch;
    const {repository: mails} = repository();
    const list = new MailListViewModel(mails, "none");

    list.window(0, 20);
    await settle();

    expect(list.failed).toBe(true);
    expect(list.initialized).toBe(false);

    mailbox({total: 10});
    list.retry();
    await settle();

    expect(list.failed).toBe(false);
    expect(list.idAt(0)).toBe("m-0");
});

test("a mailbox whose shape cannot be read stays a flat list", async () => {
    globalThis.fetch = (async (url: string) => {
        if (new URL(url, "http://localhost").pathname.endsWith("/groups")) {
            return new Response(JSON.stringify({grouping: "date"}), {status: 200});
        }
        return new Response(JSON.stringify({total: 2, offset: 0, ids: ["m-0", "m-1"]}), {status: 200});
    }) as unknown as typeof fetch;

    const {repository: mails} = repository();
    const list = new MailListViewModel(mails, "date");

    list.window(0, 20);
    await settle();

    // Without stretches there are no headers, and the rows are the mailbox itself.
    expect(list.layout.length).toBe(2);
    expect(list.layout.rowAt(0)).toEqual({kind: "mail", index: 0});
});

test("switching the archived mails in starts the list over", async () => {
    const pages = mailbox({total: 2, archived: {total: 5}});
    const {held, repository: mails} = repository();
    const list = new MailListViewModel(mails, "none");

    list.window(0, 20);
    await settle();
    list.window(0, 20);

    expect(list.total).toBe(2);
    expect(pages[0].archived).toBe("false");
    expect([...held].sort()).toEqual(["m-0", "m-1"]);

    list.setIncludeArchived(true);
    list.window(0, 20);
    await settle();
    list.window(0, 20);

    // A different filter is a different list: the length, the ids and the subscriptions are all
    // the new one's.
    expect(list.total).toBe(5);
    expect(pages.at(-1)!.archived).toBe("true");
    expect(list.idAt(0)).toBe("a-0");
    expect([...held].sort()).toEqual(["a-0", "a-1", "a-2", "a-3", "a-4"]);
});

test("switching to what is already shown changes nothing", async () => {
    const pages = mailbox({total: 2});
    const {repository: mails} = repository();
    const list = new MailListViewModel(mails, "none");

    list.window(0, 20);
    await settle();
    const requests = pages.length;

    list.setIncludeArchived(false);
    list.window(0, 20);
    await settle();

    expect(pages.length).toBe(requests);
});
