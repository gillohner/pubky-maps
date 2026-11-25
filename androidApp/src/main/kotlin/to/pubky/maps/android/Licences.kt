package to.pubky.maps.android

import android.content.res.Resources
import to.pubky.maps.R
import to.pubky.maps.settings.mergeLicenceLists

/**
 * The open-source licence list: the libraries the AboutLibraries plugin found, and the Rust crates
 * of the core (scripts/rust-licences.py), both generated at build time. Blocking: call it off the
 * main thread.
 */
internal fun readLicenceList(resources: Resources): String {
    val read = { id: Int -> resources.openRawResource(id).bufferedReader().use { it.readText() } }
    return mergeLicenceLists(read(R.raw.aboutlibraries), read(R.raw.rust_licences))
}
