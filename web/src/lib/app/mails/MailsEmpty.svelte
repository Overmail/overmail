<script lang="ts">
    import {_} from "svelte-i18n";
    import {TrayIcon} from "phosphor-svelte";
</script>

<div class="flex flex-col items-center gap-1 py-6">
    <TrayIcon class="text-muted-foreground size-6"/>
    <p class="text-muted-foreground text-sm">{$_("mails.empty")}</p>
</div>
