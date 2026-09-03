import {beforeEach, expect, mock, test} from "bun:test";
import {EntityRepository} from "./EntityRepository.svelte";

type Label = {id: string; name: string};

function repository(fetcher: typeof fetch) {
    globalThis.fetch = fetcher;
    return new EntityRepository<Label>({
        endpoint: "/api/labels",
        key: "labels",
        // No IndexedDB in a test; the repository has to work without one.
        table: () => null,
        parse: (raw) => ({id: raw.id, name: raw.name}),
    });
}

function answering(labels: Label[]) {
    return mock(async () => new Response(JSON.stringify({labels}), {status: 200})) as unknown as typeof fetch;
}

/** Lets the queued flush and the fetch that follows it run. */
async function settle() {
    for (let i = 0; i < 5; i++) await Promise.resolve();
}

beforeEach(() => {
    // Each test brings its own fetch.
});

test("asks for everything requested in one tick in a single call", async () => {
    const fetcher = answering([{id: "a", name: "Studium"}, {id: "b", name: "Rechnungen"}]);
    const repo = repository(fetcher);

    repo.request("a");
    repo.request("b");
    // The same id again must not turn into a second request.
    repo.request("a");
    await settle();

    expect(fetcher).toHaveBeenCalledTimes(1);
    expect((fetcher as any).mock.calls[0][0]).toBe("/api/labels?ids=a,b");
    expect(repo.peek("a").value).toEqual({id: "a", name: "Studium"});
    expect(repo.peek("b").value).toEqual({id: "b", name: "Rechnungen"});
});

test("an id the server does not return is known to be gone", async () => {
    const repo = repository(answering([]));

    repo.request("missing");
    await settle();

    expect(repo.peek("missing")).toEqual({value: null, isLoading: false});
});

test("peek alone starts nothing and reads as loading", () => {
    const fetcher = answering([]);
    const repo = repository(fetcher);

    expect(repo.peek("a")).toEqual({value: null, isLoading: true});
    expect(fetcher).toHaveBeenCalledTimes(0);
});

test("a failed request is forgotten, so a later one tries again", async () => {
    const failing = mock(async () => new Response("nope", {status: 500})) as unknown as typeof fetch;
    const repo = repository(failing);

    repo.request("a");
    await settle();
    expect(repo.peek("a")).toEqual({value: null, isLoading: true});

    const fetcher = answering([{id: "a", name: "Studium"}]);
    globalThis.fetch = fetcher;
    repo.request("a");
    await settle();

    expect(repo.peek("a").value).toEqual({id: "a", name: "Studium"});
});
