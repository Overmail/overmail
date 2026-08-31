import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
	return twMerge(clsx(inputs));
}

/**
 * Scrolls `container` -- and only it -- just far enough for `element` to be fully visible.
 * Unlike `Element.scrollIntoView` this never scrolls an ancestor, the popover or the page.
 */
export function scrollIntoViewWithin(element: HTMLElement, container: HTMLElement) {
	const elementRect = element.getBoundingClientRect();
	const containerRect = container.getBoundingClientRect();

	if (elementRect.top < containerRect.top) {
		container.scrollTop -= containerRect.top - elementRect.top;
	} else if (elementRect.bottom > containerRect.bottom) {
		container.scrollTop += elementRect.bottom - containerRect.bottom;
	}
}

/** Up to two initials from a display name or, failing that, an e-mail address. */
export function initials(nameOrAddress: string): string {
	return nameOrAddress
		.split(/[\s.@_-]+/)
		.filter(Boolean)
		.slice(0, 2)
		.map((part) => part[0]!.toUpperCase())
		.join("");
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type WithoutChild<T> = T extends { child?: any } ? Omit<T, "child"> : T;
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type WithoutChildren<T> = T extends { children?: any } ? Omit<T, "children"> : T;
export type WithoutChildrenOrChild<T> = WithoutChildren<WithoutChild<T>>;
export type WithElementRef<T, U extends HTMLElement = HTMLElement> = T & { ref?: U | null };
