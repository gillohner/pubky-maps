package to.pubky.maps.account

/**
 * How a person reads a public key: `pubky<z32>` (the SDK's display form), shortened to its first and
 * last four characters, e.g. `pubky8pin…mfye`.
 */
fun shortPubkyId(publicKey: String): String =
    if (publicKey.length <= 12) "pubky$publicKey" else "pubky${publicKey.take(4)}…${publicKey.takeLast(4)}"
