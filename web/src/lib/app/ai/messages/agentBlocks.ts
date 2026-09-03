/**
 * An answer, cut at the tool call elements the agent wrote into it.
 *
 * The markdown itself is left alone -- every markdown block still goes through the renderer.
 * What this split is for is the grouping: consecutive tool calls are shown as one collapsible
 * group, and that cannot be decided by a renderer that sees one element at a time.
 */
export type AgentBlock = {
    type: "markdown";
    content: string;
} | {
    type: "tools";
    calls: ToolCall[];
};

export type ToolCall = {
    /** The element name without the `toolcall-` prefix: `read-email`, `search-emails`, ... */
    kind: string;
    attributes: Record<string, string>;
    /** What stood inside the element; only thinking uses it. */
    content: string;
};

// Our own markup, so the shapes are known: an opening tag with quoted attributes, and either a
// closing tag or -- while the answer is still streaming -- nothing yet.
const TOOL_CALL = /<toolcall-([a-z-]+)((?:\s+[a-zA-Z-]+="[^"]*")*)\s*>([\s\S]*?)(?:<\/toolcall-\1>|$)/g;
const ATTRIBUTE = /([a-zA-Z-]+)="([^"]*)"/g;

/** The entities the server escapes when it writes markup, see `escapeAttribute`. */
const ENTITIES: [string, string][] = [
    ["&quot;", '"'],
    ["&lt;", "<"],
    ["&gt;", ">"],
    // Last: `&amp;lt;` has to come out as `&lt;`, not as `<`.
    ["&amp;", "&"],
];

function decode(value: string): string {
    return ENTITIES.reduce((decoded, [entity, character]) => decoded.replaceAll(entity, character), value);
}

function attributesOf(source: string): Record<string, string> {
    const attributes: Record<string, string> = {};
    for (const [, name, value] of source.matchAll(ATTRIBUTE)) attributes[name] = decode(value);
    return attributes;
}

export function agentBlocks(content: string): AgentBlock[] {
    const blocks: AgentBlock[] = [];
    let index = 0;

    const addMarkdown = (text: string) => {
        // The blank lines around an element are what separates it as a block; they are not part
        // of the text around it.
        const trimmed = text.trim();
        if (trimmed !== "") blocks.push({type: "markdown", content: trimmed});
    };

    const addToolCall = (call: ToolCall) => {
        const last = blocks.at(-1);
        // Only calls that follow each other directly belong in the same group; text in between
        // starts a new one.
        if (last?.type === "tools") last.calls.push(call);
        else blocks.push({type: "tools", calls: [call]});
    };

    for (const match of content.matchAll(TOOL_CALL)) {
        addMarkdown(content.slice(index, match.index));
        addToolCall({
            kind: match[1],
            attributes: attributesOf(match[2]),
            content: decode(match[3]).trim(),
        });
        index = match.index + match[0].length;
    }

    addMarkdown(content.slice(index));

    return blocks;
}
