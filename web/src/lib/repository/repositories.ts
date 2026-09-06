import {getContext, setContext} from "svelte";
import {AuthRepository} from "$lib/repository/AuthRepository";
import {CurrentUserRepository} from "$lib/repository/CurrentUserRepository";
import {EmailBodyRepository} from "$lib/repository/EmailBodyRepository";
import {InboxRepository} from "$lib/repository/InboxRepository";
import {KnowledgeRepository} from "$lib/repository/KnowledgeRepository";
import {InboxSetupRepository} from "$lib/repository/InboxSetupRepository";
import {ShareRepository} from "$lib/repository/ShareRepository";
import {SharedEmailRepository} from "$lib/repository/SharedEmailRepository";
import {ChatHistoryRepository} from "$lib/app/ai/ChatHistoryRepository";
import {EmailRepository} from "$lib/repository/EmailRepository.svelte";
import {HomeScreenRepository} from "$lib/repository/HomeScreenRepository.svelte";
import {
    createEmailRepository,
    createLabelRepository,
    createSenderRepository,
    type EntityRepository,
} from "$lib/app/entities/EntityRepository.svelte";
import type {CachedEmail, CachedLabel, CachedSender} from "$lib/app/entities/cache";

/**
 * Every repository the app talks to, in one place.
 *
 * A repository is what owns a piece of server state -- what it caches and when it re-fetches is
 * its business, and there is only ever one of each, so two components asking for the same thing
 * share the answer. Nothing else may construct one: a second instance is a second cache, and
 * then which one is current depends on who asked last.
 */
export type Repositories = {
    auth: AuthRepository;
    /** Mail metadata over the content socket -- the live one. See also [emails]. */
    mails: EmailRepository;
    currentUser: CurrentUserRepository;
    emailBody: EmailBodyRepository;
    /** The mailboxes that are connected, as the settings screen lists them. */
    inboxes: InboxRepository;
    /** The checks the "new inbox" dialog runs while the form is being filled in. */
    inboxSetup: InboxSetupRepository;
    /** What the assistant knows about the user, as the settings screen lists it. */
    knowledge: KnowledgeRepository;
    /** The links one mail was handed out under, as the share dialog reads and edits them. */
    shares: ShareRepository;
    /** A shared mail as the page behind a link reads it -- the one repository with no session. */
    sharedEmail: SharedEmailRepository;
    home: HomeScreenRepository;
    chatHistory: ChatHistoryRepository;
    emails: EntityRepository<CachedEmail>;
    labels: EntityRepository<CachedLabel>;
    senders: EntityRepository<CachedSender>;
};

/** One builder per key, so nothing is constructed before somebody asks for it. */
const factories: {[K in keyof Repositories]: () => Repositories[K]} = {
    auth: () => new AuthRepository(),
    mails: () => new EmailRepository(),
    currentUser: () => new CurrentUserRepository(),
    emailBody: () => new EmailBodyRepository(),
    inboxes: () => new InboxRepository(),
    inboxSetup: () => new InboxSetupRepository(),
    knowledge: () => new KnowledgeRepository(),
    shares: () => new ShareRepository(),
    sharedEmail: () => new SharedEmailRepository(),
    chatHistory: () => new ChatHistoryRepository(),
    home: () => new HomeScreenRepository(),
    emails: createEmailRepository,
    labels: createLabelRepository,
    senders: createSenderRepository,
};

/**
 * A fresh set. Every repository is built on first read and then kept, so importing this module
 * constructs nothing -- a repository that opens a socket or a cache stays out of the server
 * renderer and out of a test that never touches it.
 *
 * [overrides] is what a test replaces a single repository with; the app itself uses
 * [repositories].
 */
export function createRepositories(overrides: Partial<Repositories> = {}): Repositories {
    const built = new Map<keyof Repositories, unknown>();
    const container = {} as Repositories;

    for (const key of Object.keys(factories) as (keyof Repositories)[]) {
        Object.defineProperty(container, key, {
            enumerable: true,
            get() {
                const override = overrides[key];
                if (override !== undefined) return override;
                if (!built.has(key)) built.set(key, factories[key]());
                return built.get(key);
            },
        });
    }

    return container;
}

/** The set this app runs on. Reach it through [useRepositories], not through this. */
export const repositories = createRepositories();

const key = Symbol("repositories");

/** Called by the root layout, once, so everything below it resolves to the same set. */
export function provideRepositories(container: Repositories = repositories): Repositories {
    setContext(key, container);
    return container;
}

/**
 * The repositories for this component. Setup only -- it reads context, like any other
 * `getContext` caller.
 *
 * Falls back to [repositories] where nothing was provided, so a component rendered on its own in
 * a test still works; wrap it in a [provideRepositories] to hand it stubs instead.
 */
export function useRepositories(): Repositories {
    return getContext<Repositories | undefined>(key) ?? repositories;
}
