/** One label as the search hands it out, with how much mail carries it. */
export type LabelSearchResult = {
	id: string;
	name: string;
	color: string;
	emailCount: number;
};

/**
 * The least a label has to be to be shown as a chip. A [LabelSearchResult] is one, and so is what
 * the entity cache keeps -- which is what a caller has after reading ids back out of a stored
 * filter.
 */
export type PickedLabel = {
	id: string;
	name: string;
	color: string;
};

/**
 * The labels whose name matches [query], in the order the server puts them.
 *
 * An empty query is not an empty answer: it is what to offer somebody who has not typed yet.
 * A request that fails is no labels rather than an error -- every caller is a list that can be
 * empty, and none of them can do anything about it.
 */
export async function findLabels(query: string): Promise<LabelSearchResult[]> {
	const response = await fetch(`/api/labels/search?query=${encodeURIComponent(query)}`);
	if (!response.ok) return [];

	const data: {
		labels: {id: string; name: string; color: string; email_count: number}[];
	} = await response.json();

	return data.labels.map((label) => ({
		id: label.id,
		name: label.name,
		color: label.color,
		emailCount: label.email_count
	}));
}
