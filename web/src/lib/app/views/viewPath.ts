import {parseSlugId, slugParam} from "$lib/app/mails/emailPath";
import {INBOX_VIEW, isPredefinedViewId} from "$lib/app/views/predefinedViews";

/**
 * Which view the listing is showing: `/?view=<slug>`, which is what makes a view something you
 * can link to, reload and step back out of -- the same arrangement `?email=` has for a mail.
 */
export const VIEW_PARAM = "view";

/**
 * How a view is spelled in a url: the beginning of its name, then the id without its hyphens.
 *
 * The name is there for the reader and is not read back -- a view that was renamed is still the
 * same view, and [parseViewId] only looks at the id at the end.
 */
export function viewSlug(id: string, name?: string | null): string {
    // A view the app brings is named, not identified by a uuid: its id *is* readable, so there is
    // nothing to put in front of it.
    if (isPredefinedViewId(id)) return id;

    return slugParam(id, name);
}

/**
 * The view a `?view=` value is about: the name of one the app brings, or the uuid of a stored
 * one. Null when it is neither, which is the listing nobody asked anything of.
 */
export function parseViewId(value: string | null | undefined): string | null {
    if (isPredefinedViewId(value)) return value;

    return parseSlugId(value);
}

/** The view the url [url] has open, or null when it is showing no view. */
export function openViewId(url: URL): string | null {
    return parseViewId(url.searchParams.get(VIEW_PARAM));
}

/**
 * Where a view is shown: the listing, with the view in the query.
 *
 * Built on the root and not on [current], so nothing of the url it is left from travels along --
 * in particular not the `?email=` of an open mail. Opening a view is leaving that mail, not
 * keeping it beside a different listing. [current] is only what the url is resolved against.
 */
export function viewUrl(id: string, name: string | null | undefined, current: URL): URL {
    const url = new URL("/", current);
    // The inbox is what the listing shows when nothing is asked for, so it is the bare root and
    // not `?view=inbox`: one address for one listing, and the shortest one at that.
    if (id !== INBOX_VIEW) url.searchParams.set(VIEW_PARAM, viewSlug(id, name));

    return url;
}

/**
 * The same as [viewUrl], as the value of an `href`: path and query, without the origin -- what a
 * link in the app is written with.
 */
export function viewHref(id: string, name: string | null | undefined, current: URL): string {
    const url = viewUrl(id, name, current);

    return `${url.pathname}${url.search}`;
}
