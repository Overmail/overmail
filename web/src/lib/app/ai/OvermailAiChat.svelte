<script lang="ts">
    import {Separator} from "$lib/components/ui/separator";
    import {ArrowUpIcon, LightbulbIcon} from "phosphor-svelte";
    import * as InputGroup from "$lib/components/ui/input-group";
    import * as Select from "$lib/components/ui/select";
    import {onMount} from "svelte";
    import {fade} from "svelte/transition";
    import PromptInput from "$lib/app/ai/PromptInput.svelte";
    import {OvermailPromptViewModel} from "$lib/app/ai/OvermailPromptViewModel.svelte";
    import type {PromptEmail, PromptInputExports, PromptLabel, PromptSender} from "$lib/app/ai/prompt";
    import {Spinner} from "$lib/components/ui/spinner";
    import {AiChatViewModel} from "$lib/app/ai/AiChatViewModel.svelte";
    import AiChatSwitcher from "$lib/app/ai/AiChatSwitcher.svelte";
    import EmailSegment from "$lib/app/ai/EmailSegment.svelte";
    import LabelSegment from "$lib/app/ai/LabelSegment.svelte";
    import SenderSegment from "$lib/app/ai/SenderSegment.svelte";
    import {_, locale} from "svelte-i18n";
    import {formatDistance} from "date-fns";
    import {de, enUS} from "date-fns/locale";

    // Keys, not strings: the rotation has to follow the ui language.
    const placeholderKeys = [
        "ask",
        "summarize",
        "label",
        "waiting",
        "archive",
        "deadlines",
    ].map((name) => `ai.chat.placeholders.${name}`);

    let placeholderIndex = $state(Math.round(Math.random() * placeholderKeys.length));
    let placeholderKey = $derived(placeholderKeys[placeholderIndex % placeholderKeys.length]);

    onMount(() => {
        const interval = setInterval(() => {
            let next = Math.round(Math.random() * placeholderKeys.length);
            while (next === placeholderIndex) {
                next = Math.round(Math.random() * placeholderKeys.length);
            }
            placeholderIndex = next;
        }, 5000);

        return () => clearInterval(interval);
    })

    const chatViewModel = new AiChatViewModel();

    onMount(() => () => chatViewModel.dispose());

    const promptViewModel = new OvermailPromptViewModel();

    let promptInput: PromptInputExports | undefined = $state();

    // date-fns has catalogs of its own, so the relative dates have to be pointed at the ui
    // language separately. `$locale` can be a regional tag such as `de-DE`.
    const dateLocale = $derived($locale?.slice(0, 2) === "de" ? de : enUS);

    // "5 minutes ago" goes stale while the chat is open, so every label is computed against this
    // value rather than Date.now().
    let now = $state(new Date());
    onMount(() => {
        const interval = setInterval(() => now = new Date(), 60_000);
        return () => clearInterval(interval);
    });

    /**
     * The chips of a sent prompt. A reference the server could no longer resolve -- deleted since
     * -- keeps its shape and says so instead of showing a blank chip.
     */
    function emailChip(email: {id: string, subject: string | null, avatarUrl: string | null}): PromptEmail {
        return {...email, subject: email.subject ?? $_("ai.chat.messages.deletedReference")};
    }

    function labelChip(label: {id: string, name: string | null, color: string | null}): PromptLabel {
        return {
            id: label.id,
            name: label.name ?? $_("ai.chat.messages.deletedReference"),
            color: label.color ?? "currentColor",
        };
    }

    function senderChip(
        sender: {id: string, address: string | null, name: string | null, avatarUrl: string | null},
    ): PromptSender {
        return {...sender, address: sender.address ?? $_("ai.chat.messages.deletedReference")};
    }

    function submitPrompt() {
        if (promptViewModel.isEmpty || chatViewModel.isAnswering) return;

        // A snapshot, because the editor is emptied in the same breath -- the request must not
        // read the prompt after that.
        const prompt = $state.snapshot(promptViewModel.prompt);
        promptInput?.clear();
        chatViewModel.onPromptSubmitted(prompt);
    }

    // The bar below the editor shows the text cursor but is not editable itself, so a click on
    // it should land in the prompt instead of going nowhere. Controls are exempt, and
    // preventDefault keeps the focus in the editor rather than losing it and clicking it back.
    function focusPromptFromAddon(event: MouseEvent) {
        if (!(event.target instanceof Element) || event.target.closest("button")) return;

        event.preventDefault();
        promptInput?.focusEnd();
    }
</script>

<AiChatSwitcher viewModel={chatViewModel}/>

<div class="flex flex-col h-192">
    <div class="w-full flex-1 overflow-y-auto">
        <h2 class="text-muted-foreground text-sm font-medium tracking-tight">
            {$_('ai.chat.history')}
        </h2>

        <ul class="flex flex-col gap-3 py-3">
            {#each chatViewModel.currentChatMessages as message (message.id)}
                <li class="flex flex-col gap-0.5" class:items-end={message.type === "user"}>
                    <!-- 80% keeps the two sides apart even when a message is one long line. -->
                    <div
                            class="max-w-[80%] rounded-lg px-3 py-2 text-sm whitespace-pre-wrap wrap-break-word"
                            class:bg-muted={message.type === "user"}
                    >
                        <!-- No line breaks inside: the bubble keeps whitespace, so every newline
                             in this template would show up in the message. -->
                        {#if message.type === "user"}{#each message.content as segment, index (index)}{#if segment.type === "text"}{segment.content}{:else if segment.type === "email"}<EmailSegment email={emailChip(segment.email)}/>{:else if segment.type === "label"}<LabelSegment label={labelChip(segment.label)}/>{:else if segment.type === "sender"}<SenderSegment sender={senderChip(segment.sender)}/>{/if}{/each}{:else if message.content !== ""}{message.content}{:else}<Spinner class="size-4"/>{/if}
                    </div>

                    <span class="text-muted-foreground text-xs">
                        {formatDistance(message.created_at, now, {addSuffix: true, locale: dateLocale})}
                    </span>
                </li>
            {/each}
        </ul>
    </div>

    <div class="flex flex-col min-h-24 max-h-56">
        <InputGroup.Root class="flex-1 min-h-0">
            {#if promptViewModel.isEmpty}
                {#key placeholderKey}
                    <span
                            class="absolute top-2.5 left-3 text-muted-foreground pointer-events-none"
                            in:fade={{delay: 300, duration: 300}}
                            out:fade={{duration: 300}}
                    >
                        {$_(placeholderKey)}
                    </span>
                {/key}
            {/if}
            <PromptInput
                    bind:this={promptInput}
                    viewModel={promptViewModel}
                    onPromptSubmit={submitPrompt}
            />

            <InputGroup.Addon align="block-end" onmousedown={focusPromptFromAddon}>
                <Select.Root type="single" bind:value={promptViewModel.prompt.type}>
                    <Select.Trigger>
                        {#if promptViewModel.prompt.type === "normal"}
                            {$_("ai.chat.mode.normal")}
                        {:else if promptViewModel.prompt.type === "ask-before-write"}
                            {$_("ai.chat.mode.askBeforeChanges")}
                        {:else if promptViewModel.prompt.type === "read-only"}
                            {$_("ai.chat.mode.readOnly")}
                        {/if}
                    </Select.Trigger>
                    <Select.Content
                            side="top"
                            align="start"
                    >
                        <Select.Group>
                            <Select.Item value="normal">{$_('ai.chat.mode.normal')}</Select.Item>
                            <Select.Item value="ask-before-write">{$_('ai.chat.mode.askBeforeChanges')}</Select.Item>
                            <Select.Item value="read-only">{$_('ai.chat.mode.readOnly')}</Select.Item>
                        </Select.Group>
                    </Select.Content>
                </Select.Root>

                <InputGroup.Text class="ms-auto">
                    {#if promptViewModel.currentModel.type === "loading"}
                        <Spinner />
                    {:else}
                        {promptViewModel.currentModel.model}
                    {/if}
                </InputGroup.Text>
                <Separator orientation="vertical" class="h-4!"/>
                <InputGroup.Button
                        variant="default"
                        class="rounded-full"
                        size="icon-xs"
                        onclick={submitPrompt}
                        disabled={promptViewModel.isEmpty
                            || promptViewModel.currentModel.type === "loading"
                            || chatViewModel.isAnswering}
                >
                    {#if chatViewModel.isAnswering}
                        <Spinner/>
                    {:else}
                        <ArrowUpIcon/>
                    {/if}
                    <span class="sr-only">{$_('ai.chat.send')}</span>
                </InputGroup.Button>
            </InputGroup.Addon>
        </InputGroup.Root>
        <span class="block text-muted-foreground text-sm pt-1 pl-4 font-medium tracking-tight">
            <LightbulbIcon class="inline-block size-3.5 align-[-0.2em]" />
            {$_('ai.chat.hint')}
        </span>
    </div>
</div>
