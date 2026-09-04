/** How much of the subject a url carries; past this it says nothing more about the mail. */
const SUBJECT_LIMIT = 64;

/**
 * Where a mail lives on its own page -- the one place that spells the route out.
 *
 * The id goes in without its hyphens: it is a uuid, and the bare hex is the shorter, tidier url
 * for something a user sees and copies. Whoever reads the parameter puts them back.
 *
 * [subject] is what makes the link readable, and it is what a caller knows about the mail: the
 * beginning of it goes in front of the id, spaces as hyphens. Without it -- a mail this client
 * has never seen -- the url is the id alone, which is the part that identifies the mail anyway.
 */
export function emailPath(id: string, subject?: string | null): string {
	const bare = id.replaceAll("-", "");
	const slug = subjectSlug(subject);

	return slug === "" ? `/email/${bare}` : `/email/${slug}-${bare}`;
}

function subjectSlug(subject: string | null | undefined): string {
	if (!subject) return "";

	// By code point, so a subject that starts with emoji is not cut through the middle of one --
	// a lone surrogate is not something encodeURIComponent can encode at all.
	const beginning = Array.from(subject).slice(0, SUBJECT_LIMIT).join("").trim();

	// Everything that is not a space is left to the encoder; a hyphen survives it untouched,
	// which is what makes the run of them the readable part of the url.
	return encodeURIComponent(beginning.replaceAll(/\s+/g, "-")).replaceAll(/^-+|-+$/g, "");
}
