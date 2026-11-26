package to.pubky.maps.account

import to.pubky.maps.write.placeText

/**
 * How the account page names a place: the name the app stored for it ([names], keyed as [normalize]
 * spells places), else its link in words. [place] is spelled as the homeserver has it, which another
 * app may have written differently (`http://`, no `www.`, a trailing `/`).
 */
fun accountPlaceLabel(
    place: String,
    names: Map<String, String>,
    normalize: (String) -> String,
): String {
    val key = normalize(place)
    return names[key] ?: placeText(key)
}
