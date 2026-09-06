import {expect, mock, test} from "bun:test";
import {MAX_KEYWORDS, NewKnowledgeViewModel} from "./NewKnowledgeViewModel.svelte";
import {KnowledgeNameTakenError, type KnowledgeRepository} from "$lib/repository/KnowledgeRepository";

const WRITTEN = {
    id: "k-1",
    name: "Stromvertrag",
    description: "Bei Rheinenergie.",
    keywords: ["strom"],
    relevantOn: null,
    createdAt: "2026-03-01T10:00:00Z",
    updatedAt: "2026-03-01T10:00:00Z",
    createdByAgent: false,
};

/** A repository whose `create` the test controls. */
function writing(create: KnowledgeRepository["create"] = mock(async () => WRITTEN)) {
    const spy = mock(create);
    return {viewModel: new NewKnowledgeViewModel({create: spy} as unknown as KnowledgeRepository), create: spy};
}

/** A form filled in far enough to be submittable. */
function filledIn() {
    const {viewModel, create} = writing();
    viewModel.setName("Stromvertrag");
    viewModel.description = "Bei Rheinenergie.";
    return {viewModel, create};
}

test("an entry needs a name and something known, and nothing else", () => {
    const {viewModel} = writing();
    expect(viewModel.canSubmit).toBe(false);

    viewModel.setName("  Stromvertrag  ");
    expect(viewModel.canSubmit).toBe(false);

    viewModel.description = "Bei Rheinenergie.";
    expect(viewModel.canSubmit).toBe(true);

    // Whitespace is not an answer to either field.
    viewModel.description = "   ";
    expect(viewModel.canSubmit).toBe(false);
});

test("keywords are stored the way the server would store them", () => {
    const {viewModel} = writing();

    viewModel.keywordDraft = "  Rheinenergie  ";
    viewModel.commitKeywords();

    // Lowercase and trimmed, so the chip is the keyword that ends up in the row.
    expect(viewModel.keywords).toEqual(["rheinenergie"]);
    expect(viewModel.keywordDraft).toBe("");

    // And the same word again in different capitals is the same keyword.
    viewModel.commitKeywords("RHEINENERGIE, strom  vertrag");
    expect(viewModel.keywords).toEqual(["rheinenergie", "strom vertrag"]);
});

test("a pasted list becomes one chip per keyword, empties dropped", () => {
    const {viewModel} = writing();

    viewModel.commitKeywords("rechnung, , abschlag,");

    expect(viewModel.keywords).toEqual(["rechnung", "abschlag"]);
});

test("what does not fit stays in the field instead of disappearing", () => {
    const {viewModel} = writing();

    viewModel.commitKeywords(Array.from({length: MAX_KEYWORDS + 2}, (_, i) => `k${i}`).join(","));

    expect(viewModel.keywords.length).toBe(MAX_KEYWORDS);
    expect(viewModel.keywordsFull).toBe(true);
    expect(viewModel.keywordDraft).toBe(`k${MAX_KEYWORDS}, k${MAX_KEYWORDS + 1}`);
});

test("backspace on an empty field takes the last chip back for correcting", () => {
    const {viewModel} = writing();
    viewModel.commitKeywords("strom, rechnnung");

    viewModel.editLastKeyword();
    expect(viewModel.keywords).toEqual(["strom"]);
    expect(viewModel.keywordDraft).toBe("rechnnung");

    // Not while something is being typed: there, backspace edits that.
    viewModel.editLastKeyword();
    expect(viewModel.keywords).toEqual(["strom"]);
});

test("submitting sends the trimmed entry and takes a keyword still in the field with it", async () => {
    const {viewModel, create} = filledIn();
    viewModel.keywordDraft = "strom";

    const entry = await viewModel.submit();

    expect(entry).toEqual(WRITTEN);
    expect(create).toHaveBeenCalledWith({
        name: "Stromvertrag",
        description: "Bei Rheinenergie.",
        keywords: ["strom"],
        relevantOn: null,
    });
    expect(viewModel.saveState).toEqual({type: "idle"});
});

test("a day is sent when one was picked", async () => {
    const {viewModel, create} = filledIn();
    viewModel.relevantOn = "2026-04-01";

    await viewModel.submit();

    expect((create as any).mock.calls[0][0].relevantOn).toBe("2026-04-01");
});

test("a name that is taken is its own state, and typing in the field clears it", async () => {
    const {viewModel} = writing(async () => {
        throw new KnowledgeNameTakenError();
    });
    viewModel.setName("Stromvertrag");
    viewModel.description = "Bei Rheinenergie.";

    expect(await viewModel.submit()).toBeNull();
    expect(viewModel.saveState).toEqual({type: "nameTaken"});

    // The field the message points at is the one being corrected.
    viewModel.setName("Stromvertrag 2");
    expect(viewModel.saveState).toEqual({type: "idle"});
});

test("any other failure leaves the form standing with the reason on it", async () => {
    const {viewModel} = writing(async () => {
        throw new Error("500");
    });
    viewModel.setName("Stromvertrag");
    viewModel.description = "Bei Rheinenergie.";

    expect(await viewModel.submit()).toBeNull();
    expect(viewModel.saveState).toEqual({type: "failed"});
    // Nothing is cleared: the entry is not written, and retyping it is not the user's job.
    expect(viewModel.name).toBe("Stromvertrag");
});

test("an incomplete form does not reach the server", async () => {
    const {viewModel, create} = writing();
    viewModel.setName("Stromvertrag");

    expect(await viewModel.submit()).toBeNull();
    expect(create).not.toHaveBeenCalled();
});

test("resetting is what makes the next entry a new one", () => {
    const {viewModel} = filledIn();
    viewModel.commitKeywords("strom");
    viewModel.relevantOn = "2026-04-01";

    viewModel.reset();

    expect(viewModel.name).toBe("");
    expect(viewModel.description).toBe("");
    expect(viewModel.keywords).toEqual([]);
    expect(viewModel.relevantOn).toBe("");
    expect(viewModel.canSubmit).toBe(false);
});
