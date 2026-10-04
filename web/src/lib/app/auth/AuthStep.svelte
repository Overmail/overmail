<script lang="ts">
    import type {Snippet} from "svelte";
    import {claimStepIntro, stepIn, stepOut} from "./stepTransition";

    let {children}: { children: Snippet } = $props();

    const animate = claimStepIntro();
</script>

<!-- Every step sits in the same cell of the page's grid, so the one leaving and the one arriving
     overlap for the length of the transition instead of stacking. Each one is a whole page and
     scrolls on its own when it does not fit. -->
<div class="col-start-1 row-start-1 h-full min-h-0 overflow-y-auto p-16 pt-32 will-change-transform"
     in:stepIn={{animate}} out:stepOut>
    {@render children()}
</div>
