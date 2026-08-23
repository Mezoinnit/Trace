package com.mezo.trace.domain

/**
 * Source trust classification. Deliberately separate from event confidence
 * (plan §2.4): an official source is not guaranteed truth, an unknown source
 * is not automatically false.
 */
enum class TrustClassification {
    FIRST_PARTY,
    OFFICIAL,
    ESTABLISHED,
    COMMUNITY,
    UNKNOWN,
    UNTRUSTED,
}
