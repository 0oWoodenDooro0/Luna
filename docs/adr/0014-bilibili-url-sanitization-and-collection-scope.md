# Bilibili URL Sanitization, Collection/Series Scope, and Honest User-Agent Policy

Status: accepted

## Context

Shared Bilibili links in Discord frequently carry tracking and client-fingerprint parameters missing from `:libs:socialpeek`'s blacklist (`buvid`, `from_spmid`, `plat_id`, `share_from`, `spmid`, `timestamp`, `up_id`), redundant default pagination (`p=1`), inconsistent trailing slashes, and `b23.tv/BV...` short links that skip redirect/canonical expansion.

At the same time, we evaluated extending `BilibiliResolver` to `space.bilibili.com` creator space profiles (`space.bilibili.com/{mid}`) and creator collections/series (`collectiondetail`, `seriesdetail`, `/lists/{sid}`). Probing Bilibili's unauthenticated endpoints with `BilibiliResolver`'s honest `User-Agent` (`SocialPeek/1.0`) showed:
- `x/web-interface/card` (creator profile) and `x/polymer/web-space/seasons_archives_list` return anti-bot error `-352` under `SocialPeek/1.0`, whereas spoofing a desktop Chrome `User-Agent` receives HTTP `200`.
- `x/space/fav/season/list` (collections), `x/series/series` + `x/series/archives` (series), and `x/web-interface/view` (videos) succeed unauthenticated with `code: 0` under `SocialPeek/1.0`.

## Decision

1. **Preserve Honest `SocialPeek/1.0` User-Agent and Exclude Creator Space Root**:
   - Retain `SocialPeek/1.0` in `BilibiliResolver` rather than spoofing a desktop browser `User-Agent`.
   - Explicitly exclude `space.bilibili.com/{mid}/` personal space URLs from `BilibiliResolver` (and consequently from Discord `SocialPreviewService` auto-previewing and `Summary Pipeline` / `Chatter Pipeline` `SOCIAL` routing).

2. **Support Bilibili Collections and Series via Unblocked Public APIs**:
   - Support `space.bilibili.com/{mid}/channel/collectiondetail/`, `seriesdetail/`, and `lists/{sid}/` (`type=season|series`, trying `season` then `series` when `type` is omitted) via `x/space/fav/season/list` and `x/series/series` + `x/series/archives`.
   - Enrich creator display name and avatar via a single follow-up `x/web-interface/view?bvid=...` call on the first item in the list (degrading gracefully without avatar if unavailable).
   - Replace empty covers or Bilibili's default placeholder (`viedeo_material_default.png`) with the first item's cover, and upgrade `http://` image URLs to `https://`.

3. **Route-Scoped Strict Whitelisting, `p=1` Removal, and Trailing-Slash Canonicalization**:
   - Apply strict query-parameter whitelisting and trailing `/` path normalization in `UrlSanitizer` exclusively to supported Bilibili content routes:
     - `/video/.../`: preserve `t` and `p` only when `p != "1"` (stripping redundant `p=1` without flagging standalone `p=1` as a tracking param in `hasTrackingParams`).
     - `/opus/.../` and `t.bilibili.com/.../`: strip all query parameters.
     - `collectiondetail/`, `seriesdetail/`, and `lists/{sid}/`: preserve `sid` and `type`.
   - Normalize `bilibili.com` and `m.bilibili.com` to `www.bilibili.com`.
   - Keep unsupported Bilibili URLs (such as `space.bilibili.com/{mid}` or search URLs) on the existing blacklist + whitelist-protection model (adding the newly observed tracking parameters) without modifying their path trailing slashes.
   - Keep `UrlSanitizer.clean` synchronous and offline (no trailing `/` added to `b23.tv`), while `BilibiliResolver.resolve` expands/converts all `b23.tv/...` links (including `b23.tv/BV...` and `b23.tv/av...`) into canonical `https://www.bilibili.com/.../` URLs.

4. **No Link-Only Fallback in `SocialPreviewService`**:
   - Maintain `SocialPreviewService`'s existing boundary: if `canResolveSocial` is `false` or `peekOrNull` returns `null`, do not execute `Webhook Replacement` or emit clean-link fallback messages.

## Consequences

- **Positive**: Completely eliminates known and future Bilibili share tracking parameters on supported content links without risking functional query loss on unrelated Bilibili pages; avoids browser-UA spoofing while delivering rich collection/series embeds across both Discord previews and AI pipelines.
- **Negative / Trade-offs**: `space.bilibili.com/{mid}` creator profile links remain unsupported (no cleaning or Discord embed); collection/series embeds perform one extra `x/web-interface/view` request to obtain creator avatar and display name.
