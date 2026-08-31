<script lang="ts">
    import {Separator} from "$lib/components/ui/separator";
    import {ArrowUpIcon, LightbulbIcon} from "phosphor-svelte";
    import * as InputGroup from "$lib/components/ui/input-group";
    import * as Select from "$lib/components/ui/select";
    import {onMount} from "svelte";
    import {fade} from "svelte/transition";
    import PromptInput from "$lib/app/ai/popover/PromptInput.svelte";
    import {OvermailPromptViewModel} from "$lib/app/ai/popover/OvermailPromptViewModel.svelte";
    import {Spinner} from "$lib/components/ui/spinner";
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

    const promptViewModel = new OvermailPromptViewModel();
    let promptEmpty = $derived(promptViewModel.isEmpty);

    let promptType: "normal" | "read-only" = $state("normal");
</script>

<h1 class="text-xl">{$_('ai.title')}</h1>
<div class="flex flex-col h-192">
    <div class="w-full flex-1 overflow-y-auto">
        {$_('ai.chat.history')}
    </div>

    <div class="flex flex-col min-h-24 max-h-56">
        <InputGroup.Root class="flex-1 min-h-0">
            {#if promptEmpty}
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
            <PromptInput viewModel={promptViewModel}/>

            <InputGroup.Addon align="block-end">
                <Select.Root type="single" bind:value={promptType}>
                    <Select.Trigger>
                        {promptType === "normal" ? $_('ai.chat.mode.normal') : $_('ai.chat.mode.readOnly')}
                    </Select.Trigger>
                    <Select.Content
                            side="top"
                            align="start"
                    >
                        <Select.Group>
                            <Select.Item value="normal">{$_('ai.chat.mode.normal')}</Select.Item>
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
                        disabled={promptEmpty || promptViewModel.currentModel.type === "loading"}
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
