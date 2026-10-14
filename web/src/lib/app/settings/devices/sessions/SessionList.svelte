<script lang="ts">
    import {_, locale} from "svelte-i18n";
    import {BellRingingIcon, CheckIcon, XIcon} from "phosphor-svelte";
    import {Button} from "$lib/components/ui/button";
    import RelativeTime from "$lib/components/time/RelativeTime.svelte";
    import type {SessionClient, UserSession} from "$lib/repository/SessionsRepository";

    let {sessions, revoking, onrevoke, sendingTestPush, sentTestPush, ontestpush}: {
        sessions: UserSession[];
        /** Ids being signed out right now; their button is off meanwhile. */
        revoking: Set<string>;
        onrevoke: (session: UserSession) => void;
        /** Ids a test push is being sent to right now; their button is off meanwhile. */
        sendingTestPush: Set<string>;
        /** Ids whose test push just went out; their button shows a check for a moment. */
        sentTestPush: Set<string>;
        ontestpush: (session: UserSession) => void;
    } = $props();

    /** What the session is, in a few words: the browser, or the phone for the app. */
    function title(client: SessionClient): string {
        switch (client.type) {
            case "web":
                return client.browser;
            case "android":
                return `${client.manufacturer} ${client.device}`;
            case "ios":
                return client.device;
        }
    }

    /** Where it runs: the operating system, and the device for a browser. */
    function subtitle(client: SessionClient): string {
        return client.type === "web" ? `${client.device} · ${client.os}` : client.os;
    }
</script>

{#if sessions.length === 0}
    <span class="text-sm text-muted-foreground">{$_("settings.devices.sessions.empty")}</span>
{:else}
    <ul class="flex flex-col divide-y border rounded-lg">
        {#each sessions as session (session.id)}
            <li class="flex flex-row gap-2 items-center justify-between px-3 py-2">
                <div class="flex flex-col min-w-0">
                    <span class="text-sm truncate">{title(session.client)}</span>
                    <span class="text-xs text-muted-foreground truncate">
                        {subtitle(session.client)} ·
                        <RelativeTime
                                date={session.issuedAt}
                                title={session.issuedAt.toLocaleString($locale ?? undefined, {dateStyle: "medium", timeStyle: "short"})}
                        />
                        {#if session.isCurrentSession}
                            · <span class="text-primary">{$_("settings.devices.sessions.current")}</span>
                        {/if}
                    </span>
                </div>
                <div class="flex flex-row gap-1 shrink-0">
                    {#if session.pushNotificationState === "setup"}
                        <Button
                                variant="ghost"
                                size="icon-sm"
                                aria-label={$_("settings.devices.sessions.testPush")}
                                title={$_("settings.devices.sessions.testPush")}
                                disabled={sendingTestPush.has(session.id)}
                                onclick={() => ontestpush(session)}
                        >
                            {#if sentTestPush.has(session.id)}
                                <CheckIcon />
                            {:else}
                                <BellRingingIcon />
                            {/if}
                        </Button>
                    {/if}
                    <Button
                            variant="ghost"
                            size="icon-sm"
                            aria-label={$_("settings.devices.sessions.revoke")}
                            title={$_("settings.devices.sessions.revoke")}
                            disabled={revoking.has(session.id)}
                            onclick={() => onrevoke(session)}
                    >
                        <XIcon />
                    </Button>
                </div>
            </li>
        {/each}
    </ul>
{/if}
