import type {Share, ShareRepository} from "$lib/repository/ShareRepository";

/**
 * How long the copy button stays a checkmark. The same as the one in the mail toolbar, because it
 * says the same thing: that worked, and nothing more.
 */
export const COPIED_FLASH_MS = 2000;

/** Where the list of links stands. */
export type ListState = {type: "loading"} | {type: "ready"} | {type: "failed"};

/**
 * The share dialog around the form: the links this mail is already out under, and what can be
 * done to one of them -- copied, opened for editing, taken back.
 *
 * The form itself is [ShareFormViewModel], one instance per dialog: the edit window is a window
 * of its own, and it must not write into the fields of the create form standing behind it.
 */
export class ShareDialogViewModel {
    shares: Share[] = $state([]);
    listState: ListState = $state({type: "loading"});

    /** The share the edit window is open on; null closes it. */
    editing: Share | null = $state(null);

    /** The share whose link was last copied, so the button can say it worked. */
    copied: string | null = $state(null);

    /** The share the delete confirmation is open on. */
    deleting: Share | null = $state(null);
    deleteFailed = $state(false);

    private copiedTimeout: ReturnType<typeof setTimeout> | null = null;

    constructor(
        private readonly emailId: string,
        private readonly shares_: ShareRepository,
        /** How long the checkmark stays; a test does not wait two seconds to see it go. */
        private readonly flashMs: number = COPIED_FLASH_MS,
    ) {}

    /** Reads the links this mail has. The dialog is opened on this, and every write repeats it. */
    async load(signal?: AbortSignal): Promise<void> {
        this.listState = {type: "loading"};
        try {
            this.shares = await this.shares_.list(this.emailId, signal);
            this.listState = {type: "ready"};
        } catch {
            if (signal?.aborted) return;
            this.listState = {type: "failed"};
        }
    }

    /**
     * Puts the link on the clipboard and remembers which one, so the button can say so.
     *
     * The checkmark goes back to a link icon on its own: it is a receipt for what just happened,
     * and one that stays would read as a state the link is in.
     */
    async copy(share: Share, url: string): Promise<boolean> {
        try {
            await navigator.clipboard.writeText(url);
        } catch {
            this.copied = null;
            return false;
        }

        this.copied = share.id;
        if (this.copiedTimeout) clearTimeout(this.copiedTimeout);
        this.copiedTimeout = setTimeout(() => {
            this.copied = null;
            this.copiedTimeout = null;
        }, this.flashMs);

        return true;
    }

    /** Opens the confirmation. A link is handed out, so taking it back is worth one question. */
    askToDelete(share: Share) {
        this.deleting = share;
        this.deleteFailed = false;
    }

    /** Takes the link back for good, and closes whatever was open on it. */
    async confirmDelete(): Promise<boolean> {
        const target = this.deleting;
        if (!target) return false;

        try {
            await this.shares_.remove(this.emailId, target.id);
        } catch {
            // The link is still there, so the confirmation stays open with the reason on it.
            this.deleteFailed = true;
            return false;
        }

        this.deleting = null;
        if (this.editing?.id === target.id) this.editing = null;
        if (this.copied === target.id) this.copied = null;
        await this.load();
        return true;
    }

    /** Drops the timer, so a dialog that was closed does not write into state nobody reads. */
    dispose() {
        if (this.copiedTimeout) clearTimeout(this.copiedTimeout);
        this.copiedTimeout = null;
    }
}
