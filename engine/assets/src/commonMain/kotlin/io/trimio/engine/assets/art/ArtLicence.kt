package io.trimio.engine.assets.art

/**
 * The terms an animated picture (or any fetched element) comes under, and whether Trimio may put
 * it into a creator's exported video — commercially, without imposing terms on the video itself.
 *
 * Allowed: public domain and CC0, permissive software licences (MIT, ISC, Apache-2.0, BSD), and
 * CC BY (with the credit carried in the edit's credits). Refused: ShareAlike (it could bind the
 * creator's whole video), NonCommercial, NoDerivatives, "free for personal use", and anything
 * unknown — an unknown licence is never guessed to be fine.
 */
data class ArtLicence(
    /** SPDX identifier when there is one ("CC-BY-4.0", "MIT", "CC0-1.0"); otherwise the name as given. */
    val id: String,
    val allowed: Boolean,
    /** A credit must travel with the video (CC BY). */
    val attribution: Boolean,
) {
    companion object {
        /**
         * Classifies a licence as sources write it: SPDX ids ("CC-BY-4.0"), Iconify titles ("MIT"),
         * Wikimedia Commons short names ("CC BY-SA 4.0", "Public domain", "CC0").
         */
        fun of(name: String?): ArtLicence {
            val raw = name?.trim().orEmpty()
            val k = raw.lowercase().replace('_', '-').replace(' ', '-')
            return when {
                k.isEmpty() -> ArtLicence("unknown", allowed = false, attribution = false)
                REFUSED.any { it in k } -> ArtLicence(raw, allowed = false, attribution = false)
                k.startsWith("cc0") || k == "pd" || k.startsWith("public-domain") || k == "pdm" -> ArtLicence(raw, allowed = true, attribution = false)
                k.startsWith("cc-by-") || k == "cc-by" -> ArtLicence(raw, allowed = true, attribution = true)
                PERMISSIVE.any { k == it || k.startsWith("$it-") } -> ArtLicence(raw, allowed = true, attribution = false)
                else -> ArtLicence(raw, allowed = false, attribution = false)
            }
        }

        /** ShareAlike, NonCommercial, NoDerivatives and personal-only terms. */
        private val REFUSED = listOf("-sa", "sa-", "-nc", "nc-", "-nd", "nd-", "gpl", "personal", "non-commercial", "noncommercial", "proprietary", "all-rights")
        private val PERMISSIVE = listOf("mit", "isc", "apache-2.0", "apache", "bsd-2-clause", "bsd-3-clause", "bsd", "unlicense", "0bsd") + IMPORTED

        /**
         * Pictures the creator brought in themselves (their own files, or LottieFiles animations under the
         * Lottie Simple License — commercial use, no attribution — fetched with their own key): local packs only.
         */
        private val IMPORTED = listOf("licenseref-owner", "lottie-simple-license")
    }
}
