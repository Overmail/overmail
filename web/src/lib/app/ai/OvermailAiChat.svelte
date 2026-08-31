<script lang="ts">
    import {Separator} from "$lib/components/ui/separator";
    import {ArrowUpIcon, LightbulbIcon} from "phosphor-svelte";
    import * as InputGroup from "$lib/components/ui/input-group";
    import * as Select from "$lib/components/ui/select";
    import {onMount} from "svelte";
    import {fade} from "svelte/transition";
    import PromptInput from "$lib/app/ai/popover/PromptInput.svelte";
    import {OvermailPromptViewModel} from "$lib/app/ai/popover/OvermailPromptViewModel.svelte";

    const placeholders = [
        "Frage etwas oder gib eine Aufgabe",
        "\"Fasse diese E-Mail in drei Sätzen zusammen.\"",
        "\"Versehe alle E-Mails zu meinem Studium mit dem Label Uni.\"",
        "\"Welche E-Mails warten noch auf eine Antwort von mir?\"",
        "\"Archiviere alle Newsletter, die älter als eine Woche sind.\"",
        "\"Gibt es Fristen oder Termine in dieser E-Mail?\"",
    ]

    let placeholderIndex = $state(Math.round(Math.random() * placeholders.length));
    let placeholder = $derived(placeholders[placeholderIndex % placeholders.length]);

    onMount(() => {
        const interval = setInterval(() => {
            placeholderIndex = Math.round(Math.random() * placeholders.length);
        }, 5000);

        return () => clearInterval(interval);
    })

    const promptViewModel = new OvermailPromptViewModel();
    let promptEmpty = $derived(promptViewModel.isEmpty);

    let promptType: "normal" | "read-only" = $state("normal");
</script>

<h1 class="text-xl">Overmail AI</h1>
<div class="flex flex-col h-192">
    <div class="w-full flex-1 overflow-y-auto">
        history
    </div>

    <div class="flex flex-col min-h-24 max-h-56">
        <InputGroup.Root class="flex-1 min-h-0">
            {#if promptEmpty}
                {#key placeholder}
                    <span
                            class="absolute top-2.5 left-3 text-muted-foreground pointer-events-none"
                            in:fade={{delay: 300, duration: 300}}
                            out:fade={{duration: 300}}
                    >
                        {placeholder}
                    </span>
                {/key}
            {/if}
            <PromptInput viewModel={promptViewModel}/>

            <InputGroup.Addon align="block-end">
                <Select.Root type="single" bind:value={promptType}>
                    <Select.Trigger>
                        {promptType === "normal" ? "Normal" : "Nur lesen"}
                    </Select.Trigger>
                    <Select.Content
                            side="top"
                            align="start"
                    >
                        <Select.Group>
                            <Select.Item value="normal">Normal</Select.Item>
                            <Select.Item value="read-only">Nur lesen</Select.Item>
                        </Select.Group>
                    </Select.Content>
                </Select.Root>

                <InputGroup.Text class="ms-auto">AI Features are experimental and can edit your inbox.
                </InputGroup.Text>
                <Separator orientation="vertical" class="h-4!"/>
                <InputGroup.Button
                        variant="default"
                        class="rounded-full"
                        size="icon-xs"
                        disabled
                >
                    <ArrowUpIcon/>
                    <span class="sr-only">Send</span>
                </InputGroup.Button>
            </InputGroup.Addon>
        </InputGroup.Root>
        <span class="block text-muted-foreground text-sm pt-1 pl-4 font-medium tracking-tight">
            <LightbulbIcon class="inline-block size-3.5 align-[-0.2em]" />
            Markiere #labels, @emails oder :personen
        </span>
    </div>
</div>
