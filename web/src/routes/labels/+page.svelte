<script lang="ts">
    import { onMount } from 'svelte';
    import { type LabelMapNode, LabelMapSimulation } from '$lib/app/label-map/LabelMapSimulation.ts';
    import Freecam from "$lib/components/freecam/Freecam.svelte";

    const simulation = new LabelMapSimulation();

    let nodes = $state<LabelMapNode[]>([]);
    let links = $state(simulation.links);

    onMount(() => {
        let mounted = true;

        const initialize = async () => {
            await simulation.fetchLabels();
            if (!mounted) return;

            nodes = simulation.calculate();
            links = simulation.links;

            simulation.simulation?.on('tick', () => {
                if (!mounted) return;
                nodes = [...simulation.nodes];
                links = [...simulation.links];
            });
        };

        initialize();

        return () => {
            mounted = false;
            simulation.stop();
        };
    });
</script>

<Freecam>
    <!-- Nodes -->
    {#each nodes as node (node.id)}
        {@const diameter = node.radius * 2}

        <div
                class="absolute flex items-center justify-center rounded-full whitespace-nowrap select-none text-xs font-medium shadow-sm"
                style:translate="{node.x ?? 0}px {node.y ?? 0}px"
                style:width="{diameter}px"
                style:height="{diameter}px"
                style:background-color={node.label.color}
                style:transform="translate(-50%, -50%)"
        >
            {node.label.name}
        </div>
    {/each}
</Freecam>