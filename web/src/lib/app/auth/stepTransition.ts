import {getContext, setContext} from "svelte";
import {cubicInOut} from "svelte/easing";
import type {TransitionConfig} from "svelte/transition";

/**
 * The sign-in steps scroll by: the next one comes up from below while the last one leaves upwards,
 * on the same curve and by a short distance, and they crossfade on the way. The leaving one is
 * gone a little before the arriving one is fully there, so the two never read as one jumble.
 * Whoever asked for less motion gets the crossfade alone.
 */
const DURATION = 650;
const SHIFT = "5rem";

/** Opacity over the window [from, to] of the eased progress, so the fades overlap in the middle. */
function fade(t: number, from: number, to: number): number {
    return Math.min(1, Math.max(0, (t - from) / (to - from)));
}

function reducedMotion(): boolean {
    return window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

/**
 * Whether a step has been shown on this page yet. The first one is simply there: the page loading
 * is not a step changing. Per page rather than per module, so coming back to /auth starts still.
 */
const KEY = Symbol("auth-step");

export function initStepTransitions() {
    setContext(KEY, {shown: false});
}

/** Called once by a step as it is created; false only for the first step of the page. */
export function claimStepIntro(): boolean {
    const state = getContext<{ shown: boolean } | undefined>(KEY);
    if (!state) return true;
    const animate = state.shown;
    state.shown = true;
    return animate;
}

export function stepIn(_node: Element, {animate}: { animate: boolean }): TransitionConfig {
    if (!animate) return {duration: 0};
    if (reducedMotion()) return {duration: DURATION / 2, css: (t) => `opacity: ${t};`};
    return {
        duration: DURATION,
        easing: cubicInOut,
        css: (t, u) => `opacity: ${fade(t, 0.35, 1)}; transform: translateY(calc(${u} * ${SHIFT}));`,
    };
}

export function stepOut(node: Element): TransitionConfig {
    // Still in the DOM for the whole transition, but done with: out of the tab order, and the
    // focus it holds is let go so the arriving step can take it.
    (node as HTMLElement).inert = true;
    if (reducedMotion()) return {duration: DURATION / 2, css: (t) => `opacity: ${t};`};
    return {
        duration: DURATION,
        easing: cubicInOut,
        css: (t, u) => `opacity: ${fade(t, 0.4, 1)}; transform: translateY(calc(${-u} * ${SHIFT}));`,
    };
}
