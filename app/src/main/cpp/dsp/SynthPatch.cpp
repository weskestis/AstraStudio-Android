#include "SynthPatch.h"
#include <algorithm>
#include <cmath>
#include <numbers>

namespace astra::synth {
Patch::Patch() noexcept {
    for (int i = 0; i < ParameterCount; ++i) values[i] = specs[i].initial;
    for (auto& shape : shapes) for (int i = 0; i < kShapePoints; ++i)
        shape[i] = static_cast<float>(-std::cos(2.0 * std::numbers::pi * i / kShapePoints));
}
bool Patch::valid() const noexcept {
    for (int i = 0; i < ParameterCount; ++i) {
        const auto v = values[i]; const auto& s = specs[i];
        if (!std::isfinite(v) || v < s.minimum || v > s.maximum || (s.integer && v != std::floor(v))) return false;
    }
    for (const auto& shape : shapes) for (float v : shape)
        if (!std::isfinite(v) || v < -1.0f || v > 1.0f) return false;
    for (const auto& route : routes)
        if (route.source < 0 || route.source > static_cast<int>(Source::Key) ||
            route.target < 0 || route.target >= static_cast<int>(Target::Count) ||
            !std::isfinite(route.amount) || std::abs(route.amount) > 1.0f) return false;
    return true;
}
bool Patch::decode(std::span<const float> wire) noexcept {
    if (wire.size() != kWireCount) return false;
    for (float v : wire) if (!std::isfinite(v)) return false;
    Patch next;
    int at = 0;
    for (auto& v : next.values) v = wire[at++];
    for (auto& shape : next.shapes) for (auto& v : shape) v = wire[at++];
    for (auto& r : next.routes) {
        const float source = wire[at++], target = wire[at++];
        // Validate before float-to-int conversion, including hostile huge inputs.
        if (source < 0 || source > 9 || target < 0 || target >= static_cast<int>(Target::Count) ||
            source != std::floor(source) || target != std::floor(target)) return false;
        r = {static_cast<int>(source), static_cast<int>(target), wire[at++]};
    }
    if (!next.valid()) return false;
    *this = next;
    return true;
}
void Patch::encode(std::span<float, kWireCount> wire) const noexcept {
    int at = 0;
    for (auto v : values) wire[at++] = v;
    for (const auto& shape : shapes) for (auto v : shape) wire[at++] = v;
    for (const auto& r : routes) { wire[at++] = static_cast<float>(r.source); wire[at++] = static_cast<float>(r.target); wire[at++] = r.amount; }
}
Patch preset(int index) noexcept {
    Patch p;
    auto route = [&](int slot, Source source, Target target, float depth) {
        p.routes[slot] = {static_cast<int>(source), static_cast<int>(target), depth};
    };
    switch (index) {
        case 1: // A rounded moving growl, with a separate clean sub oscillator.
            p[OscABank]=2; p[OscAPosition]=.3f; p[OscALevel]=.7f;
            p[OscBBank]=1; p[OscBPosition]=.4f; p[OscBLevel]=.35f; p[OscBTune]=12;
            p[FmAmount]=.3f; p[SubLevel]=.45f;
            p[Filter1Type]=1; p[Filter1Cutoff]=900; p[Filter1Resonance]=.45f; p[Filter1Drive]=.25f;
            p[Filter2Type]=5; p[Filter2Cutoff]=900; p[Filter2Vowel]=.25f; p[FilterRouting]=1;
            p[Env1Release]=.08f;
            route(0,Source::Lfo1,Target::Cutoff1,.46f);
            route(1,Source::Lfo1,Target::PositionA,.3f);
            route(2,Source::Lfo1,Target::Amplitude,.5f);
            route(3,Source::Env2,Target::FmAmount,.12f);
            break;
        case 2:
            p[OscABank]=3; p[OscAPosition]=.6f; p[OscALevel]=.6f;
            p[OscBBank]=2; p[OscBPosition]=.7f; p[OscBLevel]=.5f; p[OscBTune]=7;
            p[OscAWarp]=1; p[OscAWarpAmount]=.18f; p[FmAmount]=.65f; p[SubLevel]=.35f;
            p[Filter1Type]=4; p[Filter1Cutoff]=240; p[Filter1Resonance]=.65f; p[Filter1Drive]=.25f;
            p[Filter2Type]=1; p[Filter2Cutoff]=5000; p[Filter2Resonance]=.2f;
            p[Lfo1Beats]=.5f; p[Env1Release]=.06f;
            route(0,Source::Lfo1,Target::FmAmount,.15f);
            route(1,Source::Lfo1,Target::Cutoff1,.16f);
            route(2,Source::Lfo2,Target::PositionB,.3f); p[Lfo2Beats]=2;
            break;
        case 3:
            p[OscAPosition]=.67f; p[OscADetune]=-9; p[OscALevel]=.7f;
            p[OscBPosition]=.67f; p[OscBDetune]=9; p[OscBLevel]=.7f; p[OscBTune]=12;
            p[Filter1Type]=1; p[Filter1Cutoff]=2400; p[Filter1Resonance]=.3f;
            p[Env1Attack]=.015f; p[Env1Decay]=.35f; p[Env1Sustain]=.6f; p[Env1Release]=.4f;
            p[Env2Decay]=.4f; p[Env2Sustain]=0;
            route(0,Source::Env2,Target::Cutoff1,.35f);
            p[Lfo1Sync]=0; p[Lfo1Rate]=5; p[Lfo1Retrigger]=0;
            route(1,Source::Lfo1,Target::Pitch,.003f);
            break;
        case 4:
            p[OscALevel]=.35f; p[SubLevel]=.9f;
            p[Env1Attack]=.008f; p[Env1Decay]=.6f; p[Env1Sustain]=.7f; p[Env1Release]=.12f;
            p[Env2Decay]=.04f; p[Env2Sustain]=0;
            route(0,Source::Env2,Target::Pitch,.2f);
            break;
        case 5:
            p[Instrument]=1; p[Algorithm]=0; p[FmIndex]=3.2f; p[Feedback]=.22f;
            p[Op2Ratio]=2.01f; p[Op3Ratio]=3.5f; p[Op4Ratio]=7;
            p[Op2Level]=.7f; p[Op3Level]=.5f; p[Op4Level]=.3f;
            p[Filter1Type]=1; p[Filter1Cutoff]=3800; p[Filter1Drive]=.18f;
            p[SubLevel]=.35f; p[Env1Release]=.08f;
            route(0,Source::Lfo1,Target::FmIndex,.22f);
            route(1,Source::Lfo1,Target::Cutoff1,.25f);
            break;
        case 6:
            p[Instrument]=1; p[Algorithm]=1; p[FmIndex]=2.6f;
            p[Op2Ratio]=2; p[Op3Ratio]=1; p[Op4Ratio]=3;
            p[Op1Decay]=.3f; p[Op2Decay]=.12f; p[Op3Decay]=.25f; p[Op4Decay]=.06f;
            p[Op1Sustain]=0; p[Op2Sustain]=0; p[Op3Sustain]=0; p[Op4Sustain]=0;
            p[Env1Decay]=.4f; p[Env1Sustain]=0; p[Env1Release]=.1f; p[SubLevel]=.25f;
            p[Env2Decay]=.05f; p[Env2Sustain]=0; route(0,Source::Env2,Target::Pitch,.12f);
            break;
        case 7:
            p[Instrument]=1; p[Algorithm]=2; p[FmIndex]=1.8f;
            p[Op2Ratio]=2; p[Op3Ratio]=5; p[Op4Ratio]=7.01f;
            p[Op2Level]=.35f; p[Op3Level]=.18f; p[Op4Level]=.12f;
            p[Op1Decay]=1; p[Op2Decay]=.2f; p[Op3Decay]=.15f; p[Op4Decay]=.08f;
            p[Op1Sustain]=.1f; p[Op2Sustain]=0; p[Op3Sustain]=0; p[Op4Sustain]=0;
            p[Env1Attack]=.002f; p[Env1Decay]=.7f; p[Env1Sustain]=.15f; p[Env1Release]=.5f;
            p[Filter1Type]=1; p[Filter1Cutoff]=9000;
            break;
        default: break;
    }
    return p;
}
} // namespace astra::synth
