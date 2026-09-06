import {expect, test} from "bun:test";
import {searchKnowledge, searchTerms} from "./knowledgeSearch";
import type {KnowledgeEntry} from "$lib/repository/KnowledgeRepository";

function entry(id: string, name: string, description: string, keywords: string[]): KnowledgeEntry {
    return {
        id,
        name,
        description,
        keywords,
        relevantOn: null,
        createdAt: "2026-03-01T10:00:00Z",
        updatedAt: "2026-03-01T10:00:00Z",
        createdByAgent: false,
    };
}

/** The list as the table holds it: freshest first, which is the order a tie has to keep. */
const ENTRIES: KnowledgeEntry[] = [
    entry("k-1", "Stromvertrag", "Bei Rheinenergie, monatlicher Abschlag.", ["strom", "rheinenergie"]),
    entry("k-2", "Umzug nach Köln", "Im Mai, neue Adresse in der Südstadt.", ["umzug", "adresse"]),
    entry("k-3", "Fahrrad", "Das Rad ist bei der Versicherung gemeldet.", ["versicherung"]),
    entry("k-4", "Abschlag Wasser", "Wird einmal im Jahr abgerechnet.", ["wasser"]),
];

function ids(query: string): string[] {
    return searchKnowledge(ENTRIES, query).map((hit) => hit.id);
}

test("a word in the name finds the entry", () => {
    expect(ids("Stromvertrag")).toEqual(["k-1"]);
});

test("a word that is only a keyword finds the entry", () => {
    expect(ids("rheinenergie")).toEqual(["k-1"]);
});

test("a word that is only in the description finds the entry", () => {
    expect(ids("abgerechnet")).toEqual(["k-4"]);
});

test("a name hit ranks above a description hit", () => {
    // "Abschlag" is k-4's name and a word in k-1's description, and k-1 is the earlier row.
    expect(ids("abschlag")).toEqual(["k-4", "k-1"]);
});

test("a second word narrows the list rather than widening it", () => {
    // k-4 carries "abschlag" in its name but nothing of "rheinenergie", so it drops out: a filter
    // that grew as you typed would be one nobody trusts. k-1 has both.
    expect(ids("abschlag rheinenergie")).toEqual(["k-1"]);
    expect(ids("abschlag wasser")).toEqual(["k-4"]);
});

test("capitals and umlauts are not part of the query", () => {
    expect(ids("STROM")).toEqual(["k-1"]);
    expect(ids("koeln")).toEqual(["k-2"]);
    expect(ids("Südstadt")).toEqual(["k-2"]);
});

test("a word only has to be in there, letter by letter", () => {
    // A typo and a longer form of the word both still find it.
    expect(ids("stromvertag")).toEqual(["k-1"]);
    expect(ids("versicherungen")).toEqual([]);
    expect(ids("versicher")).toEqual(["k-3"]);
});

test("commas separate words just like spaces", () => {
    expect(searchTerms("strom, umzug\nrad")).toEqual(["strom", "umzug", "rad"]);
    // A single letter is not a word: a subsequence match would answer it with everything.
    expect(searchTerms("a b strom")).toEqual(["strom"]);
    // And the same word twice is looked for once.
    expect(searchTerms("strom strom")).toEqual(["strom"]);
});

test("a query nothing carries is no hits, not the whole list", () => {
    expect(ids("zzzq")).toEqual([]);
});

test("a blank query is the list, in the order it came in", () => {
    expect(searchKnowledge(ENTRIES, "")).toEqual(ENTRIES);
    expect(searchKnowledge(ENTRIES, "   ")).toEqual(ENTRIES);
    // Nothing to look for is the same thing, whether it was blank or a single letter.
    expect(searchKnowledge(ENTRIES, "a")).toEqual(ENTRIES);
});
