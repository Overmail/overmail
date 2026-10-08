/**
 * [value] as deep state, the way a page hands a view to the table -- for a test, which cannot
 * write a rune itself.
 */
export function deepState<T>(value: T): T {
    const state = $state(value);
    return state;
}
