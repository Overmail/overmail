<script lang="ts">
    import KeyCap from "$lib/components/key/KeyCap.svelte";
    import EmailStack from "$lib/app/my-stack/EmailStack.svelte";
    import { ArchiveIcon, ArrowDownIcon, ArrowUpIcon, ChatsCircleIcon, TagIcon, WarningIcon } from "phosphor-svelte";
    import {onMount} from "svelte";
    import {EmailStackViewModel} from "$lib/app/my-stack/EmailStackViewModel.svelte";
    import {createHotkeyAttachment} from "@tanstack/svelte-hotkeys";
    import {setStackFocus} from "$lib/app/my-stack/stackFocus";
    import {setPageHeader} from "$lib/app/shell/pageHeader.svelte";
    import {_} from "svelte-i18n";
    import {useRepositories} from "$lib/repository/repositories";

    const repositories = useRepositories();
    let viewModel: EmailStackViewModel = new EmailStackViewModel(
        repositories.mails,
        repositories.emailBody,
    );

    const stackHasEmails = $derived(viewModel.emails.length > 0);

    /** The element the shortcuts are registered on -- see stackFocus.ts for why they hang here. */
    let stack: HTMLElement | undefined = $state();

    function focusStack() {
        stack?.focus();
    }

    // Overlays opened from the stack (a card's menu) give the keyboard back, and so does the
    // assistant, which the header owns.
    setStackFocus({restore: focusStack});
    setPageHeader({restoreFocus: focusStack});

    onMount(() => {
        // Nothing else on the page wants the focus, and the stack is worked through by keyboard.
        focusStack();

        return () => {
            viewModel.dispose();
        }
    })

    /**
     * A shortcut only counts when the stack itself is the focused element. Registering on the
     * element already keeps the assistant and the sidebar out, but a control *inside* the stack --
     * a card's menu button -- handles Space and Enter on its own, and pressing it must not send
     * the mail away at the same time.
     */
    function onStack(handle: () => void) {
        return (event: KeyboardEvent) => {
            if (event.target !== stack) return;

            handle();
        };
    }

    const stackShortcut = () => ({enabled: stackHasEmails});

    const keepEmail = createHotkeyAttachment(
        "Space",
        onStack(() => viewModel.onKeepEmail()),
        stackShortcut,
    );

    const previousEmail = createHotkeyAttachment(
        "Backspace",
        onStack(() => viewModel.onPreviousEmail()),
        stackShortcut,
    );

    const archiveEmail = createHotkeyAttachment(
        "A",
        onStack(() => viewModel.onArchiveOrUnarchiveEmail()),
        stackShortcut,
    );
</script>

<svelte:head>
    <title>Stack - Overmail</title>
</svelte:head>

<!-- flex-1 down the whole chain: <main> sits in the sidebar inset's flex column, and only a
     flex-1 item picks up the height left over by the header. A percentage height has nothing to
     resolve against here, since none of these boxes has a height of its own. -->
<!-- The scope of the stack shortcuts, and the element that holds their focus. It reaches around
     the shortcut bar as well as the cards, so that clicking anywhere in here -- on a card, on the
     bar, on empty space -- lands on this element and leaves the shortcuts working; only a real
     control inside it takes the focus away, and then the keys belong to that control.
     No focus ring: this is a keyboard scope, not something to point at. -->
<main
        bind:this={stack}
        tabindex="-1"
        class="flex flex-1 flex-col outline-none"
        {@attach keepEmail}
        {@attach previousEmail}
        {@attach archiveEmail}
>
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
            <!-- Wraps rather than running past the edge: six shortcuts do not fit next to each
                 other on a narrow screen, and a row that overflows here scrolls the whole page. -->
            <div class="relative flex h-full flex-row flex-wrap items-center justify-center gap-x-6 gap-y-1 px-4">
                <div class="flex flex-row items-center justify-center gap-2">
                    <KeyCap key="A" class="size-10" />
                    <span class="flex flex-row items-center gap-1">
                        <ArchiveIcon />
                        {#if viewModel.currentEmail?.classification?.type === "archive"}
                            {$_('myStack.shortcuts.unarchive')}
                        {:else}
                            {$_('myStack.shortcuts.archive')}
                        {/if}
                    </span>
                </div>

                <div class="flex flex-row items-center justify-center gap-2">
                    <KeyCap key="S" class="size-10" />
                    <span class="flex flex-row items-center gap-1"><WarningIcon /> {$_('myStack.shortcuts.spam')}</span>
                </div>

                <div class="flex flex-row items-center justify-center gap-2">
                    <KeyCap key="R" class="size-10" />
                    <span class="flex flex-row items-center gap-1"><ChatsCircleIcon /> {$_('myStack.shortcuts.replyLater')}</span>
                </div>

                <div class="flex flex-row items-center justify-center gap-2">
                    <KeyCap key="#" class="size-10" />
                    <span class="flex flex-row items-center gap-1"><TagIcon /> {$_('myStack.shortcuts.tag')}</span>
                </div>

                <div class="flex flex-row items-center justify-center gap-2">
                    <KeyCap key="␣" class="size-10" />
                    <span class="flex flex-row items-center gap-1"><ArrowDownIcon /> {$_('myStack.shortcuts.next')}</span>
                </div>

                <div class="flex flex-row items-center justify-center gap-2">
                    <KeyCap key="⌫" class="size-10" />
                    <span class="flex flex-row items-center gap-1"><ArrowUpIcon /> {$_('myStack.shortcuts.previous')}</span>
                </div>
            </div>
        </div>
    </div>
</main>