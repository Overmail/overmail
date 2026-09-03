import {getContext, setContext} from "svelte";
import {AuthRepository} from "$lib/repository/AuthRepository";
import {CurrentUserRepository} from "$lib/repository/CurrentUserRepository";
import {EmailBodyRepository} from "$lib/repository/EmailBodyRepository";
import {ChatHistoryRepository} from "$lib/app/ai/ChatHistoryRepository";
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
    currentUser: CurrentUserRepository;
    emailBody: EmailBodyRepository;
    chatHistory: ChatHistoryRepository;
    emails: EntityRepository<CachedEmail>;
    labels: EntityRepository<CachedLabel>;
    senders: EntityRepository<CachedSender>;
};

/**
 * A fresh set. [overrides] is what a test replaces a single repository with; the app itself uses
 * [repositories].
 */
export function createRepositories(overrides: Partial<Repositories> = {}): Repositories {
    return {
        auth: new AuthRepository(),
        currentUser: new CurrentUserRepository(),
        emailBody: new EmailBodyRepository(),
        chatHistory: new ChatHistoryRepository(),
        emails: createEmailRepository(),
        labels: createLabelRepository(),
        senders: createSenderRepository(),
        ...overrides,
    };
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
