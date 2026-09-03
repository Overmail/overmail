import type {PromptEmail} from "$lib/app/ai/prompt";

/**
 * An answer as it is rendered: text, and the elements the agent wrote into it for the tools it
 * used. The markup comes from the server -- see `ReadEmailTool.markup` -- and is turned back into
 * components here rather than being put into the dom as html.
 */
export type AgentMessagePart = {
    type: "text",
    content: string,
} | {
    type: "read-email",
    email: PromptEmail,
}

const READ_EMAIL = /<toolcall-read-email emailId="([^"]*)" avatarUrl="([^"]*)" subject="([^"]*)"><\/toolcall-read-email>/g;

/** Attribute escaping of the server, reversed. `&amp;` last, or `&amp;lt;` would become `<`. */
function unescapeAttribute(value: string): string {
    return value
        .replaceAll("&quot;", '"')
        .replaceAll("&lt;", "<")
        .replaceAll("&gt;", ">")
        .replaceAll("&amp;", "&");
}

export function parseAgentMessage(content: string): AgentMessagePart[] {
    const parts: AgentMessagePart[] = [];
    let index = 0;

    for (const match of content.matchAll(READ_EMAIL)) {
        // The blank lines around the element are what separates it as a block in markdown; they
        // are not part of the text around it.
        const text = content.slice(index, match.index);
        if (text.trim() !== "") parts.push({type: "text", content: text.trim()});

        parts.push({
            type: "read-email",
            email: {
                id: unescapeAttribute(match[1]),
                subject: unescapeAttribute(match[3]),
                avatarUrl: unescapeAttribute(match[2]) || null,
            },
        });

        index = match.index + match[0].length;
    }

    const rest = content.slice(index);
    if (rest.trim() !== "") parts.push({type: "text", content: rest.trim()});

    return parts;
}
