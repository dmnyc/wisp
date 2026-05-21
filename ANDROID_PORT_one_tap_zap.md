# Android port: `feat/one-tap-zap`

A guide to port the iOS `feat/one-tap-zap` branch to Wisp Android, written
for a separate agent picking it up. Mirror the user-facing behavior and the
on-disk data formats; SwiftUI-specific implementation notes are flagged so
the Android side can pick the right Compose equivalent.

## Scope

Seven commits, grouped:

1. **NIP-78 cross-device sync of UI prefs** — already may have an Android
   equivalent or partial; verify against `feat/nip78-settings-sync` on
   Android side.
2. **Instant zaps + fiat counterpart "Instant payments"** — new
   `quickZap*` settings, payload fields, post-card tap behavior.
3. **Wallet setup primary Spark button + More options accordion** — two
   screens redesigned for one-obvious-next-step.
4. **ZapSheet redesign** — full layout rewrite.
5. **Tap composer / long-press instant / disable self-zap** — gesture
   swap on the post-card zap button.
6. **White-core glow pulse for in-flight bolt** — new pulse animation.
7. **DEBUG-only developer panel** — empty scaffold for future
   throwaway experiments.

## Settings — Quick zaps

iOS adds four `AppSettings` properties, each persisted to UserDefaults
and round-tripped through the NIP-78 backup payload:

| Key | Type | Default | Notes |
|---|---|---|---|
| `wisp_settings_quick_zap_enabled` | Bool | `false` | Master toggle |
| `wisp_settings_quick_zap_amount_sats` | Int64 | `100` | Used in non-fiat mode |
| `wisp_settings_quick_zap_amount_fiat` | Double | `0.10` | Major-unit (e.g. dollars), converted to sats via exchange-rate cache at fire time |
| `wisp_settings_quick_zap_message` | String | `""` | Optional default zap message |

NIP-78 `AppSettingsPayload` carries all four:
`quickZapEnabled`, `quickZapAmountSats`, `quickZapAmountFiat`,
`quickZapMessage`. Mirror the JSON keys exactly so the iOS and Android
backups are bit-compatible.

`InterfaceSettingsView` section "Zaps" / "Payments":
- Toggle: "Instant zaps" / "Instant payments" (label flips with fiat mode)
- Below the toggle (gated on `quickZapEnabled`): amount field, message
  field, helper text explaining the tap behavior.
- **Amount clamping** — sats field clamps `min(10_000, max(1, value))`;
  fiat field clamps to the 10K-sats equivalent via the cached rate. Hard
  cap because instant zaps shouldn't bypass the soft confirmation.

## Settings — NIP-78 sync

Adds `wisp_settings_sync_settings_to_relays` (Bool, default `true`).
Toggle in the "Cross-device sync" section explains the publish. When on,
mutations to any synced field schedule a debounced (4 s) kind-30078
publish via the same backup encryption used by quick-reactions.

Synced fields (full payload): `zapIconStyle`, `fiatModeEnabled`,
`fiatCurrency`, `zapPresetsCSV`, `largeText`, `themeName`, `colorScheme`,
`accentColorARGB`, `autoLoadMedia`, `videoAutoplay`, `animateAvatars`,
`mediaLayoutStyle`, `clientTagEnabled`, `postUndoTimerEnabled`,
`postUndoTimerSeconds`, `postUndoTimerForReplies`, plus the four quick-zap
fields. Restore is non-destructive: each field has its own `if let` guard
so missing values stay at the local default.

## Wallet setup screens

### Mode picker

Two rows: Spark (primary) and Nostr Wallet Connect (peer).

* Spark is a full-bleed orange (`wispZapColor`) filled card with white
  text + key icon, layered shadow glow (two stacked: tight 55% / wide 35%
  outer, both in zap-color).
* NWC keeps its dark surface treatment.
* Don't bury NWC under "More options" — keep it visible.

### Spark setup picker

* "Use my default wallet" rendered with the same primary treatment
  (filled orange, white text, glow shadow stack).
* Create new wallet / Restore from seed phrase / Restore from relays
  collapse under a "More options" disclosure that rotates a chevron and
  fades the rows in / out.
* Pick section is **vertically centered** in the available viewport
  (iOS uses a GeometryReader-backed ScrollView with leading + trailing
  Spacers stretching the inner column to `minHeight: geo.size.height`).
  Android equivalent: `Box` filling parent with `verticalArrangement =
  Center` once the column fits the viewport; fall back to scroll on
  smaller phones.

## ZapSheet redesign

The whole composer was rewritten. Top-to-bottom layout, with the keyboard
auto-focused on the amount field and the sheet wrapped in a scrollable
container so dragging dismisses the keyboard naturally.

### Layout (top → bottom)

1. **Toolbar** — `Close` left, `Presets` right (orange tint).
2. **Recipient row** — compact `HStack`:
   * 32pt avatar (left)
   * Display name + lud16 (caption monospace, secondary color)
   * Copy-icon button right (single button, **not** a menu; copying the
     lud16 fires a local pill `"Lightning address copied"`).
3. **Hero amount** — 56pt rounded-bold zap-color number, optional
   "sats" caption hidden in fiat mode. Tappable: tap → focus the
   hidden amount field, seed `customAmountText` with the register-style
   cents digits (fiat) or the integer sats string (non-fiat).
4. **Preset strip** — `FlowLayout` (wraps to new line, doesn't scroll
   horizontally). Each pill renders the formatted sats amount; the
   `Custom` pill carries an inline `+` badge for save-as-preset when
   the current amount isn't yet in the list (badge disables at 8 max).
5. **Hidden amount TextField** — zero-size, anchored to the focus
   state. Tap on hero / onAppear sets focus.
6. **Message field** — always-visible single-line.
7. **Privacy dropdown** — `Menu` with "Public / Anonymous / Private",
   eye / eye-slash / lock icon. Helper caption appears for non-public
   types.
8. **Instant zaps toggle** — bound directly to `quickZapEnabled` so
   users can flip the setting from the sheet without navigating to
   settings.
9. **Bottom bar** — full-width Zap button. Above it: "Max 1,000,000
   sats per zap" red caption when over the hard cap.

### Behaviors

**Auto-focus on appear, deferred 450 ms.** The keyboard rises after the
sheet's mount + transition completes — without the hop the keyboard
rising during mount changes the parent feed-row's layout, which
unmounts the `.sheet` and produces an open/close loop. Compose's
`SideEffect` after `LaunchedEffect(Unit) { delay(450) ; focusRequester.requestFocus() }`
should give the same window.

**Amount seed.** On appear `amountSats` is set from
`quickZapAmountSats` (or fiat equivalent) — treat the configured
instant-zap amount as the user's "preferred opening amount" even when
quick zaps are disabled.

**First-keystroke-replaces-seed.** Track a `hasTypedAmount` flag.
While false (the very first time the field becomes editable), an
empty value from the TextField binding is the platform's initial-focus
bind commit and is ignored — the seeded amount survives. Once true,
empty means the user backspaced and `amountSats` follows to 0,
disabling the Zap button. Tapping a preset resets the flag.

**1,000,000 sats hard cap.** Zap button disables; red "Max …" caption
appears.

**10,000 sats soft confirmation.** Zap button tap routes through a
confirmation dialog ("Zap N sats? — This is a large amount, double-check
before sending"). Below 10K fires immediately.

**Per-user preset storage.** Key is `zapPresetAmounts_<pubkey>` (one
slot per signed-in account). On first read for a new account, migrate
the legacy global `zapPresetAmounts` value into the per-user slot.
NIP-78 backup pulls + restores from the active user's slot.

**Preset format.** CSV of entries: `<sats>` or `<sats>:<message>`.
The optional message is a default zap note that auto-fills the message
field when the preset is tapped (only when the message field is
currently empty, so it doesn't clobber typing). Commas and colons are
stripped from the saved message because they're the format delimiters.

**EditPresetsSheet.** Two columns per row (Amount, Message),
drag-to-reorder, swipe-to-delete. "Add preset" button **disabled** when
any existing row has an empty amount (one blank row at a time).

**Scroll + keyboard.** The whole sheet content is a `ScrollView` with
`.scrollDismissesKeyboard(.interactively)` so a drag-down dismiss
collapses the keyboard the moment the drag starts — fixes a "floating
elements" artifact where keyboard-avoidance fought the sheet drag.
Compose equivalent: `Modifier.nestedScroll` with a connection that
drops `LocalSoftwareKeyboardController.current` on first downward drag.

**Self-zap disabled.** Compute `isOwnPost = (myPubkey == event.pubkey)`
(after `resolveRepost` so reposting your own note still disables but
reposting someone else's stays active). Zap button rendered at 35%
opacity, disabled, and the long-press gesture is also short-circuited.

**Friendly error copy.** `ZapAnimationStore.friendlyMessage(for:)`
maps raw SDK error strings into plain copy. Mirror the same pattern
match on Android. Cases:

| Substring (case-insensitive) | Replacement |
|---|---|
| `insufficient funds` / `insufficient balance` | "Not enough sats in your wallet." |
| `no route` / `route not found` / `unreachable` | "Couldn't find a payment route to the recipient. Try again later." |
| `expired` / `invoice has expired` | "The lightning invoice expired before it could be paid. Try again." |
| `timeout` / `timed out` | "The payment timed out. Check your connection and try again." |
| `no lud16` / `no lightning address` | "This account doesn't have a lightning address." |
| `lnurl` AND `400` | "The recipient's lightning provider rejected this zap. Try a different amount." |
| `amount too small` / `below minimum` | "Amount is below the recipient's minimum. Try a larger zap." |
| `amount too large` / `above maximum` | "Amount is above the recipient's maximum. Try a smaller zap." |

Fallback: extract the substring between the first `("` and `")` (Swift
enum description wrapper) if present; otherwise pass through raw.

## Post-card zap gesture

* **Tap** — always opens the ZapSheet composer.
* **Long-press (400 ms)** — fires the configured instant-zap amount
  *if* `quickZapEnabled` is on and a wallet is set up; otherwise falls
  through to the composer so the gesture never feels like a no-op.

Pin a `zapLongPressFired` flag so the tap handler short-circuits the
release after a long-press completes (otherwise SwiftUI fires both the
long-press handler AND the underlying button tap; Compose has the same
issue with `combinedClickable`).

## In-flight bolt pulse

Replaces the multi-layer Canvas bolt that smeared the silhouette at
scale peaks. New approach: **always-white silhouette** + three stacked
zap-color shadows behind it.

Single sin-eased oscillator runs the whole animation:
```
sine  = sin(elapsed / 0.9 * 2π)        // -1 … 1
phase = (sine + 1) / 2                   // 0 … 1

iconScale = 1.0 + 0.10 * sine            // 0.90 → 1.10, centered
verticalOffset = -0.5 * sine             // ±0.5pt, centered on baseline
```

Shadows (Compose: `drawBehind` with `drawCircle(blendMode = Plus)`-style
falloff, or stack three `Shape.shadow` modifiers):

| Layer | Radius | Color opacity |
|---|---|---|
| inner | 1.5pt | `wispZapColor * 0.95`, constant |
| medium | `4 + 3 * phase` | `wispZapColor * (0.55 + 0.45 * phase)` |
| outer | `8 + 6 * phase` | `wispZapColor * (0.3 + 0.5 * phase)` |

Vertical motion stays within ±0.5pt so the icon doesn't lift off the
action-bar baseline. Don't tint the icon itself — the white silhouette
is the luminous core, the shadows do the heat work.

## Developer panel

`DeveloperToolsView` is a `#if DEBUG` view presented from a new
"Developer" row in Interface settings (also wrapped in `#if DEBUG`).
Currently empty placeholder — Android equivalent is a `BuildConfig.DEBUG`
guarded row + activity / sheet so future throwaway experiments land
somewhere out of production code instead of building one-off entry
points each time.

## Test plan

- [ ] Wallet setup: Spark is primary glowing button, NWC remains
      visible peer. Spark setup screen vertically centers content with
      "Use my default wallet" primary + collapsible More options.
- [ ] Open zap sheet from a feed post: keyboard up, hero shows the
      configured one-tap amount, first keystroke replaces it, backspace
      to empty disables Zap.
- [ ] Tap a preset → amount + (if present) message auto-fill.
- [ ] Custom + `+` chip → adds the current amount to the user's
      per-account preset row.
- [ ] EditPresetsSheet: Add preset disabled when a blank row exists;
      Done writes per-account, drag-to-reorder works.
- [ ] Privacy dropdown cycles Public / Anonymous / Private.
- [ ] Instant zaps toggle on the sheet flips the same setting as
      the Interface screen.
- [ ] Drag the sheet down → keyboard collapses, sheet body translates
      as one unit (no floating items).
- [ ] > 10K sats → confirmation dialog. > 1M sats → Zap disabled + red
      cap message.
- [ ] Insufficient funds → "Not enough sats in your wallet."
- [ ] Post-card zap: tap opens composer, long-press fires instant
      (when configured), self-post button disabled at 35% opacity.
- [ ] In-flight bolt: white core with breathing warm glow, centered
      bounce, stays aligned with neighbouring action-bar items.
- [ ] Sign in as Account A, set custom presets, switch to Account B —
      B sees its own row (default first time). Switch back to A — A's
      row intact.
- [ ] Settings → Interface (debug build) → Developer → Developer tools
      opens (empty placeholder).
