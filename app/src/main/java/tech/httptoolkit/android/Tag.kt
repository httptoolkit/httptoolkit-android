package tech.httptoolkit.android

import android.os.Build

fun formatTag(tag: String): String {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        tag
    } else {
        tag.substring(0, 23) // Before API 24 there's a 23 char length limit
    }
}

val Any.TAG: String
    get() {
        val name = if (javaClass.name.startsWith("tech.httptoolkit.android")) {
            javaClass.name
        } else {
            // In some cases (e.g. coroutines) our code runs in the context of
            // other classes - make sure we prefix it for findability regardless.
            "tech.httptoolkit.android ($javaClass.name)"
        }

        return formatTag(name)
    }
