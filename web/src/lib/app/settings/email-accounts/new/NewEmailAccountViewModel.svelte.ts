import type {
    FolderNode,
    ImapHostOutcome,
    ImapLoginOutcome,
    InboxSetupRepository,
} from "$lib/repository/InboxSetupRepository";

/**
 * How long the form stays quiet before it asks the server.
 *
 * Every test opens a real connection to somebody else's mail server, so this is not about saving
 * a round trip -- it is about not knocking on `imap.exampl`, `imap.example`, `imap.example.c` on
 * the way to `imap.example.com`. Credentials get longer than the host: a password is typed in one
 * go, and a wrong-looking attempt on the way through it is what gets an account locked out.
 */
const HOST_DEBOUNCE_MS = 500;
const LOGIN_DEBOUNCE_MS = 900;

/** What imap over tls runs on, and what the field starts on. */
export const DEFAULT_IMAP_PORT = 993;

/** The steps of "new inbox", in order. What the progress indicator walks through. */
export const SETUP_STEPS = ["server", "credentials", "folders"] as const;
export type SetupStep = (typeof SETUP_STEPS)[number];

/** Where the host check stands, for the host and port currently in the form. */
export type ImapServerTest =
    | {type: "idle"}
    | {type: "testing"}
    | {type: "reachable"; capabilities: string[]}
    | {type: "unreachable"; outcome: ImapHostOutcome}
    /** The test itself did not happen. Says nothing about the host. */
    | {type: "failed"};

/** Where the credentials check stands. */
export type ImapLoginTestState =
    | {type: "idle"}
    | {type: "testing"}
    | {type: "authenticated"}
    /** The server read them and said no, or the connection went away. */
    | {type: "rejected"; outcome: ImapLoginOutcome}
    /** The test itself did not happen. */
    | {type: "failed"};

/** How far the folder scan has got. */
export type FolderScanState =
    | {type: "idle"}
    /** Connected, waiting for the folder list. Nothing to show yet. */
    | {type: "listing"}
    /** The tree is up; [counted] of [total] folders have their numbers. */
    | {type: "counting"; counted: number; total: number}
    | {type: "done"}
    | {type: "failed"; outcome: string | null};

/**
 * How far back the assistant reads in a folder. Not what is imported -- every mail of an enabled
 * folder is; this is only how much of it the ai is put through, which is the part that costs.
 *
 * Per folder, because a Sent is not an INBOX.
 */
export type AiProcessingMode =
    /** Only what arrives from now on. */
    | {type: "new_only"}
    /** The newest [count] mails, and then whatever arrives. */
    | {type: "newest"; count: number}
    /** Everything sent on or after [date] (`yyyy-mm-dd`). */
    | {type: "since"; date: string};

/** One row of the folder table. */
export type FolderRow = {
    fullName: string;
    name: string;
    /** Nesting level, straight off the path: 0 is a root folder. What indents the row. */
    depth: number;
    /** The folder this one sits in, or null at the root. */
    parentFullName: string | null;
    /** `INBOX`, `SENT`, `SPAM`, `TRASH`, `DRAFTS`, or null. */
    specialType: string | null;
    /** Whether anything sits inside it -- only those get a collapse control. */
    hasChildren: boolean;
    /** Whether this folder is imported at all. */
    enabled: boolean;
    /** Null until counted, and after a folder that could not be read. */
    mailCount: number | null;
    oldestMailAt: string | null;
    /** Whether the numbers above are in, so a row can show a placeholder until they are. */
    counted: boolean;
    aiProcessing: AiProcessingMode;
};

/**
 * The "new inbox" form, over its three steps.
 *
 * Each step checks itself against the server while it is being filled in, and the step after it
 * only opens once that check came back good: a host that answers, then credentials it accepts,
 * then the folders behind them. Finding out at the end that the port was wrong is one round of
 * typing too late, and every check here costs a real connection to somebody's mail server, which
 * is what the debounces are about.
 *
 * Only ever one check is outstanding per step. Editing cancels what was scheduled *and* what is
 * already on its way, and invalidates the steps that were built on it -- a new host cannot keep
 * the login that was verified against the old one.
 */
export class NewEmailAccountViewModel {
    step: SetupStep = $state("server");

    host = $state("");
    port = $state(DEFAULT_IMAP_PORT);
    imapServerTest: ImapServerTest = $state({type: "idle"});

    username = $state("");
    password = $state("");
    imapLoginTest: ImapLoginTestState = $state({type: "idle"});

    folders: FolderRow[] = $state([]);
    folderScan: FolderScanState = $state({type: "idle"});

    /** Folders whose children are hidden. By full name, so it survives the rows being replaced. */
    collapsed: string[] = $state([]);

    #hostDebounce: ReturnType<typeof setTimeout> | null = null;
    #hostRunning: AbortController | null = null;
    #loginDebounce: ReturnType<typeof setTimeout> | null = null;
    #loginRunning: AbortController | null = null;
    #scanRunning: AbortController | null = null;

    /** What the debounce above is waiting to run, kept so [submit] can run it straight away. */
    #hostPending: (() => void) | null = null;
    #loginPending: (() => void) | null = null;

    constructor(private readonly inboxSetup: InboxSetupRepository) {}

    /** The host answered, so the credentials step has something to log in to. */
    canLeaveServerStep = $derived(this.imapServerTest.type === "reachable");

    /** The credentials work, so the folders behind them can be listed. */
    canLeaveCredentialsStep = $derived(this.imapLoginTest.type === "authenticated");

    /** Whether [step] can be opened at all -- what the progress indicator greys out. */
    canEnter(step: SetupStep): boolean {
        if (step === "server") return true;
        if (step === "credentials") return this.canLeaveServerStep;
        return this.canLeaveServerStep && this.canLeaveCredentialsStep;
    }

    /** The rows to draw: everything whose parents are all expanded. */
    visibleFolders = $derived(
        this.folders.filter((folder) => {
            let parent = folder.parentFullName;
            while (parent !== null) {
                if (this.collapsed.includes(parent)) return false;
                parent = this.folders.find((row) => row.fullName === parent)?.parentFullName ?? null;
            }
            return true;
        }),
    );

    setHost(value: string) {
        this.host = value;
        this.#invalidateLogin();
        this.#scheduleHostTest();
    }

    setPort(value: number) {
        this.port = value;
        this.#invalidateLogin();
        this.#scheduleHostTest();
    }

    setUsername(value: string) {
        this.username = value;
        this.#invalidateFolders();
        this.#scheduleLoginTest();
    }

    setPassword(value: string) {
        this.password = value;
        this.#invalidateFolders();
        this.#scheduleLoginTest();
    }

    /** Moves to [step], if it is open. The folder scan starts on arriving there, not before. */
    goTo(step: SetupStep) {
        if (!this.canEnter(step)) return;
        this.step = step;
        if (step === "folders" && this.folderScan.type === "idle") void this.#scanFolders();
    }

    /** The "next" button: one step along from where the form is. */
    goToNextStep() {
        const next = SETUP_STEPS[SETUP_STEPS.indexOf(this.step) + 1];
        if (next) this.goTo(next);
    }

    /**
     * What the Enter key does: whatever the primary button would.
     *
     * And when that button is not ready yet, the thing that makes it ready sooner -- a check
     * still sitting out its debounce runs now. Enter right after typing a host would otherwise do
     * nothing at all for half a second, which reads as a key that only sometimes works. It never
     * navigates on its own once the answer arrives: that would move the form under a user who has
     * already started typing somewhere else.
     */
    submit() {
        const next = SETUP_STEPS[SETUP_STEPS.indexOf(this.step) + 1];
        if (!next) return;

        if (this.canEnter(next)) {
            this.goTo(next);
            return;
        }

        if (this.step === "server") this.#fireHostTestNow();
        else this.#fireLoginTestNow();
    }

    /**
     * Runs the scheduled host check now, if one is waiting.
     *
     * Per check rather than "whatever the current step is waiting on": the two debounces run
     * independently of where the form is standing -- a login check is scheduled the moment both
     * credential fields are filled, which can happen while the server step is still showing.
     */
    #fireHostTestNow() {
        const pending = this.#hostPending;
        if (this.#hostDebounce !== null) clearTimeout(this.#hostDebounce);
        this.#hostDebounce = null;
        this.#hostPending = null;
        pending?.();
    }

    #fireLoginTestNow() {
        const pending = this.#loginPending;
        if (this.#loginDebounce !== null) clearTimeout(this.#loginDebounce);
        this.#loginDebounce = null;
        this.#loginPending = null;
        pending?.();
    }

    toggleFolder(fullName: string) {
        const folder = this.folders.find((row) => row.fullName === fullName);
        if (folder) folder.enabled = !folder.enabled;
    }

    toggleCollapsed(fullName: string) {
        this.collapsed = this.collapsed.includes(fullName)
            ? this.collapsed.filter((name) => name !== fullName)
            : [...this.collapsed, fullName];
    }

    setAiProcessing(fullName: string, mode: AiProcessingMode) {
        const folder = this.folders.find((row) => row.fullName === fullName);
        if (folder) folder.aiProcessing = mode;
    }

    /** Called when the dialog closes, so no check and no scan outlives it. */
    dispose() {
        this.#cancelHostTest();
        this.#cancelLoginTest();
        this.#scanRunning?.abort();
        this.#scanRunning = null;
        // A check that was cut off never reached a verdict; left as `testing` it would greet the
        // next opening with a stuck spinner.
        if (this.imapServerTest.type === "testing") this.imapServerTest = {type: "idle"};
        if (this.imapLoginTest.type === "testing") this.imapLoginTest = {type: "idle"};
    }

    /** A new host is not the one the login was verified against, so that verdict is gone. */
    #invalidateLogin() {
        this.#cancelLoginTest();
        this.imapLoginTest = {type: "idle"};
        this.#invalidateFolders();
    }

    #invalidateFolders() {
        this.#scanRunning?.abort();
        this.#scanRunning = null;
        this.folders = [];
        this.collapsed = [];
        this.folderScan = {type: "idle"};
    }

    #cancelHostTest() {
        if (this.#hostDebounce !== null) clearTimeout(this.#hostDebounce);
        this.#hostDebounce = null;
        this.#hostPending = null;
        this.#hostRunning?.abort();
        this.#hostRunning = null;
    }

    #cancelLoginTest() {
        if (this.#loginDebounce !== null) clearTimeout(this.#loginDebounce);
        this.#loginDebounce = null;
        this.#loginPending = null;
        this.#loginRunning?.abort();
        this.#loginRunning = null;
    }

    #scheduleHostTest() {
        this.#cancelHostTest();

        const host = this.host.trim();
        const port = this.port;
        // The server rejects these, and there is nothing to say about a form this empty anyway.
        if (host === "" || !Number.isInteger(port) || port < 1 || port > 65535) {
            this.imapServerTest = {type: "idle"};
            return;
        }

        this.#hostPending = () => void this.#testImapServer(host, port);
        this.#hostDebounce = setTimeout(() => this.#fireHostTestNow(), HOST_DEBOUNCE_MS);
    }

    async #testImapServer(host: string, port: number) {
        const running = new AbortController();
        this.#hostRunning = running;
        this.imapServerTest = {type: "testing"};

        try {
            const result = await this.inboxSetup.testImapHost(host, port, running.signal);
            // A newer test took over while this one was on its way; its answer is the current one.
            if (running.signal.aborted) return;
            this.imapServerTest = result.reachable
                ? {type: "reachable", capabilities: result.capabilities}
                : {type: "unreachable", outcome: result.outcome};
        } catch {
            if (running.signal.aborted) return;
            this.imapServerTest = {type: "failed"};
        } finally {
            if (this.#hostRunning === running) this.#hostRunning = null;
        }
    }

    #scheduleLoginTest() {
        this.#cancelLoginTest();

        const username = this.username.trim();
        const password = this.password;
        if (username === "" || password === "") {
            this.imapLoginTest = {type: "idle"};
            return;
        }

        this.#loginPending = () => void this.#testImapLogin(this.host.trim(), this.port, username, password);
        this.#loginDebounce = setTimeout(() => this.#fireLoginTestNow(), LOGIN_DEBOUNCE_MS);
    }

    async #testImapLogin(host: string, port: number, username: string, password: string) {
        const running = new AbortController();
        this.#loginRunning = running;
        this.imapLoginTest = {type: "testing"};

        try {
            const result = await this.inboxSetup.testImapLogin(host, port, username, password, running.signal);
            if (running.signal.aborted) return;
            this.imapLoginTest = result.authenticated
                ? {type: "authenticated"}
                : {type: "rejected", outcome: result.outcome};
        } catch {
            if (running.signal.aborted) return;
            this.imapLoginTest = {type: "failed"};
        } finally {
            if (this.#loginRunning === running) this.#loginRunning = null;
        }
    }

    /**
     * Reads the folders, filling the table in as they arrive.
     *
     * The tree lands in one go and the numbers trickle in after it, which is the whole point of
     * the stream: on a real mailbox the counting is seconds of work, and a user watching rows
     * fill in can see that something is being looked through.
     */
    async #scanFolders() {
        const running = new AbortController();
        this.#scanRunning = running;
        this.folders = [];
        this.folderScan = {type: "listing"};

        try {
            for await (const event of this.inboxSetup.streamFolders(
                this.host.trim(),
                this.port,
                this.username.trim(),
                this.password,
                running.signal,
            )) {
                if (running.signal.aborted) return;

                switch (event.type) {
                    case "folders":
                        this.folders = toRows(event.folders);
                        this.folderScan = {type: "counting", counted: 0, total: event.folders.length};
                        break;

                    case "stats": {
                        const folder = this.folders.find((row) => row.fullName === event.stats.fullName);
                        if (folder) {
                            folder.mailCount = event.stats.mailCount;
                            folder.oldestMailAt = event.stats.oldestMailAt;
                            folder.counted = true;
                        }
                        if (this.folderScan.type === "counting") {
                            this.folderScan = {
                                type: "counting",
                                counted: this.folderScan.counted + 1,
                                total: this.folderScan.total,
                            };
                        }
                        break;
                    }

                    case "done":
                        this.folderScan = {type: "done"};
                        break;

                    case "error":
                        this.folderScan = {type: "failed", outcome: event.outcome};
                        break;
                }
            }

            // The stream ended without saying so -- treat what did arrive as all there is.
            if (this.folderScan.type === "listing" || this.folderScan.type === "counting") {
                this.folderScan = {type: "done"};
            }
        } catch {
            if (running.signal.aborted) return;
            this.folderScan = {type: "failed", outcome: null};
        } finally {
            if (this.#scanRunning === running) this.#scanRunning = null;
        }
    }
}

/**
 * The folder list as the table holds it: nesting resolved, and the INBOX switched on.
 *
 * The INBOX by default and nothing else: it is the folder everybody means by "my mail", while a
 * Trash or a Spam pulled in by default would import exactly what the user threw away.
 */
function toRows(folders: FolderNode[]): FolderRow[] {
    const parentOf = (folder: FolderNode) =>
        folder.path.length > 1 ? folder.path.slice(0, -1).join(folder.delimiter) : null;
    const parents = new Set(folders.map(parentOf).filter((name): name is string => name !== null));

    return folders.map((folder) => ({
        fullName: folder.fullName,
        name: folder.name,
        depth: folder.path.length - 1,
        parentFullName: parentOf(folder),
        specialType: folder.specialType,
        hasChildren: parents.has(folder.fullName),
        enabled: folder.specialType === "INBOX",
        mailCount: null,
        oldestMailAt: null,
        counted: false,
        aiProcessing: {type: "new_only"},
    }));
}
