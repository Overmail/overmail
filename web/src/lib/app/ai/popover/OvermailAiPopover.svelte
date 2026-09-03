<script lang="ts">
    import {Button} from "$lib/components/ui/button";
    import bot from "$lib/components/icons/bot.svg";
    import * as Popover from "$lib/components/ui/popover";
    import OvermailAiChat from "$lib/app/ai/OvermailAiChat.svelte";
    import {_} from "svelte-i18n";

    let {
        open = $bindable(false),
        onCloseFocus,
    }: {
        open: boolean;
        /**
         * Where the keyboard goes once the panel closes. Without it bits-ui hands the focus back
         * to the trigger, and the trigger answers to Space itself -- the next Space, meant for
         * the mail behind the panel, would reopen the panel instead.
         */
        onCloseFocus?: () => void;
    } = $props();

    let chat: ReturnType<typeof OvermailAiChat> | undefined = $state();
</script>

<Popover.Root bind:open={open}>
    <Popover.Trigger>
        <!-- child, so the trigger *is* the button: rendering one inside the other nests two
             <button> elements, which is invalid and makes the key handling of both fire. -->
        {#snippet child({props})}
            <Button {...props} variant="outline">
                <img src={bot} alt="" class="w-4 h-4"/>
                {$_('ai.title')}
            </Button>
        {/snippet}
    </Popover.Trigger>

    <Popover.Content
            class="md:w-2xl w-full"
            onOpenAutoFocus={(event) => {
                // The panel exists to be typed into, so the prompt takes the focus rather than
                // the first tabbable thing in it.
                event.preventDefault();
                chat?.focusPrompt();
            }}
            onCloseAutoFocus={(event) => {
                if (!onCloseFocus) return;

                event.preventDefault();
                onCloseFocus();
            }}
    >
        <OvermailAiChat bind:this={chat}/>
    </Popover.Content>
</Popover.Root>
