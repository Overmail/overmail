import {getContext, setContext} from "svelte";
import type {Inbox} from "$lib/repository/InboxRepository";
import type {InboxRepository} from "$lib/repository/InboxRepository";

/**
 * What a drop zone needs from the shell: where dropped files go, and what to say about where they
 * will end up while they are still being dragged.
 *
 * Provided once by `FileDrop`; a `FileDropZone` without it -- a list rendered outside the shell,
 * a test -- takes no files.
 */
export class FileDropController {
    /** The inboxes a mail would go to, read when a drag reaches a zone; null until then. */
    inboxes: Inbox[] | null = $state(null);
    /** While a dialog the last drop opened is up, a second batch would replace what it shows. */
    disabled = $state(false);

    private loading: Promise<void> | null = null;

    constructor(
        private readonly repository: InboxRepository,
        readonly handle: (files: File[]) => void,
    ) {}

    /** Reads the inboxes again, once per drag; a failure leaves the bubble on its generic text. */
    refreshInboxes() {
        this.loading ??= this.repository.list()
            .then((inboxes) => { this.inboxes = inboxes; })
            .catch(() => {})
            .finally(() => { this.loading = null; });
    }
}

const key = Symbol("file-drop");

export function provideFileDrop(controller: FileDropController) {
    setContext(key, controller);
}

export function useFileDrop(): FileDropController | null {
    return getContext<FileDropController | undefined>(key) ?? null;
}
