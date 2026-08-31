<script lang="ts">
    import {mount, onDestroy, onMount, unmount} from "svelte";
    import type {PromptSegment, PromptTriggerDefinition, PromptTriggerWindowExports} from "./prompt";
    import type {OvermailPromptViewModel} from "./OvermailPromptViewModel.svelte";
    import EmailSegment from "./EmailSegment.svelte";
    import LabelSegment from "./LabelSegment.svelte";
    import LabelsFindWindow from "./LabelsFindWindow.svelte";
    import EmailsFindWindow from "./EmailsFindWindow.svelte";

    let {
        viewModel,
        triggers = [
            {char: "#", window: LabelsFindWindow},
            {char: "@", window: EmailsFindWindow},
        ],
    }: {
        viewModel: OvermailPromptViewModel;
        triggers?: PromptTriggerDefinition[];
    } = $props();

    let editor: HTMLDivElement;
    let wrapper: HTMLDivElement;

    type ActiveTrigger = {
        definition: PromptTriggerDefinition;
        query: string;
        left: number;
        bottom: number;
        node: Text;
        index: number;
        caretOffset: number;
    };

    let activeTrigger: ActiveTrigger | null = $state(null);
    let triggerWindow: PromptTriggerWindowExports | undefined = $state();

    // Eine Trigger-Session startet nur in dem Moment, in dem das Trigger-Zeichen
    // getippt wird — nicht, wenn der Cursor hinter einem alten Zeichen landet. Sie
    // endet bei Escape, Auswahl, oder sobald der Cursor den Query-Bereich verlässt.
    // Dadurch dürfen Leerzeichen in der Query vorkommen.
    let session: {definition: PromptTriggerDefinition; node: Text; index: number} | null = null;

    // Mounted segment component + model segment per host element.
    const hosts = new Map<HTMLElement, {instance: Record<string, unknown>; segment: PromptSegment}>();

    function unmountAll() {
        for (const {instance} of hosts.values()) unmount(instance);
        hosts.clear();
    }

    function createSegmentHost(segment: Exclude<PromptSegment, {type: "text"}>): HTMLElement {
        const host = document.createElement("div");
        host.contentEditable = "false";
        host.className = "inline";
        host.dataset.segmentType = segment.type;

        const instance = segment.type === "email"
            ? mount(EmailSegment, {target: host, props: {email: segment.email}})
            : mount(LabelSegment, {target: host, props: {label: segment.label}});
        hosts.set(host, {instance, segment});

        return host;
    }

    function renderPrompt() {
        unmountAll();
        editor.replaceChildren(...viewModel.prompt.segments.map((segment) =>
            segment.type === "text"
                ? document.createTextNode(segment.content)
                : createSegmentHost(segment)
        ));
    }

    // DOM is the source of truth while typing; mirror it back into the view model.
    function syncFromDom() {
        const segments: PromptSegment[] = [];

        for (const node of editor.childNodes) {
            if (node instanceof HTMLElement && node.dataset.segmentType) {
                const entry = hosts.get(node);
                if (entry) segments.push(entry.segment);
                continue;
            }

            // Zero-Width-Spaces sind nur Cursor-Anker im DOM, nie Teil des Prompts.
            const text = (node instanceof HTMLBRElement ? "\n" : node.textContent ?? "").replaceAll("\u200B", "");
            if (!text) continue;

            const last = segments[segments.length - 1];
            if (last?.type === "text") {
                last.content += text;
            } else {
                segments.push({type: "text", content: text});
            }
        }

        viewModel.setSegments(segments);
    }

    function setCaret(node: Node, offset: number) {
        const range = document.createRange();
        range.setStart(node, offset);
        range.collapse(true);
        const selection = window.getSelection();
        selection?.removeAllRanges();
        selection?.addRange(range);
    }

    function deleteNonText(element: HTMLElement) {
        const prev = element.previousSibling;
        const next = element.nextSibling;
        const entry = hosts.get(element);
        if (entry) {
            unmount(entry.instance);
            hosts.delete(element);
        }
        element.remove();

        if (prev instanceof Text && next instanceof Text) {
            // Beim Join gleich die Zero-Width-Space-Anker aufräumen.
            const prevText = (prev.textContent ?? "").replaceAll("\u200B", "");
            prev.textContent = prevText + (next.textContent ?? "").replaceAll("\u200B", "");
            next.remove();
            setCaret(prev, prevText.length);
        } else if (prev instanceof Text) {
            setCaret(prev, prev.length);
        } else if (next) {
            setCaret(next, 0);
        } else {
            setCaret(editor, 0);
        }

        syncFromDom();
    }

    function nonTextBeforeCaret(): HTMLElement | null {
        const selection = window.getSelection();
        if (!selection?.isCollapsed || !selection.anchorNode) return null;

        let candidate: Node | null = null;
        if (selection.anchorNode instanceof Text) {
            // Steht vor dem Cursor höchstens ein Zero-Width-Space, zählt das wie Offset 0 —
            // sonst müsste man den unsichtbaren Anker erst "leer" wegtippen.
            const before = (selection.anchorNode.textContent ?? "").slice(0, selection.anchorOffset);
            if (/^\u200B*$/.test(before)) candidate = selection.anchorNode.previousSibling;
        } else if (selection.anchorNode === editor && selection.anchorOffset > 0) {
            candidate = editor.childNodes[selection.anchorOffset - 1];
        }

        return candidate instanceof HTMLElement && candidate.dataset.segmentType ? candidate : null;
    }

    function caretBeside(host: HTMLElement, after: boolean) {
        const sibling = after ? host.nextSibling : host.previousSibling;
        if (sibling instanceof Text) {
            setCaret(sibling, after ? 0 : sibling.length);
            return;
        }

        const range = document.createRange();
        if (after) {
            range.setStartAfter(host);
        } else {
            range.setStartBefore(host);
        }
        range.collapse(true);
        const selection = window.getSelection();
        selection?.removeAllRanges();
        selection?.addRange(range);
    }

    function onMousedown(event: MouseEvent) {
        if (!(event.target instanceof Element)) return;
        const host = event.target.closest("[data-segment-type]");
        if (!(host instanceof HTMLElement) || !editor.contains(host)) return;

        event.preventDefault();
        editor.focus();
        const rect = host.getBoundingClientRect();
        caretBeside(host, event.clientX >= rect.left + rect.width / 2);
    }

    // Startet eine Session, wenn der eben eingefügte Text genau ein Trigger-Zeichen ist.
    function maybeStartSession(event: InputEvent) {
        if (event.inputType !== "insertText") return;
        const definition = triggers.find((trigger) => event.data === trigger.char);
        if (!definition) return;

        const selection = window.getSelection();
        if (!selection?.isCollapsed) return;
        const node = selection.anchorNode;
        if (!(node instanceof Text) || node.parentNode !== editor) return;

        const index = selection.anchorOffset - 1;
        if (index < 0 || (node.textContent ?? "")[index] !== definition.char) return;

        session = {definition, node, index};
    }

    // Validiert die laufende Session gegen die aktuelle Cursor-Position: der Cursor
    // muss im selben Textknoten hinter dem (noch vorhandenen) Trigger-Zeichen stehen.
    function updateTrigger() {
        if (!session) {
            activeTrigger = null;
            return;
        }

        const selection = window.getSelection();
        const node = session.node;
        const valid = selection?.isCollapsed
            && selection.anchorNode === node
            && node.parentNode === editor
            && selection.anchorOffset > session.index
            && (node.textContent ?? "")[session.index] === session.definition.char;
        if (!valid) {
            session = null;
            activeTrigger = null;
            return;
        }

        const caretOffset = selection.anchorOffset;

        const range = document.createRange();
        range.setStart(node, session.index);
        range.setEnd(node, caretOffset);
        const rect = range.getBoundingClientRect();
        const wrapperRect = wrapper.getBoundingClientRect();

        activeTrigger = {
            definition: session.definition,
            query: (node.textContent ?? "").slice(session.index + 1, caretOffset),
            node,
            index: session.index,
            caretOffset,
            left: Math.max(0, rect.left - wrapperRect.left),
            // Über dem gesamten Eingabefeld statt über der Cursor-Zeile,
            // damit das Fenster den Prompt nie überdeckt.
            bottom: wrapperRect.height + 4,
        };
    }

    function dismissTrigger() {
        session = null;
        activeTrigger = null;
    }

    // Ersetzt den Trigger-Text (Zeichen + Query) durch ein Segment. Direkt hinter dem
    // Segment steht danach garantiert ein Textknoten (bestehend oder neu), in dem der
    // Cursor landet — er erscheint also nie im Label und auch zwischen zwei direkt
    // aufeinanderfolgenden Nicht-Text-Segmenten bleibt eine Einfügestelle.
    function replaceTrigger(segment: PromptSegment) {
        const trigger = activeTrigger;
        if (!trigger) return;

        if (segment.type === "text") {
            dismissTrigger();
            return;
        }

        const content = trigger.node.textContent ?? "";
        const remainder = content.slice(trigger.caretOffset);
        trigger.node.textContent = content.slice(0, trigger.index);

        const host = createSegmentHost(segment);
        trigger.node.after(host);

        // In einem komplett leeren Textknoten zeichnet Chrome den Cursor optisch noch
        // im Label davor. Ein Zero-Width-Space gibt ihm eine echte Position dahinter;
        // syncFromDom filtert ihn aus dem Modell wieder heraus.
        let after = host.nextSibling;
        let caretOffset = 0;
        if (remainder !== "" || !(after instanceof Text)) {
            after = document.createTextNode(remainder === "" ? "\u200B" : remainder);
            caretOffset = remainder === "" ? 1 : 0;
            host.after(after);
        }

        session = null;
        activeTrigger = null;
        editor.focus();
        setCaret(after, caretOffset);
        syncFromDom();
    }

    function onKeydown(event: KeyboardEvent) {
        if (activeTrigger) {
            if (event.key === "Escape") {
                event.preventDefault();
                dismissTrigger();
                return;
            }
            if (triggerWindow?.handleKey?.(event)) {
                event.preventDefault();
                return;
            }
        }

        if (event.key === "Enter") {
            // Shift+Enter: Browser fügt <br> ein (insertLineBreak), syncFromDom liest das als \n.
            // Enter ohne Shift bleibt blockiert (kein <div>-Wrapping; Submit kommt später).
            if (!event.shiftKey) event.preventDefault();
            return;
        }

        if (event.key !== "Backspace") return;

        const target = nonTextBeforeCaret();
        if (target) {
            event.preventDefault();
            deleteNonText(target);
        }
    }

    onMount(() => {
        renderPrompt();
        // Deckt Caret-Bewegungen per Tastatur und Maus ab.
        document.addEventListener("selectionchange", updateTrigger);
        return () => document.removeEventListener("selectionchange", updateTrigger);
    });
    onDestroy(unmountAll);

    function onInput(event: Event) {
        syncFromDom();
        if (event instanceof InputEvent) maybeStartSession(event);
        updateTrigger();
    }
</script>

<div bind:this={wrapper} class="relative flex w-full flex-1 min-h-0">
    <div
            bind:this={editor}
            contenteditable="true"
            role="textbox"
            aria-multiline="true"
            tabindex="0"
            data-slot="input-group-control"
            class="w-full flex-1 px-3 py-2.5 text-sm outline-none whitespace-pre-wrap break-words min-h-9 overflow-y-auto"
            onkeydown={onKeydown}
            onmousedown={onMousedown}
            oninput={onInput}
            onscroll={updateTrigger}
    ></div>

    {#if activeTrigger}
        {@const Window = activeTrigger.definition.window}
        <Window
                bind:this={triggerWindow}
                query={activeTrigger.query}
                left={activeTrigger.left}
                bottom={activeTrigger.bottom}
                {viewModel}
                onReplace={replaceTrigger}
                onDismiss={dismissTrigger}
        />
    {/if}
</div>
