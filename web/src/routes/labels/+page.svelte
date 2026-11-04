<!--
    The label map: every label on a mail as a circle, pulled towards the labels it shares mails
    with. Double-clicking one opens the listing of its mails.

    It starts with the labels that belong under no other, see `labelHierarchy`. A click on one
    brings out the labels under it, the next click folds them away again -- so a broad label like
    "Newsletter" does not bury the map under everything it is related to.

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
    import {fontSizeFor, nodeAt, screenToWorld, textColorOn} from "$lib/app/label-map/labelMapCanvas";
    import {
        buildHierarchy,
        type LabelHierarchy,
        toggleLabel,
        visibleLabels,
    } from "$lib/app/label-map/labelHierarchy";
    import {labelView} from "$lib/app/views/predefinedViews";
    import {inlineViewHref} from "$lib/app/views/viewPath";
    import Freecam from "$lib/components/freecam/Freecam.svelte";

    const simulation = new LabelMapSimulation();

    /**
     * The labels on the map. Raw: the simulation moves these on every tick, and nothing in the
     * markup reads a position.
     */
    let nodes = $state.raw<LabelMapNode[]>([]);

    /** Every label, for the links below the canvas: those folded away are on the map as well. */
    let allNodes = $state.raw<LabelMapNode[]>([]);

    let hierarchy: LabelHierarchy = {parentOf: new Map(), childrenOf: new Map(), roots: []};

    /** The labels that have been opened, see `toggleLabel`. */
    let expanded = new Set<string>();

    let zoom = $state(1);
    let cameraX = $state(0);
    let cameraY = $state(0);
    let width = $state(0);
    let height = $state(0);

    let canvas: HTMLCanvasElement | null = $state(null);
    let hovered = $state.raw<LabelMapNode | null>(null);

    /**
     * How each label is drawn: its colour as `#rrggbb`, what its name is written in on top of it,
     * and how large -- all of which only changes with the label, not from frame to frame.
     */
    const looks = new Map<string, {fill: string; text: string; fontSize: number}>();

    let frame = 0;

    /**
     * The size every name is set in, and scaled from to its own: setting a font is costly enough
     * that it is done once a frame rather than once a label.
     */
    const BASE_FONT_SIZE = 100;

    /** The page's font at [BASE_FONT_SIZE], see [draw]. */
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
        font ??= `400 ${BASE_FONT_SIZE}px ${getComputedStyle(canvas).fontFamily}`;
        context.font = font;
        context.textAlign = "center";
        context.textBaseline = "middle";

        // From each opened label to the labels it brought out, under the circles.
        const visibleById = new Map(nodes.map((node) => [node.id, node]));
        context.lineWidth = 2 / zoom;
        context.globalAlpha = 0.4;
        for (const node of nodes) {
            const parent = visibleById.get(hierarchy.parentOf.get(node.id) ?? "");
            if (!parent) continue;

            context.beginPath();
            context.moveTo(parent.x ?? 0, parent.y ?? 0);
            context.lineTo(node.x ?? 0, node.y ?? 0);
            context.strokeStyle = lookOf(context, parent).fill;
            context.stroke();
        }
        context.globalAlpha = 1;

        for (const node of nodes) {
            const x = node.x ?? 0;
            const y = node.y ?? 0;
            const r = node.radius;
            if (x + r < left || x - r > right || y + r < top || y - r > bottom) continue;

            const look = lookOf(context, node);

            context.beginPath();
            context.arc(x, y, r, 0, Math.PI * 2);
            context.fillStyle = look.fill;
            context.fill();

            if (node === hovered) {
                context.lineWidth = 3 / zoom;
                context.strokeStyle = look.text;
                context.globalAlpha = 0.6;
                context.stroke();
                context.globalAlpha = 1;
            }

            const scale = look.fontSize / BASE_FONT_SIZE;
            context.save();
            context.translate(x, y);
            context.scale(scale, scale);
            context.fillStyle = look.text;
            context.fillText(node.label.name, 0, 0);
            context.restore();

            const folded = expanded.has(node.id) ? 0 : (hierarchy.childrenOf.get(node.id)?.length ?? 0);
            if (folded > 0) drawBadge(context, node, look, folded);
        }
    }

    /**
     * How many labels are folded away behind [node]: a small circle on its rim, in the colours of
     * the label turned around.
     */
    function drawBadge(
        context: CanvasRenderingContext2D,
        node: LabelMapNode,
        look: {fill: string; text: string},
        count: number,
    ) {
        const angle = -Math.PI / 4;
        const x = (node.x ?? 0) + Math.cos(angle) * node.radius;
        const y = (node.y ?? 0) + Math.sin(angle) * node.radius;
        const r = Math.max(9, node.radius * 0.24);

        context.beginPath();
        context.arc(x, y, r, 0, Math.PI * 2);
        context.fillStyle = look.text;
        context.fill();
        context.lineWidth = Math.max(1.5, r * 0.15);
        context.strokeStyle = look.fill;
        context.stroke();

        const scale = r / BASE_FONT_SIZE;
        context.save();
        context.translate(x, y);
        context.scale(scale, scale);
        context.fillStyle = look.fill;
        context.fillText(`+${count}`, 0, 0);
        context.restore();
    }

    /** Expects the context to be set to [font]. */
    function lookOf(context: CanvasRenderingContext2D, node: LabelMapNode) {
        let look = looks.get(node.id);
        if (!look) {
            // The canvas reads any css colour and hands it back as `#rrggbb` when it is opaque.
            context.fillStyle = node.label.color;
            const fill = String(context.fillStyle);
            const widthPerPixel = context.measureText(node.label.name).width / BASE_FONT_SIZE;
            look = {fill, text: textColorOn(fill), fontSize: fontSizeFor(node.radius, widthPerPixel)};
            looks.set(node.id, look);
        }

        return look;
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

    /** Where the pointer went down, so a click at the end of a drag opens nothing. */
    let pressedAt: [number, number] | null = null;

    function onClick(event: MouseEvent) {
        const start = pressedAt;
        pressedAt = null;
        if (start && Math.hypot(event.clientX - start[0], event.clientY - start[1]) > 4) return;
        // The second click of a double click: that one opens the mails, it does not fold back.
        if (event.detail > 1) return;

        const node = nodeUnder(event);
        if (!node || !hierarchy.childrenOf.has(node.id)) return;

        expanded = toggleLabel(hierarchy, expanded, node.id);
        // Each label coming out starts at the one it belongs under.
        const byId = new Map(allNodes.map((other) => [other.id, other]));
        const origins = new Map<string, LabelMapNode>();
        for (const [child, parent] of hierarchy.parentOf) origins.set(child, byId.get(parent)!);
        simulation.show(visibleLabels(hierarchy, expanded), origins);
        nodes = simulation.visibleNodes;
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

                hierarchy = buildHierarchy(simulation.labels);
                allNodes = simulation.nodes;
                nodes = simulation.calculate(visibleLabels(hierarchy, expanded));
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

<!-- svelte-ignore a11y_no_static_element_interactions, a11y_click_events_have_key_events: the labels are reachable as the links below -->
<div
        class="relative h-full w-full"
        class:cursor-pointer={hovered !== null}
        onpointerdown={(event) => (pressedAt = [event.clientX, event.clientY])}
        onclick={onClick}
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
            {#each allNodes as node (node.id)}
                <li><a href={hrefOf(node)}>{node.label.name}</a></li>
            {/each}
        </ul>
    </nav>
</div>
