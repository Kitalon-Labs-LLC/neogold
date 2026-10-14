# NeoGold Android Project

This directory contains the Android Gradle multi-module project:
- `:app` - Single-activity application shell
- `:feature:*` - UI feature modules (`onboarding`, `calibration`, `screening`, `result`, `log`, `research`, `settings`)
- `:engine:*` - Core domain engines (`camera`, `vision`, `colorscience`, `estimation`, `triage`, `cardtrust`)
- `:core:*` - Common utilities, models, data layer, security, and design system
- `:sync:research` - Research upload synchronization client

## Work Packages
- **P01**: Android skeleton, multi-module configuration, build guardrails, and CI setup
- **P03**: Triage engine and signed config
- **P04**: Color science engine
- **P05**: Card trust engine
- **P06**: Estimation engine
- **P08**: Vision engine
- **P09**: Camera, device qualification
- **P10**: v0.1 prototype integration and performance
- **P11**: Local data layer (Room DB, encrypted)
- **P12**: App integrity, privacy controls, app lock, and crash handling
- **P13**: Design system, onboarding, settings, and Bangla localization
- **P14**: Research mode flow
- **P15**: Encrypted USB export
- **P16**: Release pipeline, signing, and security gates
- **P20**: Research sync client and enrollment
- **P21**: Screening flow
- **P22**: Device matrix and release candidates
