import {
    ShareExpiredError,
    ShareNotFoundError,
    WrongSharePasswordError,
    type SharedEmail,
    type SharedEmailRepository,
} from "$lib/repository/SharedEmailRepository";

/**
 * What the page is showing.
 *
 * `expired` and `missing` are kept apart because they are different news: one link was real and
 * ran out, and the reader can ask for another; the other never was.
 */
export type ShareState =
    | {type: "loading"}
    | {type: "shown"; shared: SharedEmail}
    | {type: "expired"}
    | {type: "missing"}
    | {type: "failed"};

/** Where typing the password stands. `wrong` is the one failure the field points at. */
export type UnlockState = {type: "idle"} | {type: "unlocking"} | {type: "wrong"} | {type: "failed"};

/**
 * The page behind a share link.
 *
 * There is no session here and nothing is polled: a shared mail does not change under the reader,
 * so the page reads it once and again when the password is typed. What the reader typed stays in
 * this object and is sent with the request that reads the mail -- it is never stored anywhere,
 * which is also why reloading the page asks again.
 */
export class SharePageViewModel {
    state: ShareState = $state({type: "loading"});
    unlockState: UnlockState = $state({type: "idle"});

    /** What is in the password field. */
    password = $state("");

    constructor(
        private readonly shareId: string,
        private readonly shares: SharedEmailRepository,
    ) {}

    get unlocking(): boolean {
        return this.unlockState.type === "unlocking";
    }

    /** The mail as far as it is out, or null while the page has nothing to show. */
    get shared(): SharedEmail | null {
        return this.state.type === "shown" ? this.state.shared : null;
    }

    /** Whether the page is asking for a password rather than showing the mail. */
    get locked(): boolean {
        return this.shared?.needsPassword === true;
    }

    /** Nothing to send until something is typed, and not twice while the first is in flight. */
    get canUnlock(): boolean {
        return !this.unlocking && this.password.length > 0;
    }

    /** Reads what the link shows without a password. The page opens on this. */
    async load(signal?: AbortSignal): Promise<void> {
        this.state = {type: "loading"};
        try {
            this.state = {type: "shown", shared: await this.shares.read(this.shareId, signal)};
        } catch (error) {
            if (signal?.aborted) return;
            this.state = toFailure(error);
        }
    }

    /**
     * Sends the password and, where it fits, replaces what is shown with the whole mail.
     *
     * A wrong one leaves the page as it is with the field marked: the reader mistyped, and
     * everything they could see before is still theirs to see.
     */
    async unlock(): Promise<boolean> {
        if (!this.canUnlock) return false;

        this.unlockState = {type: "unlocking"};
        try {
            const shared = await this.shares.open(this.shareId, this.password);
            this.state = {type: "shown", shared};
            this.unlockState = {type: "idle"};
            // Not kept: the mail is here, and there is nothing left to send it with.
            this.password = "";
            return true;
        } catch (error) {
            if (error instanceof WrongSharePasswordError) {
                this.unlockState = {type: "wrong"};
                return false;
            }

            // A link that ran out while the password was being typed is news about the link, not
            // about the password, so the whole page says so.
            if (error instanceof ShareExpiredError || error instanceof ShareNotFoundError) {
                this.state = toFailure(error);
                this.unlockState = {type: "idle"};
                return false;
            }

            this.unlockState = {type: "failed"};
            return false;
        }
    }

    /** Typing is the answer to "that is not the password", so the message goes with it. */
    setPassword(password: string) {
        this.password = password;
        if (this.unlockState.type === "wrong") this.unlockState = {type: "idle"};
    }
}

function toFailure(error: unknown): ShareState {
    if (error instanceof ShareExpiredError) return {type: "expired"};
    if (error instanceof ShareNotFoundError) return {type: "missing"};

    return {type: "failed"};
}
