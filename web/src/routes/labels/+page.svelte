<!--
    The label map: every label on a mail as a circle, pulled towards the labels it shares mails
    with. Double-clicking one opens the listing of its mails.

    It starts with the labels that belong under no other, see `labelHierarchy`. A click on one
    flies to it, brings out the labels under it and fades whatever it has nothing to do with --
    the label is in focus, and only it and what belongs to it push each other around. Zooming back
    out undoes that a layer at a time: the focus lets go, and what was brought out at a closer zoom
    folds away again, so the far view shows the broad labels and the close one the details.

    Drawn on a canvas, not as an element per label: the simulation moves every circle on every
    frame, and Safari repainted the whole scaled layer they sat in each time. `Freecam` still owns
    the camera; the canvas sits under it and draws with the same transform.
-->
<script lang="ts">
    import {onMount, untrack} from "svelte";
    import {goto} from "$app/navigation";
    import {page} from "$app/state";
    import {_} from "svelte-i18n";
    import {type LabelMapLabel, type LabelMapNode, LabelMapSimulation} from "$lib/app/label-map/LabelMapSimulation";
    import {fontSizeFor, nodeAt, screenToWorld, textColorOn} from "$lib/app/label-map/labelMapCanvas";
    import {
        buildHierarchy,
        type LabelHierarchy,
        relatedLabels,
        toggleLabel,
        visibleLabels,
    } from "$lib/app/label-map/labelHierarchy";
    import {labelView} from "$lib/app/views/predefinedViews";
    import {inlineViewHref} from "$lib/app/views/viewPath";
    import Freecam from "$lib/components/freecam/Freecam.svelte";

    /** How opaque a label is that has nothing to do with the one in focus. */
    const FADED = 0.12;

    /**
     * How far below the zoom a label was opened at the view has to go before it folds away again,
     * and before the focus lets go of it. Well below, so a little zooming does not undo a click.
     */
    const FOLD_BELOW = 0.5;

    /** How long the flight to a clicked label takes, in milliseconds. */
    const FLIGHT = 650;

    const simulation = new LabelMapSimulation();

    /** Every label, for the links below the canvas: those folded away are on the map as well. */
    let allNodes = $state.raw<LabelMapNode[]>([]);

    /**
     * The labels drawn: what is not folded away. Raw: the simulation moves these on every tick,
     * and nothing in the markup reads a position.
     */
    let nodes = $state.raw<LabelMapNode[]>([]);

    let hierarchy: LabelHierarchy = {parentOf: new Map(), childrenOf: new Map(), roots: []};
    let labelsById = new Map<string, LabelMapLabel>();

    /** The labels that have been opened, see `toggleLabel`, and the zoom each was opened at. */
    let expanded = new Set<string>();
    const openedAt = new Map<string, number>();

    /** The label in focus, what stays in sight with it, and the zoom it was focused at. */
    let focus = $state.raw<string | null>(null);
    let related = new Set<string>();
    let focusedAt = 0;

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

    /** How opaque each label is right now, on its way to what the focus asks of it. */
    const opacities = new Map<string, number>();

    let frame = 0;
    let lastFrameAt = 0;

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
        frame = requestAnimationFrame((now) => {
            frame = 0;
            const elapsed = lastFrameAt === 0 ? 16 : Math.min(now - lastFrameAt, 100);
            lastFrameAt = now;
            // Still fading: the next frame carries on, even with nothing else moving.
            if (draw(elapsed)) scheduleDraw();
            else lastFrameAt = 0;
        });
    }

    /** Whether a label is still fading. */
    function draw(elapsed: number): boolean {
        const context = canvas?.getContext("2d");
        if (!canvas || !context || width === 0 || height === 0) return false;

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

        let fading = false;
        const step = elapsed / 250;
        for (const node of nodes) {
            const target = focus === null || related.has(node.id) ? 1 : FADED;
            const current = opacities.get(node.id) ?? target;
            const next = current < target ? Math.min(target, current + step) : Math.max(target, current - step);
            opacities.set(node.id, next);
            if (next !== target) fading = true;
        }
        const opacityOf = (node: LabelMapNode) => opacities.get(node.id) ?? 1;

        // Faded labels first, so those in focus lie on top of them.
        const ordered = [...nodes].sort((a, b) => opacityOf(a) - opacityOf(b));

        // From each opened label to the labels it brought out, under the circles.
        const visibleById = new Map(nodes.map((node) => [node.id, node]));
        context.lineWidth = 2 / zoom;
        for (const node of ordered) {
            const parent = visibleById.get(hierarchy.parentOf.get(node.id) ?? "");
            if (!parent) continue;

            context.globalAlpha = 0.4 * Math.min(opacityOf(node), opacityOf(parent));
            context.beginPath();
            context.moveTo(parent.x ?? 0, parent.y ?? 0);
            context.lineTo(node.x ?? 0, node.y ?? 0);
            context.strokeStyle = lookOf(context, parent).fill;
            context.stroke();
        }

        for (const node of ordered) {
            const x = node.x ?? 0;
            const y = node.y ?? 0;
            const r = node.radius;
            if (x + r < left || x - r > right || y + r < top || y - r > bottom) continue;

            const look = lookOf(context, node);
            const opacity = opacityOf(node);
            context.globalAlpha = opacity;

            context.beginPath();
            context.arc(x, y, r, 0, Math.PI * 2);
            context.fillStyle = look.fill;
            context.fill();

            if (node === hovered || node.id === focus) {
                context.lineWidth = 3 / zoom;
                context.strokeStyle = look.text;
                context.globalAlpha = 0.6 * opacity;
                context.stroke();
                context.globalAlpha = opacity;
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
        context.globalAlpha = 1;

        return fading;
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

    /**
     * Brings the map in line with [expanded] and [focus]: what is drawn, and what of it is
     * simulated -- with a label in focus only it and what belongs to it, the rest stands still.
     */
    function apply() {
        const previous = new Set(nodes.map((node) => node.id));
        const visible = visibleLabels(hierarchy, expanded);
        related = focus === null ? new Set() : relatedLabels(hierarchy, labelsById, focus);

        nodes = allNodes.filter((node) => visible.has(node.id));

        // Each label coming out starts at the one it belongs under.
        const byId = new Map(allNodes.map((node) => [node.id, node]));
        const appearing = new Map<string, LabelMapNode>();
        for (const id of visible) {
            const parent = hierarchy.parentOf.get(id);
            if (!previous.has(id) && parent !== undefined) appearing.set(id, byId.get(parent)!);
        }

        const simulated = focus === null ? visible : new Set([...visible].filter((id) => related.has(id)));
        simulation.show(simulated, appearing);
        scheduleDraw();
    }

    /** How close to come to [node]: it and the ring of labels under it fill a good part of the view. */
    function zoomFor(node: LabelMapNode): number {
        const children = hierarchy.childrenOf.get(node.id) ?? [];
        const largestChild = Math.max(0, ...allNodes.filter((n) => children.includes(n.id)).map((n) => n.radius));
        const extent = children.length > 0 ? node.radius + 2 * largestChild + 48 : node.radius * 3;

        return Math.max(0.1, Math.min(4, (0.32 * Math.min(width, height)) / extent));
    }

    let flight = 0;

    /**
     * Moves the camera onto [node] at [targetZoom]: the point in the middle of the view slides
     * over while the zoom changes by the same factor every frame, which reads as an even zoom.
     * It follows the label, which is still being pushed into place by what came out of it.
     */
    function flyTo(node: LabelMapNode, targetZoom: number) {
        cancelAnimationFrame(flight);

        const startZoom = zoom;
        const startX = -cameraX / zoom;
        const startY = -cameraY / zoom;
        const startedAt = performance.now();

        const step = (now: number) => {
            const t = Math.min(1, (now - startedAt) / FLIGHT);
            const eased = t < 0.5 ? 4 * t * t * t : 1 - (-2 * t + 2) ** 3 / 2;

            const z = startZoom * (targetZoom / startZoom) ** eased;
            const x = startX + ((node.x ?? 0) - startX) * eased;
            const y = startY + ((node.y ?? 0) - startY) * eased;

            zoom = z;
            cameraX = -x * z;
            cameraY = -y * z;

            flight = t < 1 ? requestAnimationFrame(step) : 0;
        };
        flight = requestAnimationFrame(step);
    }

    /**
     * Zoomed out far enough, the closer layers give way: the focus lets go, and every label
     * opened at a closer zoom folds away again -- the deepest first, as they were opened.
     */
    function relax(currentZoom: number) {
        let changed = false;

        if (focus !== null && currentZoom < focusedAt * FOLD_BELOW) {
            focus = null;
            changed = true;
        }

        const byZoom = [...openedAt].sort(([, a], [, b]) => b - a);
        for (const [id, at] of byZoom) {
            if (currentZoom >= at * FOLD_BELOW || !expanded.has(id)) continue;

            expanded = toggleLabel(hierarchy, expanded, id);
            changed = true;
        }
        for (const id of openedAt.keys()) if (!expanded.has(id)) openedAt.delete(id);

        if (changed) apply();
    }

    $effect(() => {
        const currentZoom = zoom;
        // Not during a flight: on the way in it passes the zoom that would fold what it opened.
        untrack(() => {
            if (flight === 0) relax(currentZoom);
        });
    });

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

    /**
     * The label the last click was on. The click already set off the flight to it, so by the
     * second click of a double click it may have moved out from under the pointer.
     */
    let clicked: LabelMapNode | null = null;

    function onClick(event: MouseEvent) {
        const start = pressedAt;
        pressedAt = null;
        if (start && Math.hypot(event.clientX - start[0], event.clientY - start[1]) > 4) return;
        // The second click of a double click: that one opens the mails, it does not fold back.
        if (event.detail > 1) return;

        const node = nodeUnder(event);
        clicked = node;

        // Beside every label: nothing is in focus any more.
        if (!node) {
            if (focus !== null) {
                focus = null;
                apply();
            }
            return;
        }

        // The label in focus once more: it lets go and folds away what it brought out.
        if (node.id === focus) {
            if (expanded.has(node.id)) expanded = toggleLabel(hierarchy, expanded, node.id);
            focus = null;
            apply();
            return;
        }

        const targetZoom = zoomFor(node);
        focus = node.id;
        focusedAt = targetZoom;
        if (hierarchy.childrenOf.has(node.id) && !expanded.has(node.id)) {
            expanded = toggleLabel(hierarchy, expanded, node.id);
            openedAt.set(node.id, targetZoom);
        }
        apply();
        flyTo(node, targetZoom);
    }

    function onDoubleClick(event: MouseEvent) {
        const node = clicked ?? nodeUnder(event);
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
        void [zoom, cameraX, cameraY, width, height, hovered, nodes, focus, canvas];
        scheduleDraw();
    });

    onMount(() => {
        let mounted = true;

        simulation.fetchLabels()
            .then(() => {
                if (!mounted) return;

                hierarchy = buildHierarchy(simulation.labels);
                labelsById = new Map(simulation.labels.map((label) => [label.id, label]));
                simulation.calculate(visibleLabels(hierarchy, expanded));
                allNodes = simulation.nodes;
                nodes = simulation.visibleNodes;
                simulation.simulation?.on("tick", scheduleDraw);
            })
            .catch((error) => console.error(error));

        return () => {
            mounted = false;
            simulation.stop();
            cancelAnimationFrame(frame);
            cancelAnimationFrame(flight);
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
