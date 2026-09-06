import {slugSegment} from "$lib/app/mails/emailPath";
import type {Share} from "$lib/repository/ShareRepository";

/**
 * Where a shared mail is read: `/share/<subject>-<id without hyphens>`, like a mail page.
 *
 * [subject] is the readable part and is left out where there is none -- and, more importantly,
 * where the link must not carry it; [subjectFor] is what decides that. The page itself is not
 * built yet: this is the link the dialog hands out, and the one place that spells the route out,
 * so building it is a change to this file.
 */
export function sharePath(shareId: string, subject?: string | null): string {
	return `/share/${slugSegment(shareId, subject)}`;
}

/**
 * The link as it is copied out of the dialog: absolute, because it is pasted somewhere that is
 * not this app.
 *
 * [origin] is `location.origin` for the browser; a caller that has none -- the server renderer --
 * has no clipboard either.
 */
export function shareUrl(shareId: string, origin: string, subject?: string | null): string {
	return `${origin}${sharePath(shareId, subject)}`;
}

/**
 * The subject this share's link may carry, or null.
 *
 * A url is read by everything a link passes through -- a chat, a mail, a proxy log -- and by
 * anyone looking over a shoulder, none of which type the password. So the subject only goes into
 * the link where a visitor is shown it anyway: no password at all, or one that leaves the
 * metadata open.
 */
export function subjectFor(share: Share, subject: string | null | undefined): string | null {
	if (share.hasPassword && !share.allowMetadataWithoutPassword) return null;

	return subject ?? null;
}
