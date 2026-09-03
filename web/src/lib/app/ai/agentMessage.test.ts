import {expect, test} from "bun:test";
import {parseAgentMessage} from "./agentMessage";

test("splits an answer into text and the mails the agent read", () => {
    const content = 'Ich schaue nach.\n\n'
        + '<toolcall-read-email emailId="abc" avatarUrl="/api/avatars/1" subject="Rechnung"></toolcall-read-email>\n\n'
        + 'Der Code ist 123.';

    expect(parseAgentMessage(content)).toEqual([
        {type: "text", content: "Ich schaue nach."},
        {type: "read-email", email: {id: "abc", subject: "Rechnung", avatarUrl: "/api/avatars/1"}},
        {type: "text", content: "Der Code ist 123."},
    ]);
});

test("reverses the attribute escaping of the server", () => {
    const content = '<toolcall-read-email emailId="x" avatarUrl="" subject="Re: &quot;5 &lt; 6&quot; &amp; more"></toolcall-read-email>';

    expect(parseAgentMessage(content)).toEqual([
        {type: "read-email", email: {id: "x", subject: 'Re: "5 < 6" & more', avatarUrl: null}},
    ]);
});

test("an answer without a tool call is one text part", () => {
    expect(parseAgentMessage("nur text")).toEqual([{type: "text", content: "nur text"}]);
    expect(parseAgentMessage("")).toEqual([]);
});
