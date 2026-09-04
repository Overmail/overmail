import type {Component} from "svelte";
import {HouseIcon, StackIcon, TrayIcon} from "phosphor-svelte";

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
    matches: (pathname: string) => boolean;
};

/** One list for the menu and the header, so no page can be linked without a heading. */
export const navItems: NavItem[] = [
    {
        key: "app.nav.home",
        icon: HouseIcon,
        href: "/",
        matches: (pathname) => pathname === "/",
    },
    {
        key: "app.nav.mails",
        icon: TrayIcon,
        href: "/mails",
        matches: (pathname) => pathname.startsWith("/mails"),
    },
    {
        key: "app.nav.stack",
        icon: StackIcon,
        href: "/my-stack",
        matches: (pathname) => pathname.startsWith("/my-stack"),
    },
];

/** The page that is open, or null on a route the menu does not cover. */
export function currentNavItem(pathname: string): NavItem | null {
    return navItems.find((item) => item.matches(pathname)) ?? null;
}
