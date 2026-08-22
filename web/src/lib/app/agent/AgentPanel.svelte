<script lang="ts" module>
    /**
     * The panel's size once it stands open. Exported because the card animates up to it from
     * nothing, and the panel is held at it throughout so its content is revealed rather than
     * reflowed while that runs.
     *
     * Sized off the viewport rather than off the card: the card shrinks to what is in it, so a
     * percentage width would have nothing to resolve against. Three steps -- the whole screen on a
     * phone, full height and half the width on a tablet, and a window in the corner from a desktop
     * width on.
     *
     * `dvh`, not `vh` and not `svh`: on a phone `vh` is the viewport with the address bar out of
     * the way, so a panel that size runs under the bar, and `svh` is the viewport with the bar
     * shown, so it leaves a strip of page visible once the bar retracts. `dvh` is whatever is on
     * screen right now, which is what fullscreen means here. On a desktop all three are the same.
     */
    export const PANEL_SIZE = `
        h-[100dvh] w-dvw
        md:h-[calc(100dvh-3rem)] md:w-[50vw]
        xl:h-[36rem] xl:max-h-[75dvh] xl:w-[28rem]
    `;
</script>

<script lang="ts">
    // The open state of the card, and the shell the chat with the agent is to live in. What the
    // agent is doing sits in a card at the top of it, not in the content.
    import {XIcon} from "phosphor-svelte";
    import {Button} from "$lib/components/ui/button";
    import AgentImportCard from "./AgentImportCard.svelte";
    import type {AgentProcessStatus} from "$lib/repository/AgentRepository";

    let {status, onClose}: {status: AgentProcessStatus | null; onClose: () => void} = $props();
</script>

<div class="flex flex-col {PANEL_SIZE}">
    <header class="flex h-12 shrink-0 items-center justify-between ps-5 pe-2">
        <span class="text-sm font-medium">Overmail AI</span>
        <Button
                variant="ghost"
                size="icon-sm"
                class="rounded-full"
                aria-label="Schließen"
                onclick={onClose}
        >
            <XIcon class="size-4" />
        </Button>
    </header>

    <AgentImportCard {status} />

    <!-- The chat goes in here. -->
</div>
