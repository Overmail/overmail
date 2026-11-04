<script lang="ts">
    import {type Snippet} from "svelte";
    import EmlImportDialog from "./EmlImportDialog.svelte";
    import {draggesFiles, groupByKind} from "./fileDrop";
    import {FileDropController, provideFileDrop} from "./fileDropContext.svelte";
    import {useRepositories} from "$lib/repository/repositories";

    /**
     * Files dropped onto the app, routed to what handles their kind.
     *
     * Wraps the signed-in shell. Where a drop is taken is up to the page: a `FileDropZone` (the
     * mail list) takes it and shows what it does. A new kind is an entry in `fileDrop.ts`, its
     * bubble in `FileDropZone` and a branch here.
     */
    let {children}: {children: Snippet} = $props();

    const {inboxes} = useRepositories();

    let emlImport: EmlImportDialog | undefined = $state();
    let emlImportOpen = $state(false);

    const controller = new FileDropController(inboxes, (files) => {
        for (const [kind, ofKind] of groupByKind(files)) {
            switch (kind) {
                case "eml":
                    void emlImport?.start(ofKind);
                    break;
            }
        }
    });
    provideFileDrop(controller);

    $effect(() => {
        controller.disabled = emlImportOpen;
    });

    // Everywhere outside a zone a file is refused rather than let through: let go, the browser
    // would open it in the tab and the app would be gone.
    function refuse(event: DragEvent) {
        if (!draggesFiles(event.dataTransfer)) return;
        event.preventDefault();
        if (event.type === "dragover") event.dataTransfer!.dropEffect = "none";
    }
</script>

<svelte:window ondragover={refuse} ondrop={refuse} />

{@render children()}

<EmlImportDialog bind:this={emlImport} bind:open={emlImportOpen} />
