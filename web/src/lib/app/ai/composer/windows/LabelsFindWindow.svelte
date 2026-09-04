<!--
    The window `#` opens in the prompt: the label list, above the caret.

    Only the window and the segment it produces are the prompt's business; picking a label is
    LabelPicker's, which the mail panel uses as well.
-->
<script lang="ts">
    import TriggerWindow from "$lib/app/ai/composer/windows/TriggerWindow.svelte";
    import LabelPicker from "$lib/app/labels/LabelPicker.svelte";
    import type {PromptTriggerWindowProps} from "$lib/app/ai/composer/prompt";

    let {
        query,
        left,
        bottom,
        onReplace,
        onDismiss,
    }: PromptTriggerWindowProps = $props();

    let picker: ReturnType<typeof LabelPicker> | undefined = $state();

    // Keyboard events handed down by PromptInput; true means the event was consumed.
    export function handleKey(event: KeyboardEvent): boolean {
        return picker?.handleKey(event) ?? false;
    }
</script>

<TriggerWindow {left} {bottom} class="w-64 p-1">
    <!--
        pb-12: the window's own footer of keyboard hints sits over the bottom of the list.

        No creating from here: a prompt is written about labels that exist, and a name typed after
        `#` is a search rather than a decision to make one.
    -->
    <LabelPicker
            bind:this={picker}
            {query}
            class="pb-12 *:rounded-sm"
            onSelect={(label) => onReplace({
                type: "label",
                label: {id: label.id, name: label.name, color: label.color},
            })}
            onDismiss={onDismiss}
    />
</TriggerWindow>
