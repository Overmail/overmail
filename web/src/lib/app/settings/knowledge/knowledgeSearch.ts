import type {KnowledgeEntry} from "$lib/repository/KnowledgeRepository";

/**
 * Searching the knowledge the settings screen already has, the way the agent searches it.
 *
 * The list route hands back every entry of a user -- a few hundred short rows -- so filtering is
 * done here and not in a route: a request per keystroke would answer nothing the browser is not
 * already holding.
 *
 * The rules are the server's (`KnowledgeStore.search` and `String.fuzzyContains`), so a user
 * looking something up in the table gets the same hits the assistant gets while it sorts a mail.
 * The one difference is the haystack: the server matches keywords and name, because that is what
 * a model asks by, while here the description is searched too -- the table shows it, and a user
 * looks for what they read.
 */

/**
 * How much a term is worth by where it was found.
 *
 * A name hit says the entry is about the word; a keyword hit says it is filed under it; the
 * description mentions it. That is the order, and it only decides ties -- how many of the typed
 * words an entry carries comes first, as on the server.
 */
const WEIGHT = {name: 3, keyword: 2, description: 1} as const;

const UMLAUTS: Record<string, string> = {"ä": "ae", "ö": "oe", "ü": "ue", "ß": "ss"};

/** Lowercased and with the umlauts written out, so "koeln" and "Köln" are the same string. */
function normalize(value: string): string {
    let normalized = "";
    for (const char of value.toLowerCase()) normalized += UMLAUTS[char] ?? char;
    return normalized;
}

/**
 * Whether [needle] appears in [haystack] as a subsequence -- its letters in order, gaps allowed.
 *
 * The server's `fuzzyContains`, minus the matched ranges nothing here highlights. Loose on
 * purpose: it is what makes "rechnung" find "rechnungen" and a typo still find its entry.
 */
export function fuzzyContains(haystack: string, needle: string): boolean {
    const inHaystack = normalize(haystack);
    let index = 0;
    for (const char of normalize(needle)) {
        index = inHaystack.indexOf(char, index);
        if (index === -1) return false;
        index++;
    }
    return true;
}

/**
 * A query as the words to look for: commas and whitespace separate them, and a single letter is
 * not one -- a subsequence match would answer it with everything.
 */
export function searchTerms(query: string): string[] {
    const terms = query
        .split(/[\s,]+/)
        .map((term) => term.trim().toLowerCase())
        .filter((term) => term.length > 1);
    return [...new Set(terms)];
}

/** How many of [terms] an entry carries, and what those hits are worth by where they are. */
function score(entry: KnowledgeEntry, terms: string[]): {matched: number; weight: number} {
    let matched = 0;
    let weight = 0;
    for (const term of terms) {
        const hit = bestHit(entry, term);
        if (hit === 0) continue;
        matched++;
        weight += hit;
    }
    return {matched, weight};
}

/** The best place one term was found in, or 0 when the entry does not carry it at all. */
function bestHit(entry: KnowledgeEntry, term: string): number {
    if (fuzzyContains(entry.name, term)) return WEIGHT.name;
    if (entry.keywords.some((keyword) => fuzzyContains(keyword, term))) return WEIGHT.keyword;
    if (fuzzyContains(entry.description, term)) return WEIGHT.description;
    return 0;
}

/**
 * The entries that account for [query], the best hits first.
 *
 * Every typed word has to be somewhere in the entry. That is stricter than the server's own
 * lookup, which ranks by how many words an entry carries and hands the agent the best of them --
 * but this is a filter over a table somebody is looking at, and there a second word means "and
 * this too". A search that grew the list as you typed would be a search nobody trusts.
 *
 * Ranked by where the words were found (see [WEIGHT]); ties keep the order [entries] came in,
 * which is the list's own: what was written last stays on top.
 *
 * A blank query is not an empty answer: it is the list, untouched.
 */
export function searchKnowledge(entries: KnowledgeEntry[], query: string): KnowledgeEntry[] {
    const terms = searchTerms(query);
    if (terms.length === 0) return entries;

    return entries
        .map((entry, index) => ({entry, index, ...score(entry, terms)}))
        .filter((hit) => hit.matched === terms.length)
        .sort((a, b) => b.weight - a.weight || a.index - b.index)
        .map((hit) => hit.entry);
}
