package to.pubky.maps.map

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The size (dp) of the base style's own icon inside a highlight's 24 dp ring (its inside is 20 dp),
 * for a sprite whose icons have their own background (OpenFreeMap's: a coloured glyph in a light disc).
 */
const val SPRITE_ICON_DP = 17.0

/** The same for a sprite of SDF icons (VersaTiles': bare glyphs the map colours, here in the Pubky teal). */
const val SDF_ICON_DP = 14.0

/**
 * How a base style draws the icon of the points of interest of one of its sources: [image] is the
 * name of a tile feature's sprite image as the style's own symbol layers on that source layer give
 * it (the first whose filter takes the feature), "" when none does; [known] are the images its
 * sprites hold, and [size] the icon size that draws their typical icon at [dp]: [SPRITE_ICON_DP], or
 * [SDF_ICON_DP] for a sprite of SDF icons.
 */
data class PoiIcon(
    val image: JsonElement,
    val known: List<String>,
    val size: Float,
    val dp: Float,
)

/** A sprite of a style: the [url] its index and sheet are named after and the [prefix] its images take in the style (`id:`). */
data class SpriteRef(
    val prefix: String,
    val url: String,
)

/** An image of a sprite's index. */
data class SpriteImage(
    val width: Double,
    val height: Double,
    val pixelRatio: Double,
    val sdf: Boolean,
)

/**
 * The sprites of [style], their URLs resolved against [styleUrl] (OpenFreeMap names one, VersaTiles
 * a list with ids). An image of a listed sprite is named `id:name` in the style, but for the id
 * `default`. A `mapbox://` sprite is left out: it needs that service's key.
 */
fun spriteRefsOf(
    style: JsonObject,
    styleUrl: String,
): List<SpriteRef> {
    fun resolved(url: String): String? =
        if (url.startsWith("mapbox://")) {
            null
        } else {
            try {
                URLBuilder(styleUrl).takeFrom(url).buildString()
            } catch (_: Exception) {
                null
            }
        }
    return when (val sprite = style["sprite"]) {
        is JsonPrimitive -> {
            listOfNotNull(sprite.contentOrNull?.let(::resolved)?.let { SpriteRef("", it) })
        }

        is JsonArray -> {
            sprite.mapNotNull { entry ->
                val obj = entry as? JsonObject ?: return@mapNotNull null
                val id = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val url = (obj["url"] as? JsonPrimitive)?.contentOrNull?.let(::resolved) ?: return@mapNotNull null
                SpriteRef(if (id == "default") "" else "$id:", url)
            }
        }

        else -> {
            emptyList()
        }
    }
}

/** The URL of the index of the sprite at [spriteUrl] (`.json` before any query, as MapLibre asks for it). */
fun spriteIndexUrl(spriteUrl: String): String {
    val query = spriteUrl.indexOf('?')
    return if (query < 0) "$spriteUrl.json" else spriteUrl.substring(0, query) + ".json" + spriteUrl.substring(query)
}

/** The images of a sprite index, by name; an entry without a size is left out. */
fun parseSpriteIndex(text: String): Map<String, SpriteImage> {
    val index = Json.parseToJsonElement(text) as? JsonObject ?: return emptyMap()
    return index
        .mapNotNull { (name, value) ->
            val obj = value as? JsonObject ?: return@mapNotNull null
            val width = (obj["width"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val height = (obj["height"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val ratio = (obj["pixelRatio"] as? JsonPrimitive)?.doubleOrNull ?: 1.0
            val sdf = (obj["sdf"] as? JsonPrimitive)?.booleanOrNull ?: false
            name to SpriteImage(width, height, ratio.takeIf { it > 0 } ?: 1.0, sdf)
        }.toMap()
}

/**
 * The icons [style] gives the points of interest of each of [sources], from its symbol layers on
 * that source layer, in the style's order, and its sprites' images ([images], by their name in the
 * style). A layer whose icon or filter the highlight cannot take over is left out: an old-style
 * filter or function, an icon that depends on the zoom or on a feature's state (allowed in its own
 * layer, not inside the highlight's choice), one made with `image`. A source with no layer left, or
 * a style with no images, has none: its highlights keep the kind's badge. The layers' filters and
 * icons are joined in one `case`, where one that fails on a feature would fail the whole choice and
 * leave the place unmarked (neither ring nor badge); so their order comparisons are guarded
 * ([guardedComparisons]), the error a style's filter meets on a feature without a property it
 * compares (`[">=", ["get", "rank"], 20]`). Other failing expressions (a `to-number` of a word, a
 * type assertion) stay unguarded: the default styles have none.
 */
fun poiIconsOf(
    style: JsonObject,
    sources: List<PoiSource>,
    images: Map<String, SpriteImage>,
): Map<PoiSource, PoiIcon> {
    if (images.isEmpty()) return emptyMap()
    val layers = (style["layers"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    val sdf = images.values.count { it.sdf } * 2 > images.size
    val typical =
        images.values
            .map { maxOf(it.width, it.height) / it.pixelRatio }
            .sorted()
            .let { it[it.size / 2] }
    if (typical <= 0) return emptyMap()
    val dp = if (sdf) SDF_ICON_DP else SPRITE_ICON_DP
    val size = (dp / typical).toFloat()
    val known = images.keys.sorted()
    return sources
        .mapNotNull { poiSource ->
            val choices =
                layers
                    .filter {
                        it.string("type") == "symbol" &&
                            it.string("source") == poiSource.source &&
                            it.string("source-layer") == poiSource.sourceLayer
                    }.mapNotNull { layer ->
                        val icon = (layer["layout"] as? JsonObject)?.get("icon-image")?.let(::iconImageExpression)
                        val filter = layer["filter"] ?: JsonPrimitive(true)
                        if (icon == null || !isExpressionFilter(filter) || filter.uses(LAYOUT_UNSAFE)) {
                            null
                        } else {
                            guardedComparisons(filter) to guardedComparisons(icon)
                        }
                    }
            if (choices.isEmpty()) return@mapNotNull null
            val image =
                if (choices.size == 1 && choices[0].first == JsonPrimitive(true)) {
                    choices[0].second
                } else {
                    expr("case", *choices.flatMap { listOf(it.first, it.second) }.toTypedArray(), "")
                }
            poiSource to PoiIcon(image, known, size, dp.toFloat())
        }.toMap()
}

/** The order comparisons MapLibre fails on (not false) when the two sides are not both numbers or both strings. */
private val ORDER_COMPARISONS = setOf("<", "<=", ">", ">=")

/**
 * [expression] with each of its order comparisons false, not failing, when its sides are not both
 * numbers or both strings (both strings with a collator): `[op, a, b]` becomes
 * `["all", <a and b of one such type>, [op, a, b]]` (`all` stops at the first false). A `literal`
 * and the labels of a `match` are left as they are.
 */
internal fun guardedComparisons(expression: JsonElement): JsonElement {
    if (expression !is JsonArray || expression.isEmpty()) return expression
    val op = (expression[0] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (op == "literal") return expression

    // A match's labels sit at 2, 4, .. before its fallback; they are values, not expressions.
    fun isLabel(i: Int) = op == "match" && i >= 2 && i % 2 == 0 && i < expression.size - 1
    val inner = JsonArray(expression.mapIndexed { i, it -> if (i == 0 || isLabel(i)) it else guardedComparisons(it) })
    if (op !in ORDER_COMPARISONS || inner.size !in 3..4) return inner
    val left = expr("typeof", inner[1])
    val sameType = expr("==", left, expr("typeof", inner[2]))
    val types = if (inner.size == 4) listOf("string") else listOf("number", "string")
    val comparable = expr("in", left, expr("literal", JsonArray(types.map(::JsonPrimitive))))
    return expr("all", sameType, comparable, inner)
}

/** A style expression: [op] called with [args] (a string, number or boolean as a literal of its own). */
internal fun expr(
    op: String,
    vararg args: Any,
): JsonArray =
    JsonArray(
        listOf(JsonPrimitive(op)) +
            args.map {
                when (it) {
                    is JsonElement -> it
                    is String -> JsonPrimitive(it)
                    is Number -> JsonPrimitive(it)
                    is Boolean -> JsonPrimitive(it)
                    else -> error("not an expression: $it")
                }
            },
    )

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** Operators the highlight's own choice of icon cannot hold. */
private val LAYOUT_UNSAFE = setOf("zoom", "feature-state", "image")

/** Whether this expression calls one of [ops] anywhere. */
private fun JsonElement.uses(ops: Set<String>): Boolean =
    this is JsonArray &&
        (((firstOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.content in ops) || any { it.uses(ops) })

/**
 * A layer's `icon-image` as an expression of the image's name: an expression as it is, a name with
 * `{property}` tokens as the `concat` MapLibre makes of it; null for an old-style function or an
 * icon the highlight cannot take over ([LAYOUT_UNSAFE]).
 */
internal fun iconImageExpression(value: JsonElement): JsonElement? {
    when (value) {
        is JsonArray -> {
            return value.takeUnless { it.isEmpty() || it.uses(LAYOUT_UNSAFE) }
        }

        is JsonPrimitive -> {
            if (!value.isString) return null
            val text = value.content
            if ('{' !in text) return value
            val parts = ArrayList<JsonElement>()
            var rest = text
            while (rest.isNotEmpty()) {
                val open = rest.indexOf('{')
                val close = if (open < 0) -1 else rest.indexOf('}', open)
                if (open < 0 || close < 0) {
                    parts += JsonPrimitive(rest)
                    break
                }
                if (open > 0) parts += JsonPrimitive(rest.substring(0, open))
                parts.add(expr("coalesce", expr("to-string", expr("get", rest.substring(open + 1, close))), ""))
                rest = rest.substring(close + 1)
            }
            return parts.singleOrNull()?.takeIf { it is JsonPrimitive } ?: expr("concat", *parts.toTypedArray())
        }

        else -> {
            return null
        }
    }
}

/**
 * Whether [filter] is an expression, not an old-style filter (MapLibre's `isExpressionFilter`): an
 * old-style one cannot be used inside another expression.
 */
internal fun isExpressionFilter(filter: JsonElement): Boolean {
    if (filter is JsonPrimitive) return filter.booleanOrNull != null
    if (filter !is JsonArray || filter.isEmpty()) return false
    val op = (filter[0] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false

    fun stringAt(i: Int) = (filter.getOrNull(i) as? JsonPrimitive)?.isString == true
    return when (op) {
        "has" -> filter.size >= 2 && (filter[1] as? JsonPrimitive)?.contentOrNull !in setOf("\$id", "\$type")
        "in" -> filter.size >= 3 && (!stringAt(1) || filter[2] is JsonArray)
        "!in", "!has", "none" -> false
        "==", "!=", ">", ">=", "<", "<=" -> filter.size != 3 || filter[1] is JsonArray || filter[2] is JsonArray
        "any", "all" -> filter.drop(1).all { isExpressionFilter(it) }
        else -> true
    }
}

/**
 * The base style's icons for its points of interest ([poiIconsOf]), read from its style document
 * and the indexes of its sprites, the same files MapLibre loads for the map (read and parsed off
 * the main thread). Kept for the process, by style URL; a style whose files could not be read is not kept,
 * so the next ask reads them again.
 */
class StyleIcons(
    private val client: HttpClient,
) {
    private val mutex = Mutex()
    private val read = HashMap<String, Map<PoiSource, PoiIcon>>()

    /** The icons of [sources] of the style at [styleUrl] (empty when it has none), or null when its files could not be read. */
    suspend fun of(
        styleUrl: String,
        sources: List<PoiSource>,
    ): Map<PoiSource, PoiIcon>? {
        // Not held over the network: two maps asking at once read the files twice at worst.
        val icons = mutex.withLock { read[styleUrl] } ?: load(styleUrl, sources)?.also { mutex.withLock { read[styleUrl] = it } }
        return icons?.filterKeys { it in sources }
    }

    private suspend fun load(
        styleUrl: String,
        sources: List<PoiSource>,
    ): Map<PoiSource, PoiIcon>? =
        try {
            // Off the main thread as a whole: a style document is up to a few hundred kB to decode and parse.
            withContext(Dispatchers.Default) {
                val style = text(styleUrl)?.let { Json.parseToJsonElement(it).jsonObject } ?: return@withContext null
                val images = HashMap<String, SpriteImage>()
                for (sprite in spriteRefsOf(style, styleUrl)) {
                    val index = text(spriteIndexUrl(sprite.url)) ?: return@withContext null
                    for ((name, image) in parseSpriteIndex(index)) images[sprite.prefix + name] = image
                }
                poiIconsOf(style, sources, images)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // No connection, a broken document: the badges stay, the next style load asks again.
            null
        }

    private suspend fun text(url: String): String? {
        val response = client.get(url)
        return if (response.status.isSuccess()) response.bodyAsText() else null
    }
}
