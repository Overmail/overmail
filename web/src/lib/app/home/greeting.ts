export type Greeting = "night" | "morning" | "day" | "evening";

/**
 * Which greeting fits the time on the clock: night until 5, morning until 11, day until 18,
 * evening until 23, night again after that.
 *
 * Local time, deliberately -- the greeting is about the hour the reader is having.
 */
export function greetingFor(now: Date): Greeting {
    const hour = now.getHours();
    if (hour < 5) return "night";
    if (hour < 11) return "morning";
    if (hour < 18) return "day";
    if (hour <= 22) return "evening";
    return "night";
}
