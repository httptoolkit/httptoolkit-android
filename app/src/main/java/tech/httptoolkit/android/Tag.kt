package tech.httptoolkit.android

import android.os.Build

fun formatTag(tag: String): String {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        if (tag.startsWith("tech.httptoolkit.android")) {
            tag
        } else {
            // In some cases (e.g. coroutines) our code runs in the context of
            // other classes - make sure we prefix it for findability regardless.
            "tech.httptoolkit.android ($tag)"
        }
    } else {
        // Before API 24 there's a 23 char length limit we need to stay under:
        (
            if (tag.startsWith("tech.httptoolkit.android")) {
                tag.replace("tech.httptoolkit.android", "tech.httptoolkit...")
            } else {
                // In some cases (e.g. coroutines) our code runs in the context of
                // other classes - make sure we prefix it for findability regardless.
                "tech.httptoolkit... ($tag)"
            }
        ).substring(0, 23)
    }
}

val Any.TAG: String
    get() {
        return formatTag(javaClass.name)
    }
