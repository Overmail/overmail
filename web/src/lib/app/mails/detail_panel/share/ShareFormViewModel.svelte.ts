import type {Share, ShareDraft} from "$lib/repository/ShareRepository";

/** The shortest password the server takes; see `shareInput.kt`. */
export const MIN_PASSWORD_LENGTH = 4;

/** How long a new link lasts by default -- a week, so a link that is forgotten stops on its own. */
const DEFAULT_EXPIRY: ShareExpiry = "7d";

/** How long a link lasts, as the dialog offers it. `custom` reads [ShareFormViewModel.expiresOn]. */
export type ShareExpiry = "1d" | "7d" | "30d" | "never" | "custom";

export const SHARE_EXPIRIES: ShareExpiry[] = ["1d", "7d", "30d", "never", "custom"];

const DAY_SECONDS = 24 * 60 * 60;

/** Where saving stands. A failure leaves the form standing with the reason on it. */
export type SaveState = {type: "idle"} | {type: "saving"} | {type: "failed"};

/**
 * The share form, behind both the create dialog and the edit one.
 *
 * A view model rather than state in the dialog, like the knowledge form: the parts worth getting
 * right are not about markup -- what an expiry means as a timestamp, and what an untouched
 * password field means on an edit (leave it) as against on a create (no password at all). Both
 * are testable without a browser, and both are wrong in ways nobody would see on screen.
 *
 * What differs between the two dialogs is only where [submit] writes to, which is the `save`
 * handed in here -- `create` for a new link, `update` for one that is already out. [now] is
 * handed in so a test can say what "in seven days" is; the app leaves it at the clock.
 */
export class ShareFormViewModel {
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

    saveState: SaveState = $state({type: "idle"});

    /** The share this form was opened on, or null while it makes a new one. */
    private editing: Share | null = $state(null);

    constructor(
        private readonly save: (draft: ShareDraft) => Promise<Share>,
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

    /**
     * Fills the form with [share], or empties it for a new link.
     *
     * The password field stays empty either way: the server never handed the old one out, and an
     * edit that leaves it alone keeps what is there -- which is what [replacingPassword] tells
     * the field to say.
     */
    reset(share: Share | null = null) {
        // Filled in locally and read back from there, never through `this.editing`: the edit
        // dialog calls this from an effect that reads what to open on, and an effect that reads a
        // piece of state it just wrote depends on itself -- which Svelte ends with
        // `effect_update_depth_exceeded`, taking the screen it was rendering with it.
        this.editing = share;
        this.shareName = share?.shareName ?? "";
        this.expiry = share === null ? DEFAULT_EXPIRY : share.validUntil === null ? "never" : "custom";
        this.expiresOn = share?.validUntil == null ? "" : isoDay(share.validUntil);
        this.password = "";
        this.removePassword = false;
        this.allowMetadataWithoutPassword = share?.allowMetadataWithoutPassword ?? true;
        this.includeLabels = share?.includeLabels ?? true;
        this.saveState = {type: "idle"};
    }

    /** Writes the link, or leaves the form standing with the reason on it. */
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
            const share = await this.save(draft);
            this.saveState = {type: "idle"};
            return share;
        } catch {
            this.saveState = {type: "failed"};
            return null;
        }
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
