import {tv} from "tailwind-variants";

/**
 * What a filter looks like in the bar above a listing: a small pill that is either set or not.
 *
 * One definition for all of them, because they stand next to each other -- a chip that is a
 * shade off or a pixel taller than the one beside it reads as a different kind of control. Set
 * is the accent-on-primary state, unset the quiet one; nothing else about a chip changes.
 *
 * The slots exist for the split ones: a chip can be one button ([root] and [action] on the same
 * element) or a button and a menu beside it, and only the pressable parts carry the hover.
 */
export const filterChip = tv({
    slots: {
        /** The pill itself. `overflow-hidden` keeps a half's hover inside the round edge. */
        root: "flex w-fit flex-row items-center overflow-hidden rounded-full text-sm",
        /**
         * A pressable part of it: the whole chip, or one half of a split one. The focus ring is
         * inset, because a half is inside the pill's `overflow-hidden` and an outset one would be
         * cut off at the edge.
         */
        action: "flex cursor-pointer flex-row items-center gap-1.5 px-2 py-0.5 outline-none transition-colors duration-100 focus-visible:ring-[3px] focus-visible:ring-inset focus-visible:ring-ring/50",
        /** The line between the two halves, so the split is visible before it is hovered. */
        divider: "w-px self-stretch",
    },
    variants: {
        active: {
            true: {
                root: "bg-primary-foreground text-primary",
                // A set chip is a light pill with the primary colour on it, so the hover darkens
                // the pill rather than tinting it towards that same colour -- `bg-primary/80`
                // would put the text's own colour behind the text. The relative colour keeps the
                // hue and takes a step off the lightness, which reads the same in both themes;
                // the badge's tint is built the same way.
                action: "hover:bg-[oklch(from_var(--primary-foreground)_calc(l_-_0.06)_c_h)]",
                divider: "bg-primary/25",
            },
            false: {
                root: "bg-accent text-accent-foreground",
                action: "hover:bg-accent/80",
                divider: "bg-foreground/10",
            },
        },
    },
    defaultVariants: {
        active: false,
    },
});
