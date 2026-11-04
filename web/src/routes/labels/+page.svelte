<!--
    The label map: every label on a mail as a circle, pulled towards the labels it shares mails
    with. Double-clicking one opens the listing of its mails.

    Drawn on a canvas, not as an element per label: the simulation moves every circle on every
    frame, and Safari repainted the whole scaled layer they sat in each time. `Freecam` still owns
    the camera; the canvas sits under it and draws with the same transform.
-->
<script lang="ts">
    import {onMount} from "svelte";
    import {goto} from "$app/navigation";
    import {page} from "$app/state";
    import {_} from "svelte-i18n";
    import {type LabelMapNode, LabelMapSimulation} from "$lib/app/label-map/LabelMapSimulation";
    import {nodeAt, screenToWorld, textColorOn} from "$lib/app/label-map/labelMapCanvas";
    import {labelView} from "$lib/app/views/predefinedViews";
    import {inlineViewHref} from "$lib/app/views/viewPath";
    import Freecam from "$lib/components/freecam/Freecam.svelte";

    const simulation = new LabelMapSimulation();

    /** Raw: the simulation moves these on every tick, and nothing in the markup reads a position. */
    let nodes = $state.raw<LabelMapNode[]>([]);

    let zoom = $state(1);
    let cameraX = $state(0);
    let cameraY = $state(0);
    let width = $state(0);
    let height = $state(0);

    let canvas: HTMLCanvasElement | null = $state(null);
    let hovered = $state.raw<LabelMapNode | null>(null);

    /** The colour of each label as `#rrggbb`, and what its name is written in on top of it. */
    const colors = new Map<string, {fill: string; text: string}>();

    let frame = 0;

    /** The page's font at the size a name is written in, see [draw]. */
    let font: string | null = null;

    /** At most one draw per frame, however many ticks and camera moves ask for one. */
    function scheduleDraw() {
        if (frame !== 0) return;
        frame = requestAnimationFrame(() => {
            frame = 0;
            draw();
        });
    }

    function draw() {
        const context = canvas?.getContext("2d");
        if (!canvas || !context || width === 0 || height === 0) return;

        const ratio = window.devicePixelRatio || 1;
        const pixelWidth = Math.round(width * ratio);
        const pixelHeight = Math.round(height * ratio);
        if (canvas.width !== pixelWidth || canvas.height !== pixelHeight) {
            canvas.width = pixelWidth;
            canvas.height = pixelHeight;
        }

        context.setTransform(1, 0, 0, 1, 0, 0);
        context.clearRect(0, 0, pixelWidth, pixelHeight);
        context.setTransform(
            ratio * zoom, 0, 0, ratio * zoom,
            ratio * (width / 2 + cameraX), ratio * (height / 2 + cameraY),
        );

        // What is on screen, in the world: everything else is skipped.
        const [left, top] = screenToWorld(0, 0, width, height, {cameraX, cameraY, zoom});
        const [right, bottom] = screenToWorld(width, height, width, height, {cameraX, cameraY, zoom});

        // Read once: a computed style in every frame can make Safari recalculate styles.
        font ??= `500 12px ${getComputedStyle(canvas).fontFamily}`;
        context.font = font;
        context.textAlign = "center";
        context.textBaseline = "middle";

        for (const node of nodes) {
            const x = node.x ?? 0;
            const y = node.y ?? 0;
            const r = node.radius;
            if (x + r < left || x - r > right || y + r < top || y - r > bottom) continue;

            const color = colorOf(context, node);

            context.beginPath();
            context.arc(x, y, r, 0, Math.PI * 2);
            context.fillStyle = color.fill;
            context.fill();

            if (node === hovered) {
                context.lineWidth = 3 / zoom;
                context.strokeStyle = color.text;
                context.globalAlpha = 0.6;
                context.stroke();
                context.globalAlpha = 1;
            }

            context.fillStyle = color.text;
            context.fillText(node.label.name, x, y);
        }
    }

    function colorOf(context: CanvasRenderingContext2D, node: LabelMapNode) {
        let color = colors.get(node.id);
        if (!color) {
            // The canvas reads any css colour and hands it back as `#rrggbb` when it is opaque.
            context.fillStyle = node.label.color;
            const fill = String(context.fillStyle);
            color = {fill, text: textColorOn(fill)};
            colors.set(node.id, color);
        }

        return color;
    }

    function nodeUnder(event: MouseEvent): LabelMapNode | null {
        const rect = (event.currentTarget as HTMLElement).getBoundingClientRect();
        const [x, y] = screenToWorld(
            event.clientX - rect.left,
            event.clientY - rect.top,
            rect.width,
            rect.height,
            {cameraX, cameraY, zoom},
        );

        return nodeAt(nodes, x, y);
    }

    function hrefOf(node: LabelMapNode): string {
        return inlineViewHref(labelView(node.id), page.url);
    }

    function onDoubleClick(event: MouseEvent) {
        const node = nodeUnder(event);
        if (node) void goto(hrefOf(node));
    }

    function onPointerMove(event: PointerEvent) {
        // A drag moves the camera, not the pointer over a label.
        if (event.buttons !== 0) return;

        const node = nodeUnder(event);
        if (node !== hovered) hovered = node;
    }

    // Whatever the picture depends on that is not the simulation's own tick.
    $effect(() => {
        void [zoom, cameraX, cameraY, width, height, hovered, nodes, canvas];
        scheduleDraw();
    });

    onMount(() => {
        let mounted = true;

        simulation.fetchLabels()
            .then(() => {
                if (!mounted) return;

                nodes = simulation.calculate();
                simulation.simulation?.on("tick", scheduleDraw);
            })
            .catch((error) => console.error(error));

        return () => {
            mounted = false;
            simulation.stop();
            cancelAnimationFrame(frame);
        };
    });
</script>

<svelte:head>
    <title>{$_("labels.map.title")}</title>
</svelte:head>

<!-- svelte-ignore a11y_no_static_element_interactions: the labels are reachable as the links below -->
<div
        class="relative h-full w-full"
        class:cursor-pointer={hovered !== null}
        ondblclick={onDoubleClick}
        onpointermove={onPointerMove}
        onpointerleave={() => (hovered = null)}
        title={hovered ? `${hovered.label.name} · ${$_("labels.emailCount", {values: {count: hovered.label.email_count}})}` : undefined}
>
    <canvas bind:this={canvas} class="pointer-events-none absolute inset-0 h-full w-full" aria-hidden="true"></canvas>

    <Freecam
            bind:zoom
            bind:cameraX
            bind:cameraY
            onSizeChanged={(w, h) => {
                width = w;
                height = h;
            }}
    />

    <p class="pointer-events-none absolute bottom-4 left-1/2 -translate-x-1/2 text-xs text-muted-foreground">
        {$_("labels.map.hint")}
    </p>

    <!-- What the canvas shows, for whoever does not see it. -->
    <nav class="sr-only" aria-label={$_("labels.map.title")}>
        <ul>
            {#each nodes as node (node.id)}
                <li><a href={hrefOf(node)}>{node.label.name}</a></li>
            {/each}
        </ul>
    </nav>
</div>
