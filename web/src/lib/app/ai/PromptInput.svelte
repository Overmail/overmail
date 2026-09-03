<script lang="ts">
    import {mount, onDestroy, onMount, unmount} from "svelte";
    import type {PromptSegment, PromptTriggerDefinition, PromptTriggerWindowExports} from "./prompt";
    import type {OvermailPromptViewModel} from "./OvermailPromptViewModel.svelte";
    import EmailSegment from "./EmailSegment.svelte";
    import LabelSegment from "./LabelSegment.svelte";
    import SenderSegment from "./SenderSegment.svelte";
    import LabelsFindWindow from "./LabelsFindWindow.svelte";
    import EmailsFindWindow from "./EmailsFindWindow.svelte";
    import SendersFindWindow from "./SendersFindWindow.svelte";

    let {
        viewModel,
        triggers = [
            {char: "#", window: LabelsFindWindow},
            {char: "@", window: EmailsFindWindow},
            {char: ":", window: SendersFindWindow},
        ],
        onPromptSubmit,
    }: {
        viewModel: OvermailPromptViewModel;
        triggers?: PromptTriggerDefinition[];
        onPromptSubmit: () => void;
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

    // A trigger session only starts in the moment the trigger character is typed, not when the
    // cursor happens to land behind an old one. It ends on Escape, on a selection, or as soon as
    // the cursor leaves the query range. That is what lets the query contain spaces.
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
            : segment.type === "label"
                ? mount(LabelSegment, {target: host, props: {label: segment.label}})
                : mount(SenderSegment, {target: host, props: {sender: segment.sender}});
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

            const text = node instanceof HTMLBRElement ? "\n" : node.textContent ?? "";
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
            const prevText = prev.textContent ?? "";
            prev.textContent = prevText + (next.textContent ?? "");
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
            if (selection.anchorOffset === 0) candidate = selection.anchorNode.previousSibling;
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

    // Puts the cursor at the end of the prompt. For clicks on areas that show the text cursor
    // but are not editable themselves, such as the bar below the editor.
    export function focusEnd() {
        editor.focus();

        // Via a range rather than setCaret: that also covers the case where a chip, not a text
        // node, sits at the end.
        const range = document.createRange();
        range.selectNodeContents(editor);
        range.collapse(false);
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

    // A character only counts as a trigger at the start of a text run or directly after
    // whitespace -- otherwise every "foo:bar", "12:30" or "a@b.tld" would open a window.
    function isTriggerPosition(node: Text, index: number, char: string): boolean {
        const content = node.textContent ?? "";
        if (content[index] !== char) return false;

        const before = content.slice(0, index);
        return before === "" || /\s$/.test(before);
    }

    // Starts a session when the text just inserted is exactly one trigger character.
    function maybeStartSession(event: InputEvent) {
        if (event.inputType !== "insertText") return;
        const definition = triggers.find((trigger) => event.data === trigger.char);
        if (!definition) return;

        const selection = window.getSelection();
        if (!selection?.isCollapsed) return;
        const node = selection.anchorNode;
        if (!(node instanceof Text) || node.parentNode !== editor) return;

        const index = selection.anchorOffset - 1;
        if (index < 0 || !isTriggerPosition(node, index, definition.char)) return;

        session = {definition, node, index};
    }

    // Validates the running session against the current cursor position: the cursor has to sit
    // in the same text node behind the trigger character, and that character has to still be in
    // a trigger position -- typing in front of it ends the session.
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
            && isTriggerPosition(node, session.index, session.definition.char);
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
            // Above the whole input rather than above the cursor's line, so the window never
            // covers the prompt.
            bottom: wrapperRect.height + 4,
        };
    }

    function dismissTrigger() {
        session = null;
        activeTrigger = null;
    }

    // Replaces the trigger text (character + query) with a segment. A text node -- existing or
    // new -- is guaranteed to follow the segment, and that is where the cursor lands: it never
    // shows up inside the label, and two adjacent non-text segments keep an insertion point
    // between them.
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

        // There is always a space behind the chip: it separates it from the next word, makes the
        // following character a valid trigger position, and gives the cursor a real position
        // behind the chip -- in an empty text node Chrome would otherwise still paint it inside
        // the chip in front. If whitespace already followed the cursor, it stays at that one.
        const after = document.createTextNode(/^\s/.test(remainder) ? remainder : ` ${remainder}`);
        host.after(after);

        session = null;
        activeTrigger = null;
        editor.focus();
        setCaret(after, 1);
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
            // Shift+Enter: the browser inserts a <br> (insertLineBreak), which syncFromDom reads
            // as \n. Plain Enter stays blocked, so the browser cannot wrap lines in <div>s.
            if (!event.shiftKey) {
                event.preventDefault();
                if (!viewModel.isEmpty) onPromptSubmit();
            }
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
        // Covers caret moves by keyboard and by mouse.
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
