import {expect, test} from "bun:test";
import {agentBlocks} from "./agentBlocks";

const search = '<toolcall-search-emails subject="Rechnung" sender=""></toolcall-search-emails>';
const read = '<toolcall-read-email emailId="abc" avatarUrl="" subject="Re: &quot;42&quot;"></toolcall-read-email>';

test("groups tool calls that follow each other", () => {
    const blocks = agentBlocks(`Ich schaue nach.\n\n${search}\n\n${read}\n\nDie Rechnung ist von der Uni.`);

    expect(blocks.map((block) => block.type)).toEqual(["markdown", "tools", "markdown"]);
    expect(blocks[1]).toEqual({
        type: "tools",
        calls: [
            {kind: "search-emails", attributes: {subject: "Rechnung", sender: ""}, content: ""},
            {kind: "read-email", attributes: {emailId: "abc", avatarUrl: "", subject: 'Re: "42"'}, content: ""},
        ],
    });
});

test("text between two calls starts a new group", () => {
    const blocks = agentBlocks(`${search}\n\nEine Sekunde.\n\n${read}`);

    expect(blocks.map((block) => block.type)).toEqual(["tools", "markdown", "tools"]);
});

test("thinking keeps its text", () => {
    const blocks = agentBlocks("<toolcall-thinking>Der Nutzer fragt nach der Rechnung.</toolcall-thinking>");

    expect(blocks).toEqual([
        {
            type: "tools",
            calls: [{kind: "thinking", attributes: {}, content: "Der Nutzer fragt nach der Rechnung."}],
        },
    ]);
});

test("an element that is still being streamed is already a block", () => {
    const blocks = agentBlocks("<toolcall-thinking>Ich überlege ger");

    expect(blocks).toEqual([
        {type: "tools", calls: [{kind: "thinking", attributes: {}, content: "Ich überlege ger"}]},
    ]);
});

test("an answer without tool calls is one markdown block", () => {
    expect(agentBlocks("**Hallo**")).toEqual([{type: "markdown", content: "**Hallo**"}]);
    expect(agentBlocks("")).toEqual([]);
});
