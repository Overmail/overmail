<script lang="ts">
    import * as Empty from "$lib/components/ui/empty";
    import Tray from "phosphor-svelte/lib/Tray";
</script>

<Empty.Root>
    <Empty.Header>
        <Empty.Media variant="icon">
            <Tray />
        </Empty.Media>
        <Empty.Title>Noch keine Mails</Empty.Title>
        <Empty.Description>
            Alles, was aus deinen Konten importiert wird, taucht hier auf — aus welchem Ordner es
            auch kam.
        </Empty.Description>
    </Empty.Header>
</Empty.Root>
