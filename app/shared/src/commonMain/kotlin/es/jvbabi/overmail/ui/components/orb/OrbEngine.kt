package es.jvbabi.overmail.ui.components.orb

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The geometry of thinking-orbs 0.3.1 (MIT, Jakub Antalik, github.com/Jakubantalik/thinking-orbs),
 * ported from its `src/engine` for the three states the assistant shows -- the same ones the web
 * app's `ToolCallGroup` uses. Kept as close to the original as Kotlin allows, so the app's orbs
 * move exactly like the web's; a state the web starts using is ported from there, not invented.
 * `OrbEngineTest` holds it against the package's golden frames.
 *
 * MIT License
 *
 * Copyright (c) 2026 Jakub Antalik
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

/** The states ported so far, named as in the package. */
enum class OrbState { Working, Searching, Breathing }

/** The package ships two tuned designs, not a scale factor: 20 inline, 64 avatar-sized. */
enum class OrbSize(val px: Int) { Inline(20), Avatar(64) }

/** One dot of a frame, in the coordinates of a [OrbSize.px]-wide square. */
internal class OrbDot(
    val x: Double,
    val y: Double,
    val z: Double,
    var r: Double,
    /** Ink value: 0 is the darkest ink on paper, mirrored on a dark theme. */
    val white: Double,
    val a: Double = 1.0,
)

private typealias Opts = Map<String, Double>

internal enum class Mode { Orbits, Globe, Ring }

private class Preset(val speed: Double, val count: Double, val size: Double, val extra: Opts = emptyMap())

internal class ResolvedOrb(private val mode: Mode, val speed: Double, private val opts: Opts) {
    /** The dots of the instant [t], far to near: the order they are drawn in. */
    fun frame(size: Double, t: Double): List<OrbDot> = when (mode) {
        Mode.Orbits -> frameOrbits(size, t, opts)
        Mode.Globe -> frameGlobe(size, t, opts)
        Mode.Ring -> frameRibbon(size, t, opts)
    }
}

private val BASE_PROFILES: Map<Mode, Opts> = mapOf(
    Mode.Globe to mapOf(
        "latRings" to 17.0, "lonDensity" to 44.0, "rBase" to 0.6, "rDepth" to 1.7, "rBoost" to 1.0,
        "inkFar" to 0.62, "inkSpan" to 0.54, "rsPow" to 0.6, "rMin" to 0.3,
    ),
    Mode.Orbits to mapOf(
        "orbitN" to 12.0, "ghostN" to 40.0, "ghostR" to 0.9, "ghostA" to 0.5, "particles" to 3.0,
        "partR" to 1.2, "partRDepth" to 1.6, "rsPow" to 0.6, "rMin" to 0.3,
    ),
    // ring shares ribbon's painter; faceOn cancels the camera tilt and moves the undulation onto
    // the radius, and there is no ghost sphere behind it
    Mode.Ring to mapOf(
        "lanes" to 5.0, "segs" to 88.0, "ghostN" to 0.0, "faceOn" to 1.0, "rBase" to 1.1,
        "rDepth" to 1.7, "rsPow" to 0.6, "rMin" to 0.3,
    ),
)

private val PRESETS: Map<Mode, Map<OrbSize, Preset>> = mapOf(
    Mode.Orbits to mapOf(
        OrbSize.Avatar to Preset(speed = 1.885, count = 1.0, size = 1.0),
        OrbSize.Inline to Preset(speed = 3.9, count = 0.238, size = 2.4),
    ),
    Mode.Globe to mapOf(
        OrbSize.Avatar to Preset(2.015, 0.42, 1.15, mapOf("scanMul" to 4.08, "dimBase" to 0.45)),
        OrbSize.Inline to Preset(2.665, 0.105, 1.75, mapOf("scanMul" to 4.335, "dimBase" to 0.45)),
    ),
    Mode.Ring to mapOf(
        OrbSize.Avatar to Preset(3.24, 0.25, 0.956, mapOf("spin" to 0.0, "bandMul" to 3.627, "wobMul" to 0.368)),
        OrbSize.Inline to Preset(3.78, 0.028, 1.622, mapOf("spin" to 0.0, "bandMul" to 3.968, "wobMul" to 0.565)),
    ),
)

// 2-D lattices come in pairs -- each side takes √scale so the total dot count scales by `scale`;
// flat lists scale linearly.
private val COUNT_PAIRS = listOf("latRings" to "lonDensity", "rings" to "lonDensity", "lanes" to "segs")
private val COUNT_KEYS = listOf("orbitN", "ghostN", "nodeN", "strandN", "signals")
private val RADIUS_KEYS = listOf(
    "rBase", "rDepth", "rActive", "rDot", "ghostR", "partR", "partRDepth", "nodeR", "nodeRDepth",
)

/** JavaScript's `Math.round`: halves go up, never to the even neighbour. */
private fun jsRound(x: Double): Double = floor(x + 0.5)

private fun scaleCounts(opts: Opts, scale: Double): Opts {
    val out = opts.toMutableMap()
    val done = mutableSetOf<String>()
    val rt = sqrt(scale)
    for ((a, b) in COUNT_PAIRS) {
        val va = out[a]
        val vb = out[b]
        if (va != null && vb != null && a !in done && b !in done) {
            out[a] = max(2.0, jsRound(va * rt))
            out[b] = max(2.0, jsRound(vb * rt))
            done += a
            done += b
        }
    }
    for (k in COUNT_KEYS) {
        val v = out[k]
        // 0 means the mode opted out of that layer entirely (ring has no ghost sphere)
        if (v != null && v != 0.0 && k !in done) out[k] = max(1.0, jsRound(v * scale))
    }
    return out
}

private fun scaleRadii(opts: Opts, scale: Double): Opts {
    val out = opts.toMutableMap()
    for (k in RADIUS_KEYS) out[k]?.let { out[k] = it * scale }
    out["rSizeMul"] = (out["rSizeMul"] ?: 1.0) * scale
    return out
}

private val resolved = mutableMapOf<Pair<OrbState, OrbSize>, ResolvedOrb>()

internal fun resolveOrb(state: OrbState, size: OrbSize): ResolvedOrb = resolved.getOrPut(state to size) {
    val mode = when (state) {
        OrbState.Working -> Mode.Orbits
        OrbState.Searching -> Mode.Globe
        OrbState.Breathing -> Mode.Ring
    }
    val preset = PRESETS.getValue(mode).getValue(size)
    var opts = BASE_PROFILES.getValue(mode)
    if (preset.count != 1.0) opts = scaleCounts(opts, preset.count)
    if (preset.size != 1.0) opts = scaleRadii(opts, preset.size)
    ResolvedOrb(mode, preset.speed, opts + preset.extra)
}

// --- core -------------------------------------------------------------

private fun hashD(a: Double, b: Double): Double {
    val h = sin(a * 12.9898 + b * 78.233) * 43758.5453
    return h - floor(h)
}

private fun fibDir(i: Int, n: Int): Triple<Double, Double, Double> {
    val golden = PI * (3 - sqrt(5.0))
    val y = 1 - (2 * (i + 0.5)) / n
    val rad = sqrt(1 - y * y)
    val a = i * golden
    return Triple(rad * cos(a), y, rad * sin(a))
}

private fun angleDelta(a: Double, b: Double): Double = atan2(sin(a - b), cos(a - b))

/** Spin + tilt + orthographic projection. */
private class Projector(yaw: Double, tilt: Double, private val cx: Double, private val cy: Double, private val scale: Double) {
    private val st = sin(tilt)
    private val ct = cos(tilt)
    private val sy = sin(yaw)
    private val cyw = cos(yaw)

    fun project(x: Double, y: Double, z: Double): Triple<Double, Double, Double> {
        val x1 = x * cyw + z * sy
        val z1 = -x * sy + z * cyw
        val y1 = y * ct - z1 * st
        val z2 = y * st + z1 * ct
        return Triple(cx + x1 * scale, cy - y1 * scale, z2)
    }
}

/** Dot radii were tuned for a 300pt frame; sub-linear scaling keeps small spinners legible. */
private fun radiusScale(size: Double, pow: Double): Double = (size / 300).pow(pow)

/** Drops invisible dots, clamps radii to the mode's floor and sorts far to near. */
private fun finalizeFrame(dots: List<OrbDot>, rMin: Double): List<OrbDot> = dots
    .filter { it.a >= 0.02 }
    .onEach { it.r = max(rMin, it.r) }
    .sortedBy { it.z }

// --- orbits: particles on tilted orbits -- working ---------------------

private fun frameOrbits(size: Double, t: Double, o: Opts): List<OrbDot> {
    val cx = size / 2
    val cy = size / 2
    val radius = (size / 2) * 0.82
    val pt = Projector(t * 0.12, 0.3, cx, cy, 1.0)
    val rs = radiusScale(size, o["rsPow"] ?: 0.6)

    val dots = mutableListOf<OrbDot>()
    val orbitN = (o["orbitN"] ?: 12.0).toInt()
    val ghostN = (o["ghostN"] ?: 40.0).toInt()
    val particles = (o["particles"] ?: 3.0).toInt()

    for (orb in 0 until orbitN) {
        val h1 = hashD(orb.toDouble(), 1.7)
        val h2 = hashD(orb.toDouble(), 5.2)
        val h3 = hashD(orb.toDouble(), 8.9)
        val ro = radius * (0.45 + 0.52 * h1)
        val th = h1 * 2 * PI
        val phi = acos(2 * h2 - 1)
        // orbit plane basis (u, v ⟂ normal n)
        val nx = sin(phi) * cos(th)
        val ny = cos(phi)
        val nz = sin(phi) * sin(th)
        var ux = -ny
        var uy = nx
        val uz = 0.0
        val ul = max(1e-6, sqrt(ux * ux + uy * uy))
        ux /= ul
        uy /= ul
        val vx = ny * uz - nz * uy
        val vy = nz * ux - nx * uz
        val vz = nx * uy - ny * ux
        val speed = (0.25 + 0.55 * h3) * (if (h3 > 0.5) 1 else -1)

        fun at(a: Double) = pt.project(
            (ux * cos(a) + vx * sin(a)) * ro,
            (uy * cos(a) + vy * sin(a)) * ro,
            (uz * cos(a) + vz * sin(a)) * ro,
        )

        // ghost path
        for (k in 0 until ghostN) {
            val (px, py, z) = at((k.toDouble() / ghostN) * 2 * PI)
            val depth = (z / ro + 1) / 2
            dots += OrbDot(
                x = px, y = py, z = z,
                r = (o["ghostR"] ?: 0.9) * rs,
                white = 0.72,
                a = (o["ghostA"] ?: 0.5) * (0.4 + 0.6 * depth),
            )
        }
        // the particles doing the work
        for (m in 0 until particles) {
            val (px, py, z) = at(t * speed + (m.toDouble() / particles) * 2 * PI + h2 * 6)
            val depth = (z / ro + 1) / 2
            dots += OrbDot(
                x = px, y = py, z = z,
                r = ((o["partR"] ?: 1.2) + (o["partRDepth"] ?: 1.6) * depth) * rs,
                white = 0.3 - 0.22 * depth,
            )
        }
    }
    return finalizeFrame(dots, o["rMin"] ?: 0.3)
}

// --- globe: lat/long field, a scan meridian sweeps -- searching --------

private fun frameGlobe(size: Double, t: Double, o: Opts): List<OrbDot> {
    val spin = 0.5
    val cx = size / 2
    val cy = size / 2
    val radius = (size / 2) * 0.82
    val tilt = 0.4 + 0.06 * sin(t * 0.35)
    val pt = Projector(t * spin, tilt, cx, cy, radius)
    // scan sweeps relative to the spin; scanMul scales that relative rate
    val scan = t * (spin + (1.7 - spin) * (o["scanMul"] ?: 1.0))
    val rs = radiusScale(size, o["rsPow"] ?: 0.6)
    val dimBase = o["dimBase"] ?: 1.0

    val dots = mutableListOf<OrbDot>()
    val latRings = (o["latRings"] ?: 17.0).toInt()
    val lonDensity = o["lonDensity"] ?: 44.0
    for (li in 0..latRings) {
        val lat = -PI / 2 + (li.toDouble() / latRings) * PI
        val cosLat = cos(lat)
        val sinLat = sin(lat)
        val lonCount = max(1.0, jsRound(abs(cosLat) * lonDensity)).toInt()
        for (lj in 0 until lonCount) {
            val lon = (lj.toDouble() / lonCount) * 2 * PI
            val (px, py, z) = pt.project(cosLat * cos(lon), sinLat, cosLat * sin(lon))
            val depth = (z + 1) / 2
            // the scan: a moving meridian read as a size ripple, not a shine
            val d = angleDelta(lon + t * spin, scan)
            val boost = exp(-(d * d) / 0.18) * max(0.0, z)
            dots += OrbDot(
                x = px, y = py, z = z,
                r = ((o["rBase"] ?: 0.6) + (o["rDepth"] ?: 1.7) * depth + (o["rBoost"] ?: 1.0) * boost) * rs,
                white = (o["inkFar"] ?: 0.62) - (o["inkSpan"] ?: 0.54) * depth,
                // dimBase < 1 fades un-scanned dots so the meridian reads clearly
                a = dimBase + (1 - dimBase) * min(1.0, boost),
            )
        }
    }
    return finalizeFrame(dots, o["rMin"] ?: 0.3)
}

// --- ribbon / ring: a face-on ring slowly morphing -- breathing --------

private fun frameRibbon(size: Double, t: Double, o: Opts): List<OrbDot> {
    val cx = size / 2
    val cy = size / 2
    val radius = (size / 2) * 0.78
    val spin = o["spin"] ?: 1.0
    val camTilt = 0.3
    val pt = Projector(t * 0.1 * spin, camTilt, cx, cy, 1.0)
    val rs = radiusScale(size, o["rsPow"] ?: 0.6)
    val faceOn = (o["faceOn"] ?: 0.0) != 0.0

    val dots = mutableListOf<OrbDot>()
    val ghostN = (o["ghostN"] ?: 150.0).toInt()
    for (i in 0 until ghostN) {
        val (dx, dy, dz) = fibDir(i, ghostN)
        val (px, py, z) = pt.project(dx * radius, dy * radius, dz * radius)
        val depth = (z / radius + 1) / 2
        dots += OrbDot(x = px, y = py, z = z, r = 0.8 * rs, white = 0.78, a = 0.1 + 0.22 * depth)
    }

    // The band plane, precessing (frozen when spin=0); face-on makes it a true circle.
    val ya = t * 0.24 * spin
    val ta = if (faceOn) -camTilt else 0.55 + 0.3 * sin(t * 0.18) * spin
    val ux = cos(ya)
    val uy = 0.0
    val uz = sin(ya)
    val vx = -uz * sin(ta)
    val vy = cos(ta)
    val vz = ux * sin(ta)
    // plane normal n = u × v
    val nx = uy * vz - uz * vy
    val ny = uz * vx - ux * vz
    val nz = ux * vy - uy * vx

    // Radial lobes swell past the radius, so the base radius is pulled in by most of the wobble.
    val wobMul = o["wobMul"] ?: 1.0
    val wobAmp = 0.23 * wobMul
    val baseR = if (faceOn) radius / (1 + 0.85 * wobAmp) else radius

    val baseLanes = o["lanes"] ?: 5.0
    val segs = (o["segs"] ?: 88.0).toInt()
    val lanes = max(1.0, jsRound(baseLanes * (o["bandMul"] ?: 1.0))).toInt()
    for (w in 0 until lanes) {
        val laneOff = (w - (lanes - 1) / 2.0) * 0.075
        val edge = abs(w - (lanes - 1) / 2.0) / max(1.0, (lanes - 1) / 2.0)
        for (k in 0 until segs) {
            val a = (k.toDouble() / segs) * 2 * PI
            // the undulation: two traveling waves along the band
            val wob = (0.16 * sin(a * 3 - t * 1.7 + w * 0.22) + 0.07 * sin(a * 5 + t * 1.1)) * wobMul
            val radial = if (faceOn) 1 + wob else 1.0
            val off = if (faceOn) laneOff else laneOff + wob
            val x = ux * cos(a) + vx * sin(a) + nx * off
            val y = uy * cos(a) + vy * sin(a) + ny * off
            val z = uz * cos(a) + vz * sin(a) + nz * off
            val l = sqrt(x * x + y * y + z * z)
            val rr = baseR * radial
            val (px, py, zr) = pt.project((x / l) * rr, (y / l) * rr, (z / l) * rr)
            val depth = (zr / radius + 1) / 2
            dots += OrbDot(
                x = px, y = py, z = zr,
                r = ((o["rBase"] ?: 1.1) + (o["rDepth"] ?: 1.7) * depth) * (1 - 0.25 * edge) * rs,
                white = 0.52 - 0.44 * depth + 0.18 * edge,
                a = 0.4 + 0.6 * depth,
            )
        }
    }
    return finalizeFrame(dots, o["rMin"] ?: 0.3)
}
