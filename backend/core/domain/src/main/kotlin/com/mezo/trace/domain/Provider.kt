package com.mezo.trace.domain

/**
 * Source providers supported by adapters. Domains add providers without
 * replacing the pipeline (plan §37).
 */
enum class Provider {
    GITHUB,
    RSS,
    ATOM,
    JSON_API,
    WEB_PAGE,
}
