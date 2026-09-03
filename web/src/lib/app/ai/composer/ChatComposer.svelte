<!-- The prompt: editor, mode, model and the send button. -->
<script lang="ts">
    import {Separator} from "$lib/components/ui/separator";
    import {ArrowUpIcon, LightbulbIcon} from "phosphor-svelte";
    import * as InputGroup from "$lib/components/ui/input-group";
    import * as Select from "$lib/components/ui/select";
    import {onMount} from "svelte";
    import {fade} from "svelte/transition";
    import PromptInput from "$lib/app/ai/composer/PromptInput.svelte";
    import {OvermailPromptViewModel} from "$lib/app/ai/composer/OvermailPromptViewModel.svelte";
    import type {PromptInputExports} from "$lib/app/ai/composer/prompt";
    import {Spinner} from "$lib/components/ui/spinner";
    import type {AiChatViewModel} from "$lib/app/ai/AiChatViewModel.svelte";
    import {_} from "svelte-i18n";

    let {viewModel}: {viewModel: AiChatViewModel} = $props();

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

    const promptViewModel = new OvermailPromptViewModel();

    let promptInput: PromptInputExports | undefined = $state();

    function submitPrompt() {
        if (promptViewModel.isEmpty || viewModel.isAnswering) return;

        // A snapshot, because the editor is emptied in the same breath -- the request must not
        // read the prompt after that.
        const prompt = $state.snapshot(promptViewModel.prompt);
        promptInput?.clear();
        viewModel.onPromptSubmitted(prompt);
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
                        || viewModel.isAnswering}
            >
                {#if viewModel.isAnswering}
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
