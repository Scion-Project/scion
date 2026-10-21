# Guest audio: iOS speaker out of the Pixel's speaker

Plan for getting sound from the emulated iPhone 11 (t8030, iOS 14) out of the phone. Speaker
output first, microphone later. Status: **milestone 1 in review** (dylanneve1/Inferno#24). iOS
loads its audio drivers, but nothing plays yet. See [Status](#status-2026-10-21) and
[Work items](#work-items).

## Summary

- **Most of the guest side already exists in Inferno.** Upstream wrote models of the MCA
  audio controller (`hw/audio/mca.c`), the CS42L77 codec (`cs42l77.c`), the CS35L27 amp
  (`cs35l27.c`) and part of the AOP audio service (`aop-audio.c`). Upstream's last commit on
  it is "mca: somewhat working audio now" (Aug 2026). What's missing is the wiring: t8030
  deletes every audio node from the device tree, so iOS never loads its audio drivers, and the
  MCA can't be pointed at a QEMU audio backend.
- **The Android build has no audio backend.** `native/build-android.sh` disables every QEMU
  audio driver, so the only ones compiled in are `none` and `wav`.
- **Recommendation for the host side: an AAudio driver inside QEMU (`-audiodev aaudio`)**,
  not a PCM socket to an `AudioTrack` in the app. The reasons are under
  [Android backend](#android-backend-aaudio-vs-a-socket).
- **Effort: about 1–2 weeks of focused work to hear the speaker on the phone**, most of it
  in milestone 2 (making iOS actually play through the emulated hardware), which is the only
  part with real unknowns. The breakdown is under [Effort](#effort).

## How iOS 14 plays audio on the iPhone 11

Read from the n104ap device tree and the iOS 14.0 kernelcache's kext personalities.

```
 CoreAudio (mediaserverd / audiomxd)
   └─ AppleEmbeddedAudio  (AppleEmbeddedAudioDevice; AppleARMIISDevice nubs)
        ├─ AppleMCA2-T8030:  AppleMCA2Switch_T8030  ← arm-io/mca-switch   "mca-switch,t8030"
        │                    AppleMCA2Cluster_T8030 ← arm-io/mca0..mca5   "mcaCluster,t8030"
        │                    (I2S units mcaNa/mcaNb  "mca,t8030", DMA through SIO)
        ├─ AppleCS42L77Audio  ← arm-io/spi3/audio-codec        "audio-control,cs42l77"  (SPI control)
        │     data: mca1/mca1a/audio-codec                     "audio-data,cs42l77"
        ├─ AppleCS35L27Amp    ← arm-io/i2c2/audio-speaker-top  "audio-control,cs35l27"  (I2C 0x40)
        │     data: mca5/mca5b/audio-speaker-top               "audio-data,cs35l27"
        ├─ AppleEmbeddedAudioResourceManager ← arm-io/audio-resource-manager
        └─ AppleAOPAudio (AOP firmware service "aop-audio"; speaker control 'apac',
                          codec MCLK manager 'acmm', PCM asset manager 'pcmM', haptics, LP mic)
```

| Piece | DT node | Driver (kext) | Bus | Inferno today |
|---|---|---|---|---|
| MCA switch (clock/sync generators, 6 clusters) | `arm-io/mca-switch` | `AppleMCA2Switch_T8030` | MMIO: SIO-side units `0x38400000`, MCA DMA FIFOs `0x38300000`, MCLK config `0x3b0400b4` | `hw/audio/mca.c`, created, node deleted |
| MCA clusters (I2S TX/RX A/B) | `arm-io/mca0`…`mca5`, children `mcaNa`/`mcaNb` | `AppleMCA2Cluster_T8030` | MMIO + SIO DMA channels (`dma-channels`) | same model; only cluster 5 TXB starts playback |
| Audio DMA | `arm-io/sio` (SIO coprocessor) | `AppleSmartIO` | RTKit mailbox; MCA5B uses endpoints `0x7a` (TX) / `0x7b` (RX) | `hw/dma/apple_sio.c`, working (SPI uses it) |
| Codec CS42L77 | `arm-io/spi3/audio-codec` | `AppleCS42L77Audio` | SPI3 | `cs42l77.c`: register file + command protocol, created, node deleted |
| Speaker amp CS35L27 ("speaker-top") | `arm-io/i2c2/audio-speaker-top` | `AppleCS35L27Amp` | I2C2 @ 0x40 | `cs35l27.c`: register file + ID, created, node deleted |
| AOP audio service | `arm-io/aop/iop-aop-nub/aop-audio/*` | `AppleAOPAudio*`, `AppleAOPHaptics` | AOP RTKit endpoint | `aop-audio.c`: property replies for most AOP devices, **not instantiated** (commented out in `t8030_create_aop`) |
| Resource manager | `arm-io/audio-resource-manager` | `AppleEmbeddedAudioResourceManager` | looks up `AOPAudioHWPCMAssetManagerInterface` | node deleted |

Notes:

- The amp's `function-secSpeaker_control` points at the AOP's `audio-speaker` node, and the
  codec's `AOP` ASP setting routes one of its serial ports to the AOP. On this phone the AOP
  owns speaker protection, so some of the speaker path probably needs the AOP audio service
  answering, not only the MCA. How much is the main open question (milestone 2).
- All the "secondary" audio-data devices (`audio-data,hawking`, `halogen`, `baseband-voice`,
  `dsp-debug1`, `audio-loopback`, `mikeybus-secondary`) match `AppleSecondaryAudio` in
  `AppleEmbeddedAudio`. They aren't needed for the speaker and stay deleted.
- How missing hardware is hidden: `hw/arm/boot.c` keeps only nodes whose `compatible` is on
  `KEEP_COMP` and deletes the rest (plus `REM_NAMES`, `REM_PROPS`). Every audio compatible is
  commented out of that list today, which is why no audio driver loads.

### The existing MCA model

`hw/audio/mca.c` models the three MMIO blocks at register level and, when cluster 5's TXB
unit is enabled, runs a timer at 48 kHz that pulls PCM from SIO DMA endpoint `0x7a` into a
ring and hands it to `AUD_write()`, while feeding silence into the RX endpoint so the guest's
feedback/RX DMA completes. Hard-coded assumptions to remove later: cluster 5 only, S16
stereo 48 kHz, RX as 4 channels. It also opened its sound card with no way to pick a backend,
so with an `-audiodev` on the command line it failed ("Failed to create QEMU sound card").

## Android backend: AAudio vs. a socket

QEMU needs somewhere to send PCM. Options considered:

| | AAudio driver in QEMU (`audio/aaudio.c`) | PCM socket → `AudioTrack` in the app | `-audiodev wav` into a FIFO → `AudioTrack` |
|---|---|---|---|
| QEMU code | new audio driver, ~300 lines + QAPI/meson | new audio driver (socket writer), ~200 lines | none |
| App code | audio focus only | reader thread + `AudioTrack` + reconnect | reader thread + `AudioTrack` |
| Child-process mode (default) | yes, if AAudio works from the exec'd child (same uid/SELinux domain as the app; to verify) | yes | yes |
| In-process mode (`:vm`) | yes, plainly an app process | yes | yes |
| Latency | lowest: a callback straight from the audio HAL, MMAP path on Pixel | extra socket + `AudioTrack` buffer | pipe buffer + `AudioTrack` buffer; writes block the QEMU audio timer if the reader stalls |
| Copies | QEMU ring → AAudio | QEMU → kernel → app → AudioTrack | same as socket |
| Failure modes | AAudio stream disconnects on route change (reopen on `AAUDIO_ERROR_DISCONNECTED`) | app must be alive and reading | QEMU blocks on a full pipe |

**Pick: AAudio in QEMU.** When the plan was first sketched, the socket route looked natural
because the display was VNC on a unix socket. Since #66 the app can run QEMU in-process and draw
straight into a `Surface`, with input over JNI, so the "everything over unix sockets" design
is now only the child-process path. An in-QEMU AAudio driver works the same way in both modes,
keeps PCM inside one process, and gives the lowest latency. The app's only job is to
request audio focus while a VM runs. The one thing to check first (milestone 3, step 1) is that
AAudio opens from the exec'd child process. If it doesn't, the fallback is the socket writer
plus `AudioTrack`, used for the child-process mode only.

Format: open the stream as S16 stereo 48 kHz, `AAUDIO_PERFORMANCE_MODE_LOW_LATENCY`,
`AAUDIO_USAGE_GAME`, data-callback mode reading from a lock-free ring that QEMU's `write` op
fills (the pattern `jackaudio.c` and `pwaudio.c` use). AAudio resamples if the device runs at
another rate. NDK API 26+ is required; the app's minSdk is 31.

The host Mac build already has the `coreaudio` driver, so milestones 1–2 can be developed on
the Mac (`-audiodev coreaudio` or `-audiodev wav,path=out.wav`) before any Android work.

## Status (2026-10-21)

Tested on the macOS host build with iOS 14.0 (18A5351d) and the real SEP, on a throwaway qcow2
overlay:

- With `-M t8030,...,audio=on,audiodev=snd0 -audiodev wav,id=snd0,path=out.wav` a fresh VM
  runs data migration and reaches Setup Assistant with no panic.
- The new trace events show the drivers probing the hardware: the MCA switch programs all six
  clusters, AppleCS42L77Audio reads the codec's chip ID over SPI3, and AppleCS35L27Amp
  reads the amp's ID over I2C2. AppleEmbeddedAudioResourceManager looks up
  `AOPAudioHWPCMAssetManagerInterface` (an AOP service) and logs nothing further.
- **Nothing has been played yet.** There was no MCA DMA setup, no TX enable and no amp writes up
  to Setup Assistant, which plays no sound by itself. Triggering a system sound is the first
  step of milestone 2.
- Testing pitfall: hard-quitting the VM soon after boot desynced the SEP anti-replay state,
  and the next boot hit `SEP Panic: :sars/sars`. Start every run from a throwaway overlay plus
  fresh copies of the small disks.

## Work items

Tracked in issues #91–#96 (label `audio`).

Each milestone is one PR (or a few small ones) with its own issue.

1. **Inferno: expose the audio hardware behind an opt-in machine option** (#91, Inferno#24). `-M
   t8030,audio=on[,audiodev=<id>]` keeps the MCA, CS42L77, CS35L27 and resource-manager nodes
   and creates the MCA, codec and amp models. The MCA becomes a realized qdev with an
   `audiodev` property. Default off, so nothing changes for existing VMs. Check: iOS boots to
   the UI with `audio=on` and the drivers attach. *Effort: 1–2 days.*
2. **Inferno: make iOS play into the MCA** (#92). Find out which cluster the speaker route uses,
   instantiate the AOP audio endpoint and answer what speaker playback needs (speaker control
   `apac`, MCLK manager `acmm`, PCM asset manager), keep the codec/amp register models
   returning "powered/locked" status, and derive the format (channels, width) from the
   MCA/DMA config instead of hard-coding cluster 5 / S16 stereo. Check: a system sound (lock
   click, keyboard click, a ringtone preview) produces non-silent samples in a `wav` capture
   on the Mac. *Effort: 3–6 days; this is the part with unknowns.*
3. **Inferno: AAudio audio driver** (#93). `audio/aaudio.c`, QAPI `AudiodevDriver` entry, meson
   detection of `aaudio/AAudio.h` + `libaaudio`. Step 1: confirm AAudio opens from the
   app's child process. Check: tone/WAV playback on the emulator and a Pixel. *Effort: 2–3 days.*
4. **ios-on-pixel: build and app wiring** (#94). Enable the driver in `build-android.sh`, pass
   `audio=on,audiodev=snd0 -audiodev aaudio,id=snd0` (behind a setting, default on once
   stable), request audio focus, mute while paused. *Effort: 1 day.*
5. **Timing and quality** (#95). The MCA timer runs on `QEMU_CLOCK_VIRTUAL` while AAudio pulls in
   real time. Under TCG the guest can run slower than real time, so expect underruns. Size the
   ring, and consider pacing from the host clock. Check: a minute of music without audible
   gaps on a Pixel 10. *Effort: 2–4 days.*
6. **Later: microphone** (#96). MCA RX path with real samples (AAudio input, `RECORD_AUDIO`
   permission), AOP low-power mic (`lpai`) for "Hey Siri" stays stubbed. Not started.

## Effort

| Milestone | Estimate | Confidence |
|---|---|---|
| 1. DT + device wiring | 1–2 days | high (drivers probe; Inferno#24) |
| 2. iOS actually plays into the MCA | 3–6 days | low: depends on how much of the AOP speaker path iOS insists on |
| 3. AAudio driver | 2–3 days | high, standard QEMU driver pattern |
| 4. App/build wiring | 1 day | high |
| 5. Timing/underruns | 2–4 days | medium |
| **Speaker on the phone** | **~1.5–3 weeks** | |
| 6. Microphone | 1–2 weeks more | low |

## References

- Inferno upstream issues: ChefKissInc/Inferno#56 "Audio support", #50 "Implement
  Multi-Channel Audio controller" (both open, no discussion).
- Upstream code: `hw/audio/{mca,cs42l77,cs35l27,aop-audio}.c`, `hw/dma/apple_sio.c`,
  `t8030_create_mca()` / `t8030_create_speaker_*()` in `hw/arm/t8030.c`, `KEEP_COMP` in
  `hw/arm/boot.c`.
- Linux (Asahi) has an MCA driver for the M1's version of the same block
  (`sound/soc/apple/mca.c`), useful for register semantics.
- Android: [AAudio](https://developer.android.com/ndk/guides/audio/aaudio/aaudio).
