package tech.httptoolkit.android

import android.text.InputFilter
import android.text.Spanned

class MinMaxInputFilter(private val min: Int, private val max: Int) : InputFilter {

    override fun filter(
        source: CharSequence,
        start: Int,
        end: Int,
        dest: Spanned,
        dstart: Int,
        dend: Int
    ): CharSequence? {
        try {
            val replacement = source.subSequence(start, end).toString()
            val updatedValue = dest.subSequence(0, dstart).toString() +
                    replacement +
                    dest.subSequence(dend, dest.length)
            if (updatedValue.toInt() in min..max) {
                return null // Allow the update
            }
        } catch (nfe: NumberFormatException) { }

        return "" // Reject the update
    }

}
