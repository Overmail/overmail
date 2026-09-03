<!--
    One of the thinking-orbs animations (MIT, Jakub Antalik). The package ships a React
    component; only its engine is used here, so the canvas is driven from Svelte directly.
-->
<script lang="ts">
    import {MODE_DRAWS, resolvePreset, type OrbSize, type OrbState} from "thinking-orbs/engine";

    let {
        // Not `state`: a variable of that name would turn every `$state` rune in this file into
        // a store subscription.
        variant = "working",
        size = 20,
        class: className,
    }: {
        variant?: OrbState;
        /** The package ships two tuned designs, not a scale factor: 20 inline, 64 avatar-sized. */
        size?: OrbSize;
        class?: string;
    } = $props();

    let canvas: HTMLCanvasElement | undefined = $state();

    /** The orb paints its ink in the opposite of the background, so it follows the ui theme. */
    function isDarkTheme(element: HTMLElement): boolean {
        if (element.closest(".dark")) return true;
        if (element.closest(".light")) return false;
        return window.matchMedia?.("(prefers-color-scheme: dark)").matches ?? false;
    }

    $effect(() => {
        const element = canvas;
        if (!element) return;

        // Retina without paying for more than that.
        const pixelRatio = Math.min(2, window.devicePixelRatio || 1);
        element.width = Math.round(size * pixelRatio);
        element.height = Math.round(size * pixelRatio);

        const context = element.getContext("2d");
        if (!context) return;

        const {mode, speed, opts} = resolvePreset(variant, size);
        const draw = MODE_DRAWS[mode];
        const isDark = isDarkTheme(element);

        const paint = (time: number) => {
            context.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0);
            context.clearRect(0, 0, size, size);
            draw(context, size, time, isDark, opts);
        };

        // A still frame is the whole animation for anyone who asked for less motion.
        if (window.matchMedia?.("(prefers-reduced-motion: reduce)").matches) {
            paint(0.6);
            return;
        }

        let frame = 0;
        const tick = () => {
            paint((performance.now() / 1000) * speed);
            frame = requestAnimationFrame(tick);
        };
        tick();

        return () => cancelAnimationFrame(frame);
    });
</script>

<!-- Decoration next to a label that says the same thing, so screen readers skip it. -->
<canvas
        bind:this={canvas}
        aria-hidden="true"
        style:width="{size}px"
        style:height="{size}px"
        class={className}
></canvas>
