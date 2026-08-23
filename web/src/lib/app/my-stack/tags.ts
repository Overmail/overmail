/**
 * Tags whose letters contain the query in order, best match first. Typing "rng" finds "Rechnung",
 * which is the point: the query is a sketch of the tag, not a prefix of it.
 */
export function fuzzySearch(query: string, tags: readonly string[]): string[] {
	const trimmed = query.trim();
	if (!trimmed) return [];

	return tags
		.map((tag) => ({ tag, score: fuzzyScore(trimmed, tag) }))
		.filter((match): match is { tag: string; score: number } => match.score !== null)
		.sort((a, b) => b.score - a.score || a.tag.localeCompare(b.tag))
		.map((match) => match.tag);
}

/** `null` when the query is not a subsequence of the tag; higher is a closer match. */
function fuzzyScore(query: string, tag: string): number | null {
	const needle = query.toLowerCase();
	const haystack = tag.toLowerCase();

	let score = 0;
	let from = 0;
	let streak = 0;

	for (const char of needle) {
		const at = haystack.indexOf(char, from);
		if (at < 0) return null;

		// A letter that follows the previous hit, or starts a word, says far more about the match
		// than one found somewhere in the middle.
		streak = at === from ? streak + 1 : 0;
		const startsWord = at === 0 || /[\s\-_/]/.test(haystack[at - 1] ?? '');
		score += 1 + streak * 2 + (startsWord ? 3 : 0);
		from = at + 1;
	}

	// Between two tags with the same hits, the shorter one is the closer match.
	return score - haystack.length * 0.1;
}
