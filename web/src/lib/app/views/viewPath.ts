import {parseSlugId, slugParam} from "$lib/app/mails/emailPath";
import {INBOX_VIEW, isPredefinedViewId} from "$lib/app/views/predefinedViews";
import type {ViewSettings} from "$lib/app/views/viewSettings";
import type {
    ViewArchivedState,
    ViewFilter,
    ViewGrouping,
    ViewGroupingKind,
    ViewSorting,
    ViewSortingKind,
} from "$lib/repository/ViewSocket";

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

/**
 * A listing spelled out in a url instead of named by it: `/?view={…}`, the settings as json.
 *
 * For what the app builds on the spot and nothing stores -- the listing of one mail account, see
 * `accountView`. The inbox and sent have a name because they are one thing for everybody; a
 * listing with something of the account in it would need a name per account, and the settings say
 * the same without one. A filter that is not set is left out, which is most of them.
 *
 * It shares `?view=` with the names and the slugs and cannot be taken for either: a slug ends in
 * the hex of an id, this ends in a brace.
 */
export function inlineViewParam(settings: ViewSettings): string {
    const filter = Object.fromEntries(
        FILTER_KEYS.map((key) => [key, settings.filter[key]]).filter(([, value]) => value !== null)
    );

    return JSON.stringify({groupings: settings.groupings, filter, sorting: settings.sorting});
}

/** Where the listing [settings] describe is shown, as the value of an `href`. */
export function inlineViewHref(settings: ViewSettings, current: URL): string {
    const url = new URL("/", current);
    url.searchParams.set(VIEW_PARAM, inlineViewParam(settings));

    return `${url.pathname}${url.search}`;
}

/** The listing the url [url] spells out, or null when it names a view or asks for none. */
export function openInlineView(url: URL): ViewSettings | null {
    return parseInlineView(url.searchParams.get(VIEW_PARAM));
}

/**
 * The settings a `?view=` value spells out, or null when it does not.
 *
 * Read strictly: a url is something anybody can type, and what comes out of this is handed to the
 * table and the server as a view. Anything that is not exactly one is no view at all.
 */
export function parseInlineView(value: string | null | undefined): ViewSettings | null {
    if (!value?.startsWith("{")) return null;

    let raw: unknown;
    try {
        raw = JSON.parse(value);
    } catch {
        return null;
    }
    if (!isRecord(raw) || !Array.isArray(raw.groupings) || !isRecord(raw.filter)) return null;

    const groupings = raw.groupings.map((grouping) => ordered(grouping, GROUPING_KINDS));
    const sorting = ordered(raw.sorting, SORTING_KINDS);
    const filter = raw.filter;

    const readState = filter.readState ?? null;
    const archivedState = strings(filter.archivedState, ARCHIVED_STATES);
    const lists = [filter.imapAccountIds, filter.sentBy, filter.sentTo, filter.hasLabels].map((list) => strings(list));

    if (groupings.includes(null) || sorting === null) return null;
    if (readState !== null && typeof readState !== "boolean") return null;
    if (archivedState === undefined || lists.includes(undefined)) return null;

    return {
        groupings: groupings as ViewGrouping[],
        filter: {
            readState,
            archivedState: archivedState as ViewArchivedState[] | null,
            imapAccountIds: lists[0] as string[] | null,
            sentBy: lists[1] as string[] | null,
            sentTo: lists[2] as string[] | null,
            hasLabels: lists[3] as string[] | null,
        },
        sorting: sorting as ViewSorting,
    };
}

const FILTER_KEYS: (keyof ViewFilter)[] = [
    "readState",
    "archivedState",
    "imapAccountIds",
    "sentBy",
    "sentTo",
    "hasLabels",
];

const GROUPING_KINDS: ViewGroupingKind[] = [
    "date_smart",
    "year",
    "month",
    "day",
    "sender",
    "imap_account",
    "read",
    "archived",
];
const SORTING_KINDS: ViewSortingKind[] = ["date", "sender", "subject"];
const ARCHIVED_STATES: ViewArchivedState[] = ["Archive", "Unarchive", "Spam"];

const isRecord = (value: unknown): value is Record<string, unknown> =>
    typeof value === "object" && value !== null && !Array.isArray(value);

/** A grouping or a sorting of one of [kinds], or null when [value] is neither. */
function ordered(value: unknown, kinds: readonly string[]): {kind: string; reversed: boolean} | null {
    if (!isRecord(value) || typeof value.kind !== "string" || !kinds.includes(value.kind)) return null;

    return {kind: value.kind, reversed: value.reversed === true};
}

/**
 * A filter's list: null when it is not set, undefined when it is not a list of strings -- or, with
 * [allowed], not one of those.
 */
function strings(value: unknown, allowed?: readonly string[]): string[] | null | undefined {
    if (value === undefined || value === null) return null;
    if (!Array.isArray(value)) return undefined;
    if (!value.every((entry) => typeof entry === "string" && (allowed?.includes(entry) ?? true))) return undefined;

    return value as string[];
}
