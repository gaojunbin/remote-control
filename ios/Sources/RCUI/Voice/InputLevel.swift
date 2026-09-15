import Foundation

/// How loud the microphone is, on the 0…1 scale the waveform and the listening
/// glow read.
///
/// Loudness is logarithmic, so the root-mean-square amplitude of a buffer is
/// mapped through decibels. Where the ends of that scale sit decides how much
/// of the glow's swing ordinary speech actually uses: a room with nobody
/// talking sits around −50 dBFS on a phone's microphone and conversational
/// speech peaks near −12, so those are the ends. The first scale ran from −55
/// to 0 dB, which is the whole dynamic range of the format rather than the
/// range of a voice: speech landed between 0.4 and 0.6 and the glow breathed
/// through a third of itself.
///
/// Both backends map it here so the two cannot drift apart.
public enum InputLevel {
    /// A quiet room.
    public static let floorDB = -50.0
    /// Conversational speech at arm's length, where the meter is full.
    public static let ceilingDB = -12.0

    public static func from(rms: Double) -> Double {
        let decibels = 20 * log10(max(rms, 0.0001))
        return min(1, max(0, (decibels - floorDB) / (ceilingDB - floorDB)))
    }
}
