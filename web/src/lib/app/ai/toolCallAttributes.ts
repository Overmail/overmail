/** The entities the server escapes when it writes an attribute, see `ReadEmailTool.markup`. */
const ENTITIES: [string, string][] = [
    ["&quot;", '"'],
    ["&lt;", "<"],
    ["&gt;", ">"],
    // Last: `&amp;lt;` has to come out as `&lt;`, not as `<`.
    ["&amp;", "&"],
];

/**
 * The value of an attribute of a tool call element, as it was before it went into the markup.
 *
 * The markdown renderer hands attributes over as they stand in the source -- entities included --
 * and an html parser may lowercase their names on the way, so the lookup ignores case.
 */
export function attributeOf(
    attributes: Record<string, string> | undefined,
    name: string,
): string | null {
    if (!attributes) return null;

    const wanted = name.toLowerCase();
    for (const [key, value] of Object.entries(attributes)) {
        if (key.toLowerCase() !== wanted) continue;
        return ENTITIES.reduce((decoded, [entity, character]) => decoded.replaceAll(entity, character), value);
    }

    return null;
}
