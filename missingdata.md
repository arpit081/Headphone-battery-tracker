# Missing data

| What is missing | Needed by | Context |
|---|---|---|
| Sony WH-1000XM5 claimed battery hours in **Ambient Sound** mode | Claim comparison (Phase 6, `claims/`) | Sony publishes ~30 h (NC on) and ~40 h (NC off). No ambient-mode figure found yet. Until found, the ambient comparison has no baseline, and `HealthCalculator` (`tracking/HealthCalculator.kt`) assumes Ambient uses battery like ANC when weighting modes. |
| realme Buds Air 3S model entry (name match + claimed hours for ANC on / ANC off, buds and with case) | `HeadphoneCatalog` (`headphones/HeadphoneCatalog.kt`), used by the home screen's Company claim card | Paired on the user's phone but not in the catalog, so the app shows "No manufacturer claim stored for this model yet". |
