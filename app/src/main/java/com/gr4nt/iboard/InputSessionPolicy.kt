package com.gr4nt.iboard

/** Android may restart the same password editor after each character. That is not a new keyboard session. */
data class EditorSessionKey(val packageName: String?, val fieldId: Int, val fieldName: String?, val inputType: Int, val imeOptions: Int) {
    fun sameField(other: EditorSessionKey?): Boolean = other != null &&
        packageName == other.packageName && fieldId == other.fieldId && fieldName == other.fieldName
}
object InputSessionPolicy {
    fun preserve(restarting: Boolean, previous: EditorSessionKey?, current: EditorSessionKey, viewReady: Boolean): Boolean =
        restarting && viewReady && previous == current
}
