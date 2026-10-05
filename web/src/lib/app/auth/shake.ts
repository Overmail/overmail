/**
 * A short sideways shake for a code that was turned down, on top of the field turning red. Through
 * the Web Animations API rather than a class, so a second wrong code shakes again without the class
 * having to come off first. Whoever asked for less motion gets the red alone.
 */
export function shake(node: Element | null | undefined) {
    if (!node || window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    node.animate(
        [0, -6, 6, -4, 4, -2, 0].map((x) => ({transform: `translateX(${x}px)`})),
        {duration: 400, easing: "ease-out"},
    );
}
