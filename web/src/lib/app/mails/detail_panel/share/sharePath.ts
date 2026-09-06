/**
 * Where a shared mail is read: `/share/<id without hyphens>`.
 *
 * The bare hex is the form this app uses for ids a user sees and copies, like [emailPath]. The
 * page itself is not built yet -- this is the link the dialog hands out, and the one place that
 * spells the route out, so building it is a change to this file.
 */
export function sharePath(shareId: string): string {
	return `/share/${shareId.replaceAll("-", "")}`;
}

/**
 * The link as it is copied out of the dialog: absolute, because it is pasted somewhere that is
 * not this app.
 *
 * [origin] is `location.origin` for the browser; a caller that has none -- the server renderer --
 * has no clipboard either.
 */
export function shareUrl(shareId: string, origin: string): string {
	return `${origin}${sharePath(shareId)}`;
}
