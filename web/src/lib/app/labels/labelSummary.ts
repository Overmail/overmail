/**
 * What a filter chip says about the labels it is on: the first few by name, and how many are left
 * over.
 *
 * Names rather than a count alone, because the point of the chip is to be read at a glance --
 * "Studium, HPI und 2 weitere" answers what is filtered, "4 Labels" only that something is. The
 * rest is a number and not more names: the chip sits in a row of other filters and cannot grow
 * with the selection.
 */
export function summariseLabels(names: string[], shown = 2): {shown: string[]; rest: number} {
    // One over the limit is spelled out rather than summarised: "A, B und 1 weiteres" is longer
    // than "A, B, C" and says less.
    if (names.length <= shown + 1) return {shown: names, rest: 0};

    return {shown: names.slice(0, shown), rest: names.length - shown};
}
