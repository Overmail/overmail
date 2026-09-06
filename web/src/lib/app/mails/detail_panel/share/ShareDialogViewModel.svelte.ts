import type {Share, ShareDraft, ShareRepository} from "$lib/repository/ShareRepository";

/** The shortest password the server takes; see `shareInput.kt`. */
export const MIN_PASSWORD_LENGTH = 4;

/** How long a new link lasts by default -- a week, so a link that is forgotten stops on its own. */
const DEFAULT_EXPIRY: ShareExpiry = "7d";

/** How long a link lasts, as the dialog offers it. `custom` reads [ShareDialogViewModel.expiresOn]. */
export type ShareExpiry = "1d" | "7d" | "30d" | "never" | "custom";

export const SHARE_EXPIRIES: ShareExpiry[] = ["1d", "7d", "30d", "never", "custom"];

const DAY_SECONDS = 24 * 60 * 60;

/** Where the list of links stands. */
export type ListState = {type: "loading"} | {type: "ready"} | {type: "failed"};

/** Where saving stands. A failure leaves the form standing with the reason on it. */
export type SaveState = {type: "idle"} | {type: "saving"} | {type: "failed"};

/**
 * The share dialog: the links one mail was handed out under, and the form that writes them.
 *
 * A view model rather than state in the dialog, because the parts worth getting right are not
 * about markup -- what an expiry means as a timestamp, and what an untouched password field means
 * on an edit (leave it) as against on a create (no password at all). Both are testable without a
 * browser, and both are wrong in ways nobody would see on screen.
 *
 * [now] is handed in so a test can say what "in seven days" is; the app leaves it at the clock.
 */
export class ShareDialogViewModel {
    shares: Share[] = $state([]);
    listState: ListState = $state({type: "loading"});
    saveState: SaveState = $state({type: "idle"});

    /** The share being edited, or null while the form makes a new one. */
    editing: Share | null = $state(null);

    shareName = $state("");
    expiry: ShareExpiry = $state(DEFAULT_EXPIRY);
    /** `yyyy-mm-dd`, read only where [expiry] is `custom`. */
    expiresOn = $state("");
    password = $state("");
    /**
     * Takes the password off the share being edited.
     *
     * Only means anything while editing one that has a password: an empty field there means "as
     * it was", so saying "no password from now on" needs a word of its own.
     */
    removePassword = $state(false);
    allowMetadataWithoutPassword = $state(true);
    includeLabels = $state(true);

    /** The share whose link was last copied, so the button can say it worked. */
    copied: string | null = $state(null);

    /** The share the delete confirmation is open on. */
    deleting: Share | null = $state(null);
    deleteFailed = $state(false);

    constructor(
        private readonly emailId: string,
        private readonly shares_: ShareRepository,
        private readonly now: () => number = () => Date.now(),
    ) {}

    get saving(): boolean {
        return this.saveState.type === "saving";
    }

    /** Whether the form is editing a link that is already out, rather than making one. */
    get isEditing(): boolean {
        return this.editing !== null;
    }

    /** Whether the field is asking for a password that would replace one already set. */
    get replacingPassword(): boolean {
        return this.editing?.hasPassword === true;
    }

    /**
     * Why the form cannot be saved, or null.
     *
     * A key, not a sentence: the dialog words it. Both are things the server refuses as well, so
     * this is about saying it before the request rather than after.
     */
    get problem(): "password-too-short" | "expiry-missing" | "expiry-past" | null {
        if (this.password.length > 0 && this.password.length < MIN_PASSWORD_LENGTH) return "password-too-short";
        if (this.expiry !== "custom") return null;

        const day = endOfDay(this.expiresOn);
        if (day === null) return "expiry-missing";
        if (day <= Math.floor(this.now() / 1000)) return "expiry-past";
        return null;
    }

    get canSubmit(): boolean {
        return !this.saving && this.problem === null;
    }

    /** When the link the form describes stops working, in whole seconds; null never runs out. */
    get validUntil(): number | null {
        const seconds = Math.floor(this.now() / 1000);
        switch (this.expiry) {
            case "1d":
                return seconds + DAY_SECONDS;
            case "7d":
                return seconds + 7 * DAY_SECONDS;
            case "30d":
                return seconds + 30 * DAY_SECONDS;
            case "never":
                return null;
            case "custom":
                return endOfDay(this.expiresOn);
        }
    }

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

    /** Empties the form, so the next link is a new one and not the one that was just edited. */
    startCreate() {
        this.editing = null;
        this.shareName = "";
        this.expiry = DEFAULT_EXPIRY;
        this.expiresOn = "";
        this.password = "";
        this.removePassword = false;
        this.allowMetadataWithoutPassword = true;
        this.includeLabels = true;
        this.saveState = {type: "idle"};
    }

    /**
     * Fills the form with [share].
     *
     * The password field stays empty: the server never handed it out, and an edit that leaves it
     * alone keeps what is there -- which is what [replacingPassword] tells the field to say.
     */
    startEdit(share: Share) {
        this.editing = share;
        this.shareName = share.shareName ?? "";
        this.expiry = share.validUntil === null ? "never" : "custom";
        this.expiresOn = share.validUntil === null ? "" : isoDay(share.validUntil);
        this.password = "";
        this.removePassword = false;
        this.allowMetadataWithoutPassword = share.allowMetadataWithoutPassword;
        this.includeLabels = share.includeLabels;
        this.saveState = {type: "idle"};
    }

    /**
     * Writes the link, and leaves the dialog on the fresh list.
     *
     * A create empties the form again, so the copy button below the new link is the next thing to
     * reach for; an edit closes back into the list it came from.
     */
    async submit(): Promise<Share | null> {
        if (!this.canSubmit) return null;

        const draft: ShareDraft = {
            shareName: this.shareName.trim().length > 0 ? this.shareName.trim() : null,
            includeLabels: this.includeLabels,
            validUntil: this.validUntil,
            password: this.password.length > 0 ? this.password : null,
            // Only where nothing was typed: a new password replaces the old one anyway, and
            // asking for both would be two answers to one question.
            removePassword: this.password.length === 0 && this.removePassword,
            allowMetadataWithoutPassword: this.allowMetadataWithoutPassword,
        };

        this.saveState = {type: "saving"};
        try {
            const target = this.editing;
            const share = target
                ? await this.shares_.update(this.emailId, target.id, draft)
                : await this.shares_.create(this.emailId, draft);

            this.saveState = {type: "idle"};
            this.startCreate();
            await this.load();
            return share;
        } catch {
            this.saveState = {type: "failed"};
            return null;
        }
    }

    /** Puts the link on the clipboard, and remembers which one, so the button can say so. */
    async copy(share: Share, url: string): Promise<boolean> {
        try {
            await navigator.clipboard.writeText(url);
            this.copied = share.id;
            return true;
        } catch {
            this.copied = null;
            return false;
        }
    }

    /** Opens the confirmation. A link is handed out, so taking it back is worth one question. */
    askToDelete(share: Share) {
        this.deleting = share;
        this.deleteFailed = false;
    }

    /** Takes the link back for good. The form falls back to a new one if it was editing this share. */
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
        if (this.editing?.id === target.id) this.startCreate();
        if (this.copied === target.id) this.copied = null;
        await this.load();
        return true;
    }
}

/** `yyyy-mm-dd` as the last second of that day, locally. Null for a day that is not one. */
function endOfDay(day: string): number | null {
    const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(day);
    if (!match) return null;

    // Local, not UTC: "until the 14th" is the reader's 14th, and their evening is what they mean.
    const date = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]), 23, 59, 59);
    if (Number.isNaN(date.getTime())) return null;

    return Math.floor(date.getTime() / 1000);
}

/** A timestamp as the `yyyy-mm-dd` a date field takes, in the reader's own timezone. */
function isoDay(seconds: number): string {
    const date = new Date(seconds * 1000);
    const month = `${date.getMonth() + 1}`.padStart(2, "0");
    const day = `${date.getDate()}`.padStart(2, "0");

    return `${date.getFullYear()}-${month}-${day}`;
}
