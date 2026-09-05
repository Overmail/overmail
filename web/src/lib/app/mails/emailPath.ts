/** How much of the subject a url carries; past this it says nothing more about the mail. */
const SUBJECT_LIMIT = 64;

/**
 * How a mail is spelled in a url: the beginning of its subject, then the id without its hyphens.
 *
 * The id is a uuid, and the bare hex is the shorter, tidier form for something a user sees and
 * copies. [subject] is what makes it readable, and it is what a caller knows about the mail;
 * without it -- a mail this client has never seen -- it is the id alone, which is the part that
 * identifies the mail anyway. [parseEmailId] reads it back.
 *
 * This is the form for a query parameter, where the encoding is the writer's business:
 * `URLSearchParams` encodes what it is handed, and handing it something encoded would come out
 * twice over. [emailPath] is the same slug for a path segment, where it does its own.
 */
export function emailSlug(id: string, subject?: string | null): string {
	const bare = bareId(id);
	const slug = subjectSlug(subject);

	return slug === "" ? bare : `${slug}-${bare}`;
}

/** Where a mail lives on its own page -- the one place that spells the route out. */
export function emailPath(id: string, subject?: string | null): string {
	const bare = bareId(id);
	const slug = subjectSlug(subject);

	return slug === "" ? `/emails/${bare}` : `/emails/${encodeURIComponent(slug)}-${bare}`;
}

/**
 * The mail a slug or a path segment is about, as the uuid the repository knows.
 *
 * Only the id at the end of it is read: the subject in front is there for the reader, and a url
 * that was edited by hand -- or one whose subject happens to end in hex -- still says which mail
 * it means. Null when there is no id in it at all.
 */
export function parseEmailId(value: string | null | undefined): string | null {
	if (!value) return null;

	const bare = value.slice(-32).toLowerCase();
	if (!/^[0-9a-f]{32}$/.test(bare)) return null;

	return [
		bare.slice(0, 8),
		bare.slice(8, 12),
		bare.slice(12, 16),
		bare.slice(16, 20),
		bare.slice(20)
	].join("-");
}

const bareId = (id: string) => id.replaceAll("-", "");

/** The readable part: the beginning of the subject as one word, spaces as hyphens, unencoded. */
function subjectSlug(subject: string | null | undefined): string {
	if (!subject) return "";

	// By code point, so a subject that starts with emoji is not cut through the middle of one --
	// a lone surrogate is not something encodeURIComponent can encode at all.
	const beginning = Array.from(subject).slice(0, SUBJECT_LIMIT).join("").trim();

	return beginning.replaceAll(/\s+/g, "-").replaceAll(/^-+|-+$/g, "");
}

/**
 * How a mail page is told that the listing sent the reader there: the query the table puts on the
 * url, and the one thing that page reads to decide whether it shows a way back.
 *
 * Here rather than spelled out twice, next to the route it belongs to -- the writer and the reader
 * of it are two different components.
 */
export const FROM_PARAM = "from";
export const FROM_MAIL_LIST = "mail-list";
