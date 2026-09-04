import {expect, mock, test} from "bun:test";
import {MailListViewModel} from "./MailListViewModel.svelte";
import type {EmailRepository} from "$lib/repository/EmailRepository.svelte";

/** Ids as the endpoint would hand them out: `m-<position>`, newest first. */
function mailbox(total: number) {
    const requests: {offset: number; limit: number}[] = [];

    globalThis.fetch = mock(async (url: string) => {
        const query = new URL(url, "http://localhost").searchParams;
        const offset = Number(query.get("offset"));
        const limit = Number(query.get("limit"));
        requests.push({offset, limit});

        const ids = Array.from({length: Math.max(0, Math.min(limit, total - offset))}, (_, index) =>
            `m-${offset + index}`
        );
        return new Response(JSON.stringify({total, offset, ids}), {status: 200});
    }) as unknown as typeof fetch;

    return requests;
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
    const requests = mailbox(250);
    const {repository: mails} = repository();
    const list = new MailListViewModel(mails);

    list.window(0, 20);
    await settle();

    expect(list.total).toBe(250);
    expect(list.initialized).toBe(true);
    expect(list.idAt(0)).toBe("m-0");
    expect(list.idAt(99)).toBe("m-99");
    // Not fetched yet, so the row is a gap the table holds open.
    expect(list.idAt(100)).toBeUndefined();
    expect(requests).toEqual([{offset: 0, limit: 100}]);
});

test("scrolling into a page that is missing asks for it once", async () => {
    const requests = mailbox(250);
    const {repository: mails} = repository();
    const list = new MailListViewModel(mails);

    list.window(0, 20);
    await settle();

    list.window(140, 160);
    await settle();
    // The same range again must not turn into a second request.
    list.window(140, 160);
    await settle();

    expect(list.idAt(150)).toBe("m-150");
    expect(requests).toEqual([
        {offset: 0, limit: 100},
        {offset: 100, limit: 100},
    ]);
});

test("a window that straddles two pages asks for both", async () => {
    const requests = mailbox(250);
    const {repository: mails} = repository();
    const list = new MailListViewModel(mails);

    list.window(0, 20);
    await settle();
    list.window(95, 105);
    await settle();

    expect(requests.map((request) => request.offset)).toEqual([0, 100]);
});

test("the rows on screen are subscribed, and the ones behind are let go of", async () => {
    mailbox(250);
    const {held, repository: mails} = repository();
    const list = new MailListViewModel(mails);

    list.window(0, 2);
    await settle();
    list.window(0, 2);

    expect([...held].sort()).toEqual(["m-0", "m-1", "m-2"]);

    list.window(1, 3);
    expect([...held].sort()).toEqual(["m-1", "m-2", "m-3"]);

    list.dispose();
    expect(held.size).toBe(0);
});

test("a page past the end is empty and changes nothing", async () => {
    mailbox(3);
    const {repository: mails} = repository();
    const list = new MailListViewModel(mails);

    list.window(0, 20);
    await settle();

    expect(list.total).toBe(3);
    expect(list.idAt(2)).toBe("m-2");
    expect(list.idAt(3)).toBeUndefined();
});

test("a failed page is reported and asked for again", async () => {
    globalThis.fetch = mock(async () => new Response("nope", {status: 500})) as unknown as typeof fetch;
    const {repository: mails} = repository();
    const list = new MailListViewModel(mails);

    list.window(0, 20);
    await settle();

    expect(list.failed).toBe(true);
    expect(list.initialized).toBe(false);

    mailbox(10);
    list.retry();
    await settle();

    expect(list.failed).toBe(false);
    expect(list.idAt(0)).toBe("m-0");
});
