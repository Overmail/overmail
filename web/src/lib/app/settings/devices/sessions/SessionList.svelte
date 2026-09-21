<script lang="ts">
    import {_, locale} from "svelte-i18n";
    import type {SessionClient, UserSession} from "$lib/repository/SessionsRepository";

    let {sessions}: {sessions: UserSession[]} = $props();

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
            <li class="flex flex-row gap-4 items-center justify-between p-4">
                <div class="flex flex-col min-w-0">
                    <span class="truncate">{title(session.client)}</span>
                    <span class="text-sm text-muted-foreground truncate">{subtitle(session.client)}</span>
                </div>
                <div class="flex flex-col items-end shrink-0 text-sm">
                    {#if session.isCurrentSession}
                        <span class="text-primary">{$_("settings.devices.sessions.current")}</span>
                    {/if}
                    <span class="text-muted-foreground">
                        {$_("settings.devices.sessions.issuedAt", {
                            values: {date: session.issuedAt.toLocaleString($locale ?? undefined, {dateStyle: "medium", timeStyle: "short"})},
                        })}
                    </span>
                </div>
            </li>
        {/each}
    </ul>
{/if}
