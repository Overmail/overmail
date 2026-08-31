<script lang="ts">
    import {mount, onDestroy, onMount, unmount} from "svelte";
    import type {Prompt, PromptSegment} from "./prompt";
    import EmailSegment from "./EmailSegment.svelte";
    import LabelSegment from "./LabelSegment.svelte";

    let {
        prompt = $bindable(),
    }: {
        prompt: Prompt;
    } = $props();

    let editor: HTMLDivElement;

    // Mounted segment components per host element, for cleanup.
    const instances = new Map<HTMLElement, Record<string, unknown>>();

    function unmountAll() {
        for (const instance of instances.values()) unmount(instance);
        instances.clear();
    }

    function renderPrompt() {
        unmountAll();
        editor.replaceChildren(...prompt.segments.map((segment) => {
            if (segment.type === "text") {
                return document.createTextNode(segment.content);
            }

            const host = document.createElement("div");
            host.contentEditable = "false";
            host.className = "inline";
            host.dataset.segmentType = segment.type;
            host.dataset.id = segment.type === "email" ? segment.emailId : segment.labelId;

            const instance = segment.type === "email"
                ? mount(EmailSegment, {target: host, props: {emailId: segment.emailId}})
                : mount(LabelSegment, {target: host, props: {labelId: segment.labelId}});
            instances.set(host, instance);

            return host;
        }));
    }

    // DOM is the source of truth while typing; mirror it back into the prompt.
    function syncFromDom() {
        const segments: PromptSegment[] = [];

        for (const node of editor.childNodes) {
            if (node instanceof HTMLElement && node.dataset.segmentType) {
                const id = node.dataset.id ?? "";
                segments.push(node.dataset.segmentType === "email"
                    ? {type: "email", emailId: id}
                    : {type: "label", labelId: id});
                continue;
            }

            const text = node.textContent ?? "";
            if (!text) continue;

            const last = segments[segments.length - 1];
            if (last?.type === "text") {
                last.content += text;
            } else {
                segments.push({type: "text", content: text});
            }
        }

        prompt.segments = segments;
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
        const instance = instances.get(element);
        if (instance) {
            unmount(instance);
            instances.delete(element);
        }
        element.remove();

        if (prev instanceof Text && next instanceof Text) {
            const offset = prev.length;
            prev.textContent = prev.textContent + next.textContent;
            next.remove();
            setCaret(prev, offset);
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
        if (selection.anchorNode instanceof Text && selection.anchorOffset === 0) {
            candidate = selection.anchorNode.previousSibling;
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

    function onKeydown(event: KeyboardEvent) {
        if (event.key === "Enter") {
            // Kein Zeilenumbruch-DOM vom Browser; Submit kommt später.
            event.preventDefault();
            return;
        }

        if (event.key !== "Backspace") return;

        const target = nonTextBeforeCaret();
        if (target) {
            event.preventDefault();
            deleteNonText(target);
        }
    }

    onMount(renderPrompt);
    onDestroy(unmountAll);
</script>

<div
        bind:this={editor}
        contenteditable="true"
        role="textbox"
        aria-multiline="true"
        tabindex="0"
        data-slot="input-group-control"
        class="w-full flex-1 px-3 py-2.5 text-sm outline-none whitespace-pre-wrap break-words min-h-9"
        onkeydown={onKeydown}
        onmousedown={onMousedown}
        oninput={syncFromDom}
></div>
