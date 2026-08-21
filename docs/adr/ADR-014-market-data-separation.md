# ADR-014: Market Data Separation

**Status:** Accepted  
**Date:** 2026-08-21

## Context

Markets are not part of V1, but the product roadmap includes market
intelligence (e.g. "what major events occurred around NVIDIA's last price
movements?"). The event model must accommodate this later without redesign.

## Decision

- **Market Data** is modeled separately from **Intelligence**:
  - Market Data: instrument, price, OHLC, volume, market snapshot, corporate
    action.
  - Intelligence: news, filings, earnings, announcements, events.
- Events may reference instruments (entities of type `STOCK`,
  `CRYPTO_ASSET`, with identifiers like `TICKER`/`ISIN`/`CUSIP`) without the
  market data being part of the core pipeline.
- Correlation (market movement ↔ events) is a later capability built on top of
  the existing event/entity model.

## Consequences

- The core engine stays market-agnostic; V1 is not slowed by market plumbing.
- Adding Phase 16 later requires new adapters and entity types but no redesign
  of events or timelines.

References: plan §36, DATA_MODEL.md (entity_identifiers).