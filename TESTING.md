# On-device testing checklist

Tick items after checking on a real phone (S25 Ultra / S23+).

## Phase 0
- [ ] App installs (`./gradlew :app:installDebug`) and launches with the McPodface splash.
- [ ] Five tabs show: Latest · Library · Downloads · Up Next · Search.
- [ ] Switch to e.g. Downloads, swipe the app away, relaunch: it reopens on Downloads with no flash of Latest.
- [ ] Gear icon on Latest and Library opens Settings; back returns.
- [ ] Settings → Theme Light / Dark / System switches immediately, and status/nav bar icons stay readable.
- [ ] Pure black on: dark background is true black. Off: dark grey.
- [ ] Content draws edge to edge (behind status and gesture bars) without being covered.
- [ ] Predictive back gesture shows the back preview from Settings.
