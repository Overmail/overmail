<script lang="ts">
    import {Separator} from "$lib/components/ui/separator";
    import {ArrowUpIcon} from "phosphor-svelte";
    import * as InputGroup from "$lib/components/ui/input-group";
    import * as Select from "$lib/components/ui/select";
    import {onMount} from "svelte";
    import {fade} from "svelte/transition";
    import type {Prompt} from "$lib/app/ai/popover/prompt";
    import PromptInput from "$lib/app/ai/popover/PromptInput.svelte";

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

    let prompt: Prompt = $state({
        segments: [
            {type: "text", content: "Fasse "},
            {type: "email", emailId: "email-123"},
            {type: "text", content: " zusammen und versehe sie mit dem Label "},
            {type: "label", labelId: "label-uni"},
            {type: "text", content: "."},
        ],
    });

    let promptEmpty = $derived(
        prompt.segments.every((s) => s.type === "text" && s.content.trim() === "")
    );

    let promptType: "normal" | "read-only" = $state("normal");
</script>

<h1 class="text-xl">Overmail AI</h1>
<div class="flex flex-col h-128 pb-4">
    <div class="w-full flex-1 overflow-y-auto">
        history
    </div>

    <div class="h-24">
        <InputGroup.Root>
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
            <PromptInput bind:prompt/>

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
    </div>
</div>
