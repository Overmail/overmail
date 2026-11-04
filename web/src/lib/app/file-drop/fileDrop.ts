/**
 * What can be dropped onto Overmail, and how a file is told to be one of those.
 *
 * Every kind is one entry here, one overlay in `FileDropOverlay` and one handler in `FileDrop`;
 * nothing else needs to know a kind exists.
 */
export type FileDropKind = "eml";

type KindSpec = {
    kind: FileDropKind;
    /** Lower case, with the dot. What a dropped file is told by. */
    extensions: string[];
    /**
     * What the browser may report while the file is still being dragged, when only the types are
     * readable and not the names. Not every system knows them, see [previewDrag].
     */
    mimeTypes: string[];
};

const KINDS: KindSpec[] = [
    {kind: "eml", extensions: [".eml"], mimeTypes: ["message/rfc822"]},
];

/** The kind of a dropped file, by its name; the type the browser guessed is the fallback. */
export function kindOfFile(file: File): FileDropKind | null {
    const name = file.name.toLowerCase();
    const byName = KINDS.find((spec) => spec.extensions.some((extension) => name.endsWith(extension)));
    if (byName) return byName.kind;
    return KINDS.find((spec) => spec.mimeTypes.includes(file.type.toLowerCase()))?.kind ?? null;
}

/** What is being dragged, as far as the browser lets a page see it before the drop. */
export type DragPreview =
    /** Every file is of this kind. */
    | {type: "kind"; kind: FileDropKind; count: number}
    /** Files whose kind cannot be told yet -- no type reported, or several kinds at once. */
    | {type: "unknown"; count: number}
    /** Files, none of which Overmail can do anything with. */
    | {type: "unsupported"; count: number};

/** Whether a drag carries files at all; a drag inside the page (reordering, text) does not. */
export function draggesFiles(transfer: DataTransfer | null): boolean {
    return transfer?.types.includes("Files") ?? false;
}

/**
 * Reads what is being dragged from its types alone: the names are only handed out on the drop.
 *
 * A type the browser leaves empty is not taken as unsupported -- a `.eml` has none on some
 * systems -- so such a drag shows the generic overlay and the drop decides by name.
 */
export function previewDrag(transfer: DataTransfer): DragPreview {
    const files = Array.from(transfer.items).filter((item) => item.kind === "file");
    const count = files.length;

    const kinds = new Set<FileDropKind | null>();
    for (const item of files) {
        const type = item.type.toLowerCase();
        if (type === "") return {type: "unknown", count};
        kinds.add(KINDS.find((spec) => spec.mimeTypes.includes(type))?.kind ?? null);
    }

    const known = [...kinds].filter((kind): kind is FileDropKind => kind !== null);
    if (known.length === 0) return {type: "unsupported", count};
    if (known.length === 1 && !kinds.has(null)) return {type: "kind", kind: known[0], count};
    return {type: "unknown", count};
}

/** Dropped files by their kind; those of no kind are left out. */
export function groupByKind(files: Iterable<File>): Map<FileDropKind, File[]> {
    const groups = new Map<FileDropKind, File[]>();
    for (const file of files) {
        const kind = kindOfFile(file);
        if (kind === null) continue;
        groups.set(kind, [...(groups.get(kind) ?? []), file]);
    }
    return groups;
}
