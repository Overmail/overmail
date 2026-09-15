import type {Component} from "svelte";
import {PaperPlaneTiltIcon, StackIcon, TrayIcon} from "phosphor-svelte";
import {INBOX_VIEW, predefinedViews} from "$lib/app/views/predefinedViews";
import {openViewId} from "$lib/app/views/viewPath";

/**
 * A page behind the sidebar.
 *
 * The label is a key rather than a string: it has to re-render in the new language when the
 * locale changes, and a plain string captured here would not.
 */
export type NavItem = {
    key: string;
    icon: Component;
    href: string;
    /** Whether this is what the url is showing -- the whole url, because a view is in its query. */
    matches: (url: URL) => boolean;
};

const ICONS: Record<string, Component> = {
    inbox: TrayIcon,
    sent: PaperPlaneTiltIcon,
};

/**
 * One list for the menu and the header, so no page can be linked without a heading.
 *
 * The views the app brings stand where a start page used to: they *are* what an account opens on,
 * and the inbox is what the listing shows when the url asks for nothing. The views somebody made
 * are listed under their own heading, see `ViewList`.
 */
export const navItems: NavItem[] = [
    ...predefinedViews().map((view) => ({
        key: view.label,
        icon: ICONS[view.id],
        // The inbox is the bare listing: it is what the url shows when it asks for nothing.
        href: view.id === INBOX_VIEW ? "/" : `/?view=${view.id}`,
        matches: (url: URL) =>
            url.pathname === "/" &&
            (openViewId(url) === view.id ||
                (view.id === INBOX_VIEW && openViewId(url) === null)),
    })),
    {
        key: "app.nav.stack",
        icon: StackIcon,
        href: "/my-stack",
        matches: (url: URL) => url.pathname.startsWith("/my-stack"),
    },
];

/** The page that is open, or null on a route the menu does not cover. */
export function currentNavItem(url: URL): NavItem | null {
    return navItems.find((item) => item.matches(url)) ?? null;
}
