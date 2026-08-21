package com.mezo.trace.application

/**
 * Marker for the application (use-case) layer.
 *
 * Application services orchestrate domain objects and ports. They must not
 * depend on frameworks, HTTP, databases, or AI providers directly. See
 * ARCHITECTURE.md for the layering rules (ADR-002).
 */
interface TraceApplication