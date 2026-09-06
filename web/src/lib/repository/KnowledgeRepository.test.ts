import {expect, mock, test} from "bun:test";
import {KnowledgeNameTakenError, KnowledgeRepository} from "./KnowledgeRepository";

function answering(status: number, body: unknown = null) {
    const fetcher = mock(async () => new Response(body === null ? "" : JSON.stringify(body), {status}));
    globalThis.fetch = fetcher as unknown as typeof fetch;
    return fetcher;
}

const ENTRY = {
    id: "k-1",
    name: "Stromvertrag",
    description: "Bei Rheinenergie.",
    keywords: ["rheinenergie", "strom"],
    relevant_on: "2026-04-01",
    created_at: "2026-03-01T10:00:00Z",
    updated_at: "2026-03-02T10:00:00Z",
    created_by_agent: true,
};

test("reads the entries the assistant has kept", async () => {
    const fetcher = answering(200, {knowledge: [ENTRY]});

    expect(await new KnowledgeRepository().list()).toEqual([
        {
            id: "k-1",
            name: "Stromvertrag",
            description: "Bei Rheinenergie.",
            keywords: ["rheinenergie", "strom"],
            relevantOn: "2026-04-01",
            createdAt: "2026-03-01T10:00:00Z",
            updatedAt: "2026-03-02T10:00:00Z",
            createdByAgent: true,
        },
    ]);
    expect((fetcher as any).mock.calls[0][0]).toBe("/api/users/me/knowledge");
});

test("an entry without keywords or a date is still an entry", async () => {
    answering(200, {knowledge: [{...ENTRY, keywords: undefined, relevant_on: undefined, created_by_agent: undefined}]});

    const entry = (await new KnowledgeRepository().list())[0];
    expect(entry.keywords).toEqual([]);
    // The screen tells a learned entry from a typed one, so an absent flag must not read as true.
    expect(entry.createdByAgent).toBe(false);
    expect(entry.relevantOn).toBeNull();
});

test("a list that could not be read throws, so the table does not show an empty state", async () => {
    answering(500);
    expect(new KnowledgeRepository().list()).rejects.toThrow();
});

test("writing sends the whole entry and reads back what was stored", async () => {
    const fetcher = answering(201, {...ENTRY, name: "Strom vertrag", created_by_agent: false});

    const written = await new KnowledgeRepository().create({
        name: "  Strom   vertrag ",
        description: "Bei Rheinenergie.",
        keywords: ["Rheinenergie"],
        relevantOn: null,
    });

    // The server normalizes; the screen shows what came back, not what it sent.
    expect(written.name).toBe("Strom vertrag");
    const [url, init] = (fetcher as any).mock.calls[0];
    expect(url).toBe("/api/users/me/knowledge");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body)).toEqual({
        name: "  Strom   vertrag ",
        description: "Bei Rheinenergie.",
        keywords: ["Rheinenergie"],
        relevant_on: null,
    });
});

test("a name that is already taken is its own error, so a form can point at the field", async () => {
    answering(409, {error: {status: 409, code: "conflict", message: "There is already an entry of that name"}});

    expect(
        new KnowledgeRepository().create({name: "Stromvertrag", description: "x", keywords: [], relevantOn: null}),
    ).rejects.toBeInstanceOf(KnowledgeNameTakenError);
});

test("an edit is addressed by id, so renaming stays one entry", async () => {
    const fetcher = answering(200, {...ENTRY, name: "Umzug nach Köln"});

    await new KnowledgeRepository().update("k-1", {
        name: "Umzug nach Köln",
        description: "Zum 1. April.",
        keywords: ["umzug"],
        relevantOn: "2026-04-01",
    });

    const [url, init] = (fetcher as any).mock.calls[0];
    expect(url).toBe("/api/users/me/knowledge/k-1");
    expect(init.method).toBe("PUT");
});

test("deleting answers with nothing, and a delete that did not happen throws", async () => {
    const fetcher = answering(204);
    await new KnowledgeRepository().remove("k-1");
    const [url, init] = (fetcher as any).mock.calls[0];
    expect(url).toBe("/api/users/me/knowledge/k-1");
    expect(init.method).toBe("DELETE");

    answering(500);
    expect(new KnowledgeRepository().remove("k-1")).rejects.toThrow();
});
