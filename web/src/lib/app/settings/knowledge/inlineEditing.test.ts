import {expect, mock, test} from "bun:test";
import {
    InlineEditing,
    MAX_KEYWORDS,
    normalizeKeyword,
    withKeyword,
    withoutKeyword,
} from "./inlineEditing.svelte";
import {KnowledgeNameTakenError, type KnowledgeEntry, type KnowledgeRepository} from "$lib/repository/KnowledgeRepository";

const ENTRY: KnowledgeEntry = {
    id: "k-1",
    name: "Stromvertrag",
    description: "Bei Rheinenergie.",
    keywords: ["strom"],
    relevantOn: "2026-04-01",
    createdAt: "2026-03-01T10:00:00Z",
    updatedAt: "2026-03-01T10:00:00Z",
    createdByAgent: false,
};

/** A repository whose `update` the test controls. */
function editing(update: KnowledgeRepository["update"] = mock(async () => ENTRY)) {
    const spy = mock(update);
    return {inline: new InlineEditing({update: spy} as unknown as KnowledgeRepository), update: spy};
}

test("a keyword is normalized the way the server stores it", () => {
    expect(normalizeKeyword("  Rheinenergie ")).toBe("rheinenergie");
    expect(normalizeKeyword("Strom   vertrag")).toBe("strom vertrag");
});

test("adding a keyword that changes nothing answers with nothing to write", () => {
    expect(withKeyword(["strom"], "rechnung")).toEqual(["strom", "rechnung"]);

    // Empty, and the same keyword in other capitals, are both already the state of the list.
    expect(withKeyword(["strom"], "   ")).toBeNull();
    expect(withKeyword(["strom"], "STROM")).toBeNull();

    const full = Array.from({length: MAX_KEYWORDS}, (_, i) => `k${i}`);
    expect(withKeyword(full, "rechnung")).toBeNull();
});

test("removing a keyword leaves the rest in order", () => {
    expect(withoutKeyword(["strom", "rechnung", "abschlag"], "rechnung")).toEqual(["strom", "abschlag"]);
    expect(withoutKeyword(["strom"], "gas")).toEqual(["strom"]);
});

test("saving one field sends the whole entry with that field changed", async () => {
    const {inline, update} = editing();

    const saved = await inline.save(ENTRY, "name", {name: "Strom"});

    expect(saved).toEqual(ENTRY);
    expect(update).toHaveBeenCalledWith("k-1", {
        name: "Strom",
        description: "Bei Rheinenergie.",
        keywords: ["strom"],
        relevantOn: "2026-04-01",
    });
    expect(inline.state).toEqual({type: "idle"});
});

test("a name that is taken is a failure on the name, and typing there clears it", async () => {
    const {inline} = editing(async () => {
        throw new KnowledgeNameTakenError();
    });

    expect(await inline.save(ENTRY, "name", {name: "Gasvertrag"})).toBeNull();
    expect(inline.failureIn("name")).toBe("nameTaken");
    // Only the cell it happened in says anything.
    expect(inline.failureIn("description")).toBeNull();

    inline.clearFailure("name");
    expect(inline.state).toEqual({type: "idle"});
});

test("any other refusal is a failure the cell keeps standing", async () => {
    const {inline} = editing(async () => {
        throw new Error("500");
    });

    expect(await inline.save(ENTRY, "keywords", {keywords: []})).toBeNull();
    expect(inline.failureIn("keywords")).toBe("unknown");
    // The cell that failed is not the one being typed in; that one is untouched.
    inline.clearFailure("name");
    expect(inline.failureIn("keywords")).toBe("unknown");
});

test("only one save at a time, so two cells cannot overwrite each other", async () => {
    let release: (entry: KnowledgeEntry) => void = () => {};
    const {inline, update} = editing(() => new Promise((resolve) => (release = resolve)));

    const first = inline.save(ENTRY, "description", {description: "Neu."});
    expect(inline.savingIn("description")).toBe(true);

    expect(await inline.save(ENTRY, "name", {name: "Strom"})).toBeNull();
    expect(update).toHaveBeenCalledTimes(1);
    // And the one that was refused is not a failure the user is shown -- nothing was written.
    expect(inline.savingIn("description")).toBe(true);

    release(ENTRY);
    expect(await first).toEqual(ENTRY);
    expect(inline.state).toEqual({type: "idle"});
});
