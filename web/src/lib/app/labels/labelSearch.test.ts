import {expect, test} from "bun:test";
import {findLabels} from "./labelSearch";

function server(answer: {status: number; body?: unknown}) {
    const asked: string[] = [];
    globalThis.fetch = (async (url: string) => {
        asked.push(String(url));
        return new Response(answer.body === undefined ? null : JSON.stringify(answer.body), {
            status: answer.status,
        });
    }) as unknown as typeof fetch;
    return asked;
}

test("the api shape comes back in the app's own", async () => {
    const asked = server({
        status: 200,
        body: {labels: [{id: "l-1", name: "Studium", color: "#ffffff", email_count: 12}]},
    });

    expect(await findLabels("stu")).toEqual([
        {id: "l-1", name: "Studium", color: "#ffffff", emailCount: 12},
    ]);
    expect(asked).toEqual(["/api/labels/search?query=stu"]);
});

test("the query is encoded, whatever was typed", async () => {
    const asked = server({status: 200, body: {labels: []}});

    await findLabels("re: a/b & c");
    expect(asked[0]).toBe("/api/labels/search?query=re%3A%20a%2Fb%20%26%20c");
});

test("a search that fails turns up no labels, not an error", async () => {
    server({status: 500});

    expect(await findLabels("anything")).toEqual([]);
});
