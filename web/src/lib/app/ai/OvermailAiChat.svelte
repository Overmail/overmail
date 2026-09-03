<script lang="ts">
    import {Separator} from "$lib/components/ui/separator";
    import {ArrowUpIcon, LightbulbIcon} from "phosphor-svelte";
    import * as InputGroup from "$lib/components/ui/input-group";
    import * as Select from "$lib/components/ui/select";
    import {onMount} from "svelte";
    import {fade} from "svelte/transition";
    import PromptInput from "$lib/app/ai/PromptInput.svelte";
    import {OvermailPromptViewModel} from "$lib/app/ai/OvermailPromptViewModel.svelte";
    import type {PromptInputExports, PromptMode} from "$lib/app/ai/prompt";
    import {Spinner} from "$lib/components/ui/spinner";
    import {AiChatViewModel} from "$lib/app/ai/AiChatViewModel.svelte";
    import AiChatSwitcher from "$lib/app/ai/AiChatSwitcher.svelte";
    import {_} from "svelte-i18n";

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

    // Die Leiste unter dem Editor zeigt den Text-Cursor, ist aber selbst nicht editierbar —
    // ein Klick darauf soll deshalb im Prompt landen statt ins Leere zu gehen. Bedienelemente
    // sind ausgenommen, und preventDefault hält den Fokus im Editor, statt ihn erst zu
    // verlieren und per Klick zurückzuholen.
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

        <!-- Debug-Ausgabe, bis der Verlauf gebaut ist: nur Anzahl und Zeitstempel. -->
        <p class="text-muted-foreground text-sm">{chatViewModel.chats.length} chats loaded</p>
        <ul class="text-muted-foreground text-xs font-mono">
            {#each chatViewModel.chats as chat (chat.id)}
                <li>{chat.created_at.toISOString()}</li>
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
                    onPromptSubmit={() => chatViewModel.onPromptSubmitted(promptViewModel.prompt)}
            />

            <InputGroup.Addon align="block-end" onmousedown={focusPromptFromAddon}>
                <Select.Root type="single" bind:value={promptViewModel.prompt.type}>
                    <Select.Trigger>
                        {#if promptViewModel.prompt.type === "normal"}
                            {$_("ai.chat.mode.normal")}
                        {:else if promptViewModel.prompt.type === "ask-before-changes"}
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
                            <Select.Item value="ask-before-changes">{$_('ai.chat.mode.askBeforeChanges')}</Select.Item>
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
                        disabled={promptViewModel.isEmpty || promptViewModel.currentModel.type === "loading"}
                >
                    <ArrowUpIcon/>
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
