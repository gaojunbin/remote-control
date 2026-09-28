import Testing

/// Every suite that switches the interface language, which is one value for
/// the whole process: they run one at a time, so no test reads a word in the
/// language another has just switched to.
@Suite(.serialized)
enum LanguageSensitive {}
