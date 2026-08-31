<script lang="ts">
    import {Button} from "$lib/components/ui/button";
    import bot from "$lib/components/icons/bot.svg";
    import * as Popover from "$lib/components/ui/popover";
    import OvermailAiChat from "$lib/app/ai/OvermailAiChat.svelte";
    import {_} from "svelte-i18n";

    let {
        open = $bindable(false),
    }: {
        open: boolean;
    } = $props();
</script>

<Popover.Root bind:open={open}>
    <Popover.Trigger>
        <Button
                variant="outline"
        >
            <img src={bot} alt="" class="w-4 h-4"/>
            {$_('ai.title')}
        </Button>
    </Popover.Trigger>

    <Popover.Content class="md:w-2xl w-full">
        <OvermailAiChat/>
    </Popover.Content>
</Popover.Root>
