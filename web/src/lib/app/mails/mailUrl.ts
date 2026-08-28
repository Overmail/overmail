/**
 * The address of one mail's screen, and how to read the mail back out of it.
 *
 * The path carries the subject in front of the id -- so a link in a chat, a browser history entry
 * or an open tab says which mail it is without opening it. Only the id is read back: the subject is
 * decoration, it may be stale or edited or missing entirely and nothing depends on it.
 */

/** How much of the subject goes into the path. Long enough to recognise, short enough to read. */
const SUBJECT_CHARS = 60;

/** The id at the end of the path, as it is spelled -- a UUID, so the subject may hold hyphens. */
const ID_AT_END = /([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/i;

/**
 * Where a mail's screen lives. The subject goes in front of the id, url-encoded and cut to
 * [SUBJECT_CHARS]; a mail without one is addressed by its id alone.
 */
export function mailPath(id: string, subject?: string | null): string {
	const label = (subject ?? '').slice(0, SUBJECT_CHARS).trim();
	return label ? `/email/${encodeURIComponent(label)}-${id}` : `/email/${id}`;
}

/**
 * The mail id out of such a path segment: everything before the id is dropped. A segment that is
 * already a bare id passes through unchanged, which is what an older link is.
 */
export function mailIdFromParam(param: string): string {
	return ID_AT_END.exec(param)?.[1] ?? param;
}
