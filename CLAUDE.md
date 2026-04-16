# CLAUDE.md

## Project
**x2rock** — A Sonos controller app for Google TV (Android TV).

## Language & Platform
- Kotlin only — no Java
- Target platform: Google TV / Android TV (Leanback UI)
- Min SDK: 21
- Target SDK: 34
- Build system: Gradle with Kotlin DSL (build.gradle.kts)

## Architecture
- MVVM with clean architecture
- Repository pattern for Sonos API calls
- Kotlin Coroutines + Flow for async/reactive state
- Jetpack Compose for TV (androidx.tv) preferred over Leanback where possible

## Sonos Integration
- Use Sonos local network control (UPnP/SOAP) — no cloud API
- Docs are in /docs/ folder — read them before implementing Sonos features

## Code Style
- Idiomatic Kotlin
- No unnecessary abstractions
- Prefer simplicity over cleverness
