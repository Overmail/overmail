<script lang="ts">
    import { onMount, type Snippet } from "svelte";
    import { cn } from "$lib/utils.ts";
    import { Gesture } from "@use-gesture/vanilla";

    let {
        children,
        onSizeChanged,
        zoom = $bindable(1),
        cameraX = $bindable(0),
        cameraY = $bindable(0),
        class: className
    }: {
        children?: Snippet;
        onSizeChanged?: (width: number, height: number) => void;
        zoom?: number;
        cameraX?: number;
        cameraY?: number;
        class?: string;
    } = $props();

    let clientWidth = $state(0);
    let clientHeight = $state(0);

    let containerElement: HTMLDivElement | null = $state(null);

    let gestureInstance: Gesture | null = null;

    let pinching = false;

    function clampZoom(value: number): number {
        return Math.max(0.1, Math.min(10, value));
    }

    function getPointerPosition(clientX: number, clientY: number): [number, number] {
        const element = containerElement;

        if (!element) {
            return [0, 0];
        }

        const rect = element.getBoundingClientRect();

        return [
            clientX - rect.left - rect.width / 2,
            clientY - rect.top - rect.height / 2
        ];
    }

    function zoomAt(clientX: number, clientY: number, nextZoom: number): void {
        const [pointerX, pointerY] = getPointerPosition(clientX, clientY);

        const previousZoom = zoom;

        if (nextZoom === previousZoom) {
            return;
        }

        const ratio = nextZoom / previousZoom;

        cameraX = pointerX - (pointerX - cameraX) * ratio;
        cameraY = pointerY - (pointerY - cameraY) * ratio;
        zoom = nextZoom;
    }

    $effect(() => {
        onSizeChanged?.(clientWidth, clientHeight);
    });

    onMount(() => {
        const element = containerElement;

        if (!element) {
            return;
        }

        gestureInstance = new Gesture(
            element,
            {
                onDrag: ({ delta: [dx, dy] }) => {
                    if (pinching) {
                        return;
                    }

                    cameraX += dx;
                    cameraY += dy;
                },

                onPinchStart: () => {
                    pinching = true;
                },

                onPinchEnd: () => {
                    pinching = false;
                },

                onPinch: ({ origin: [originX, originY], offset: [scale] }) => {
                    zoomAt(originX, originY, clampZoom(scale));
                },

                onWheel: ({ event, delta: [dx, dy] }) => {
                    if (event.ctrlKey) {
                        return;
                    }

                    event.preventDefault();

                    cameraX -= dx;
                    cameraY -= dy;
                }
            },
            {
                drag: {
                    filterTaps: true,
                    eventOptions: {
                        passive: false
                    }
                },

                wheel: {
                    eventOptions: {
                        passive: false
                    }
                },

                pinch: {
                    modifierKey: "ctrlKey",
                    eventOptions: {
                        passive: false
                    },
                    from: () => [zoom, 0],
                    scaleBounds: {
                        min: 0.1,
                        max: 10
                    }
                }
            }
        );

        return () => {
            gestureInstance?.destroy();
            gestureInstance = null;
        };
    });
</script>

<div
        bind:this={containerElement}
        class={cn(
        "relative h-full w-full overflow-hidden select-none touch-none",
        className
    )}
        bind:clientWidth
        bind:clientHeight
        style="overscroll-behavior: none"
>
    <div
            class="absolute left-0 top-0 h-full w-full origin-center will-change-transform"
            style:transform="translate3d({cameraX}px, {cameraY}px, 0) scale({zoom})"
    >
        {@render children?.()}
    </div>
</div>