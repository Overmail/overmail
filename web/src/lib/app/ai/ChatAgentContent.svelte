<!-- An answer: markdown, plus a chip for every mail the agent read while writing it. -->
<script lang="ts">
    import SvelteMarkdown from "@humanspeak/svelte-markdown";
    import ToolCallReadEmail from "$lib/app/ai/ToolCallReadEmail.svelte";
    import ToolCallSearchEmails from "$lib/app/ai/ToolCallSearchEmails.svelte";
    import EntityEmail from "$lib/app/entities/EntityEmail.svelte";
    import EntityLabel from "$lib/app/entities/EntityLabel.svelte";
    import EntityPerson from "$lib/app/entities/EntityPerson.svelte";

    let {
        content,
        streaming = false,
    }: {
        content: string;
        /** The answer is still being written, so the parser reuses what it already has. */
        streaming?: boolean;
    } = $props();

    // Only the tool call elements are ours; everything else keeps the library's renderers, which
    // sanitize urls and leave unknown html alone.
    const renderers = {
        html: {
            "toolcall-read-email": ToolCallReadEmail,
            "toolcall-search-emails": ToolCallSearchEmails,
            // What the agent mentions, written as <email id>, <label id> and <person id>. The
            // html `label` element is overridden by this on purpose: inside an answer the tag
            // means the user's label, and a form label has nothing to do here.
            email: EntityEmail,
            label: EntityLabel,
            person: EntityPerson,
        },
    };
</script>

<!-- The markdown blocks bring no margins of their own here, so the spacing is set once. -->
<div class="flex flex-col gap-2 [&_ol]:list-decimal [&_ol]:pl-5 [&_ul]:list-disc [&_ul]:pl-5
            [&_a]:underline [&_code]:font-mono [&_pre]:overflow-x-auto">
    <SvelteMarkdown source={content} {streaming} {renderers}/>
</div>
