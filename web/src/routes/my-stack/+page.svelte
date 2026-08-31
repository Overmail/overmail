<script lang="ts">
    import * as Sidebar from "$lib/components/ui/sidebar";
    import {Separator} from "$lib/components/ui/separator";
    import KeyCap from "$lib/components/key/KeyCap.svelte";
    import EmailStack from "$lib/app/my-stack/EmailStack.svelte";
    import { ArchiveIcon, ArrowDownIcon, ArrowUpIcon, ChatsCircleIcon, TagIcon, WarningIcon } from "phosphor-svelte";
    import {onMount} from "svelte";
    import {EmailStackViewModel} from "$lib/app/my-stack/EmailStackViewModel.svelte";
    import {createHotkey} from "@tanstack/svelte-hotkeys";
    import OvermailAiPopover from "$lib/app/ai/popover/OvermailAiPopover.svelte";

    let viewModel: EmailStackViewModel = new EmailStackViewModel();

    onMount(() => {
        return () => {
            viewModel.dispose();
        }
    })

    const stackHasEmails = $derived(viewModel.emails.length > 0);

    createHotkey(
        "Space",
        () => {
            viewModel.onKeepEmail()
        },
        () => ({enabled: stackHasEmails})
    )

    createHotkey(
        "Backspace",
        () => {
            viewModel.onPreviousEmail()
        },
        () => ({enabled: stackHasEmails})
    )

    createHotkey(
        "A",
        () => {
            viewModel.onArchiveOrUnarchiveEmail()
        },
        () => ({enabled: stackHasEmails})
    )

    let showOvermailAI = $state(true);
</script>

<header
        class="flex shrink-0 items-center gap-2 border-b transition-[width,height] ease-linear h-12"
>
    <div class="flex w-full items-center gap-1 px-4 lg:gap-2 lg:px-6">
        <Sidebar.Trigger class="-ms-1" />
        <Separator orientation="vertical" class="mx-2 data-[orientation=vertical]:h-4" />
        <h1 class="text-base font-medium">Stack</h1>
        <div class="ms-auto flex items-center gap-2">
            <OvermailAiPopover bind:open={showOvermailAI} />
        </div>
    </div>
</header>

<!-- flex-1 down the whole chain: <main> sits in the sidebar inset's flex column, and only a
     flex-1 item picks up the height left over by the header. A percentage height has nothing to
     resolve against here, since none of these boxes has a height of its own. -->
<main class="flex flex-1 flex-col">
    <div class="relative flex flex-1">
        <div class="flex flex-1 overflow-hidden relative">
            <!-- Down to the bottom edge, so a card runs under the shortcut bar instead of being
                 cut off above it. Keeping the last lines reachable is the scroll box's job in
                 EmailStack, which pads its scroll range by the bar's height. -->
            <div class="absolute inset-0 flex justify-center pt-8">
                <EmailStack
                        emails={viewModel.emails ?? []}
                        onRequestReclassify={(email) => viewModel.onRequestEmailClassification(email.id)}
                        currentEmailId={viewModel.currentEmailId}
                        class="h-full"
                />
            </div>
        </div>


        <div class="absolute bottom-0 left-0 z-50 w-full h-32 bg-linear-to-b from-transparent to-background">
            <!-- Progressive blur: each layer blurs the backdrop of the ones above it and is masked
                 to start further down, so the blur ramps up towards the bottom instead of showing
                 a hard seam at the top edge. -->
            <div class="pointer-events-none absolute inset-0 backdrop-blur-[2px] mask-[linear-gradient(to_bottom,transparent_0%,black_25%)]"></div>
            <div class="pointer-events-none absolute inset-0 backdrop-blur-xs mask-[linear-gradient(to_bottom,transparent_25%,black_50%)]"></div>
            <div class="pointer-events-none absolute inset-0 backdrop-blur-sm mask-[linear-gradient(to_bottom,transparent_50%,black_75%)]"></div>
            <div class="pointer-events-none absolute inset-0 backdrop-blur-lg mask-[linear-gradient(to_bottom,transparent_75%,black_100%)]"></div>

            <!-- relative, so the keys paint above the absolutely positioned blur layers. -->
            <div class="relative flex h-full flex-row items-center justify-center gap-6">
                <div class="flex flex-row items-center justify-center gap-2">
                    <KeyCap key="A" class="size-10" />
                    <span class="flex flex-row items-center gap-1">
                        <ArchiveIcon />
                        {#if viewModel.currentEmail?.classification?.type === "archive"}
                            Entarchivieren
                        {:else}
                            Archivieren
                        {/if}
                    </span>
                </div>

                <div class="flex flex-row items-center justify-center gap-2">
                    <KeyCap key="S" class="size-10" />
                    <span class="flex flex-row items-center gap-1"><WarningIcon /> Spam</span>
                </div>

                <div class="flex flex-row items-center justify-center gap-2">
                    <KeyCap key="R" class="size-10" />
                    <span class="flex flex-row items-center gap-1"><ChatsCircleIcon /> Später antworten</span>
                </div>

                <div class="flex flex-row items-center justify-center gap-2">
                    <KeyCap key="#" class="size-10" />
                    <span class="flex flex-row items-center gap-1"><TagIcon /> Taggen</span>
                </div>

                <div class="flex flex-row items-center justify-center gap-2">
                    <KeyCap key="␣" class="size-10" />
                    <span class="flex flex-row items-center gap-1"><ArrowDownIcon /> Weiter</span>
                </div>

                <div class="flex flex-row items-center justify-center gap-2">
                    <KeyCap key="⌫" class="size-10" />
                    <span class="flex flex-row items-center gap-1"><ArrowUpIcon /> Vorige Mail</span>
                </div>
            </div>
        </div>
    </div>
</main>