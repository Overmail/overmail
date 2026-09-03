/**
 * Who owns the keyboard while the stack is on screen.
 *
 * The stack shortcuts (Space, Backspace, A) are registered on the stack element rather than on
 * the document, so they only fire while the stack itself is focused. That makes the scope
 * structural, but it only holds as long as the focus comes back to the stack: bits-ui returns it
 * to the button that opened an overlay, and those buttons answer to Space and Enter themselves --
 * a trigger that keeps the focus swallows the next shortcut and reopens instead.
 *
 * So every overlay that can be opened from the stack hands the focus back through this context
 * when it closes.
 */
import {getContext, setContext} from "svelte";

const key = Symbol("my-stack-focus");

export type StackFocus = {
    /** Move the focus to the stack, away from whatever control opened the overlay. */
    restore: () => void;
};

export function setStackFocus(focus: StackFocus) {
    setContext(key, focus);
}

/** Null wherever the component is used outside the stack page, e.g. in a preview harness. */
export function getStackFocus(): StackFocus | null {
    return getContext<StackFocus | undefined>(key) ?? null;
}
