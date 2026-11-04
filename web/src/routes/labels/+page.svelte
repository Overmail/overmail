<!--
    The label map: every label on a mail as a circle, pulled towards the labels it shares mails
    with. Double-clicking one opens the listing of its mails.

    It starts with the labels that belong under no other, see `labelHierarchy`. A click on one
    flies to it, brings out the labels under it and fades whatever it has nothing to do with --
    the label is in focus. It stands still, and only the labels it brought out move, arranging
    themselves around it and what it is related to; the faded ones are left out. Zooming back
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
    import {fitCamera, fontSizeFor, nodeAt, screenToWorld, textColorOn} from "$lib/app/label-map/labelMapCanvas";
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

    /**
     * How opaque the name is of a label that has nothing to do with the one in focus. It has no
     * background and no outline then.
     */
    const FADED = 0.35;

    /**
     * How far below the zoom a label was opened at the view has to go before it folds away again,
     * and before the focus lets go of it. Well below, so a little zooming does not undo a click.
     */
    const FOLD_BELOW = 0.5;

    /**
     * How much further in the flight swings halfway, on top of where it is going: what says the
     * view went to the label, also where the zoom it ends at is the zoom it started from.
     */
    const PULSE = 0.08;

    /** The largest a label in focus is brought to by a click: its radius against the view. */
    const FOCUS_MAX = 0.15;

    /**
     * How large a label has to be zoomed to, its radius against the view, for it to take the
     * focus while it covers the middle of the view -- zooming in on a label is going to it too.
     */
    const FOCUS_BY_ZOOM = 0.2;

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

    /** How present each label is right now, on its way to what the focus asks of it; see [draw]. */
    const presences = new Map<string, number>();

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

        // How present each label is: 1 is a filled circle, 0 a faded one -- only its name, in its
        // colour.
        let fading = false;
        const step = elapsed / 250;
        for (const node of nodes) {
            const target = focus === null || related.has(node.id) ? 1 : 0;
            const current = presences.get(node.id) ?? target;
            const next = current < target ? Math.min(target, current + step) : Math.max(target, current - step);
            presences.set(node.id, next);
            if (next !== target) fading = true;
        }
        const presenceOf = (node: LabelMapNode) => presences.get(node.id) ?? 1;
        const alphaOf = (presence: number) => FADED + (1 - FADED) * presence;

        // Faded labels first, so those in focus lie on top of them.
        const ordered = [...nodes].sort((a, b) => presenceOf(a) - presenceOf(b));

        // From each opened label to the labels it brought out, under the circles.
        const visibleById = new Map(nodes.map((node) => [node.id, node]));
        context.lineWidth = 2 / zoom;
        for (const node of ordered) {
            const parent = visibleById.get(hierarchy.parentOf.get(node.id) ?? "");
            if (!parent) continue;

            context.globalAlpha = 0.4 * alphaOf(Math.min(presenceOf(node), presenceOf(parent)));
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
            const presence = presenceOf(node);
            const alpha = alphaOf(presence);

            context.beginPath();
            context.arc(x, y, r, 0, Math.PI * 2);

            // The background goes as the label fades, and only its name is left.
            if (presence > 0) {
                context.globalAlpha = presence;
                context.fillStyle = look.fill;
                context.fill();
            }

            if (node === hovered || node.id === focus) {
                context.lineWidth = 3 / zoom;
                context.strokeStyle = presence > 0.5 ? look.text : look.fill;
                context.globalAlpha = 0.6 * alpha;
                context.stroke();
            }

            // On its background in its own text colour, without one in the label's colour.
            const scale = look.fontSize / BASE_FONT_SIZE;
            context.save();
            context.translate(x, y);
            context.scale(scale, scale);
            if (presence > 0) {
                context.globalAlpha = presence;
                context.fillStyle = look.text;
                context.fillText(node.label.name, 0, 0);
            }
            if (presence < 1) {
                context.globalAlpha = FADED * (1 - presence);
                context.fillStyle = look.fill;
                context.fillText(node.label.name, 0, 0);
            }
            context.restore();

            const folded = expanded.has(node.id) ? 0 : (hierarchy.childrenOf.get(node.id)?.length ?? 0);
            if (folded > 0) {
                context.globalAlpha = alpha;
                drawBadge(context, node, look, folded);
            }
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
     * simulated. With a label in focus only the labels under it move; it and what it is related to
     * hold still for them to arrange around, and the faded labels are not simulated at all -- the
     * labels coming out may lie on top of them.
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

        if (focus === null) {
            simulation.show(visible, appearing);
        } else {
            const moving = new Set(hierarchy.childrenOf.get(focus) ?? []);
            const simulated = new Set([...visible].filter((id) => related.has(id)));
            const pinned = new Set([...simulated].filter((id) => !moving.has(id)));
            simulation.show(simulated, appearing, pinned);
        }
        scheduleDraw();
    }

    /**
     * How close to come to [node]: it, the ring of labels under it and the nearer half of its
     * peers on the map fill the view -- but the label itself grows to no more than [FOCUS_MAX],
     * so going from label to label does not blow each one up further.
     */
    function zoomFor(node: LabelMapNode): number {
        const view = Math.min(width, height);
        const children = new Set(hierarchy.childrenOf.get(node.id) ?? []);
        const largestChild = Math.max(0, ...allNodes.filter((n) => children.has(n.id)).map((n) => n.radius));
        const ring = children.size > 0 ? node.radius + 2 * largestChild + 48 : node.radius * 3;

        const peers = relatedLabels(hierarchy, labelsById, node.id);
        const distances = nodes
            .filter((other) => other !== node && peers.has(other.id) && !children.has(other.id))
            .map((other) => Math.hypot((other.x ?? 0) - (node.x ?? 0), (other.y ?? 0) - (node.y ?? 0)) + other.radius)
            .sort((a, b) => a - b);
        const nearerHalf = distances.length > 0 ? distances[Math.floor((distances.length - 1) / 2)] : 0;

        const fit = (0.45 * view) / Math.max(ring, nearerHalf);
        return Math.max(0.1, Math.min(fit, (FOCUS_MAX * view) / node.radius));
    }

    let flight = 0;

    /**
     * Moves the camera onto [node] at [targetZoom]: the point in the middle of the view slides
     * over while the zoom changes by the same factor every frame, which reads as an even zoom.
     * It runs alongside the simulation pushing the labels under it out.
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

            const z = startZoom * (targetZoom / startZoom) ** eased * (1 + PULSE * Math.sin(Math.PI * t));
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

    /** The zoom the last change came from, to tell zooming in from zooming out. */
    let lastZoom = 1;

    /** Whether the camera still has to be set to the whole map, see [fitCamera]. */
    let needsFit = $state(false);

    // Once the labels are laid out and the view has a size, whichever comes last.
    $effect(() => {
        if (!needsFit || width === 0 || height === 0) return;

        untrack(() => {
            const camera = fitCamera(nodes, width, height);
            // Set first, so the zoom going in is not taken for zooming in on a label.
            lastZoom = camera.zoom;
            zoom = camera.zoom;
            cameraX = camera.cameraX;
            cameraY = camera.cameraY;
            needsFit = false;
        });
    });

    $effect(() => {
        const currentZoom = zoom;
        untrack(() => {
            // Not during a flight: on the way in it passes the zoom that would fold what it opened.
            if (flight === 0) {
                if (currentZoom > lastZoom) focusByZoom(currentZoom);
                else relax(currentZoom);
            }
            lastZoom = currentZoom;
        });
    });

    /**
     * Zoomed in on a label until it is [FOCUS_BY_ZOOM] of the view, with the middle of the view on
     * it: it takes the focus, as a click would, without the flight.
     */
    function focusByZoom(currentZoom: number) {
        const candidates = focus === null ? nodes : nodes.filter((node) => related.has(node.id));
        const node = nodeAt(candidates, -cameraX / currentZoom, -cameraY / currentZoom);
        if (!node || node.id === focus) return;
        if (node.radius * currentZoom < FOCUS_BY_ZOOM * Math.min(width, height)) return;

        focusOn(node, currentZoom);
    }

    /** Puts [node] in focus at [atZoom], opening what is under it, see [apply]. */
    function focusOn(node: LabelMapNode, atZoom: number) {
        focus = node.id;
        focusedAt = atZoom;
        if (hierarchy.childrenOf.has(node.id) && !expanded.has(node.id)) {
            expanded = toggleLabel(hierarchy, expanded, node.id);
            openedAt.set(node.id, atZoom);
        }
        apply();
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
        focusOn(node, targetZoom);
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
                simulation.settle();
                allNodes = simulation.nodes;
                nodes = simulation.visibleNodes;
                simulation.simulation?.on("tick", scheduleDraw);
                needsFit = true;
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

    <!-- What the canvas shows, for whoever does not see it. -->
    <nav class="sr-only" aria-label={$_("labels.map.title")}>
        <ul>
            {#each allNodes as node (node.id)}
                <li><a href={hrefOf(node)}>{node.label.name}</a></li>
            {/each}
        </ul>
    </nav>
</div>
