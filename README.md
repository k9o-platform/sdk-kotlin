# Kilimanjaro for Kotlin and Android

Kotlin and Android clients for Kilimanjaro: run AI inference and sign users in with Gate.

## Contents

**A. Get started**

1. [Packages](#1-packages)
2. [Install](#2-install)
3. [Quick start](#3-quick-start)

**B. Reference**

4. [Appendix: links](#4-appendix-links)
   - 4.1 [About Kilimanjaro](#41-about-kilimanjaro)

## A. Get started

### 1. Packages

| Package | Maven coordinate | What it is for | Platform |
|---|---|---|---|
| [`k9o-core`](k9o-core) | `dev.k9o:core-kotlin` | Project client with `ai.generate` and `ai.embed` | JVM 17, Android |
| [`k9o-gate`](k9o-gate) | `dev.k9o:gate` | Gate: sign users in, refresh and sign out; tokens in the Android Keystore | Android API 23+, JVM 17 |

### 2. Install

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.k9o:core-kotlin:1.5.0")
    implementation("dev.k9o:gate:1.5.0")
}
```

Add only the packages you use. Both come from Maven Central. The Kibo vault is in [sdk-kibo-kotlin](https://github.com/k9o-platform/sdk-kibo-kotlin).
They need Android API 23 or later, or JVM 17.

### 3. Quick start

```kotlin
import dev.k9o.core.K9OCore

val core = K9OCore(
    baseUrl = "https://acme.api.k9o.io",
    projectSlug = "member-portal",
    accessToken = memberToken,
)

// suspend function: call it from a coroutine
val env = core.ai.embed(input = listOf("search_document: a"))
println(env.data.dimensions)
```

```text
768
```

Each package has its own README with a complete example and the expected output.

## B. Reference

### 4. Appendix: links

- Website: <https://kilimanjaro.dev>
- Documentation: <https://docs.kilimanjaro.dev>
- Other SDKs and the CLI: <https://github.com/k9o-platform>
- Issues and discussions: use the tabs of this repository
- License: Apache-2.0

#### 4.1 About Kilimanjaro

Kilimanjaro is a modular developer platform for building secure enterprise applications.
You define AI agents, functions and data pipelines on the platform.
The SDKs let you deploy and invoke them, read and write your data, publish to and subscribe to streams, sign users in, and encrypt with keys you hold.

It provides four building blocks:

- **Keep**: AI security and governance. Guardrails and policy for the agents you run.
- **Pulse**: data intelligence and real-time stream processing. Streams, analytics and live feeds.
- **Gate**: customer identity and access management. Sign-in and sessions, and automatic data scoping so a signed-in user reaches only their own data.
- **Kibo**: cryptographic keys and secrets management. Client-side encryption with keys you hold, and a secrets vault.
