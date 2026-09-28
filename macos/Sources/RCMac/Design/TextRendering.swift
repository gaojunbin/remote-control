import Foundation

/// The web draws its type with `-webkit-font-smoothing: antialiased`: plain
/// greyscale antialiasing, without the stem darkening macOS adds to text by
/// default. Measured against the browser, the Mac's text carries 10–26 % more
/// ink with it and the same ink without it, so the app turns it off for its own
/// windows — in this process's argument domain, which is where the text system
/// reads it, and never in anybody's saved preferences.
public enum TextRendering {
    public static func matchWeb() {
        var arguments = UserDefaults.standard.volatileDomain(forName: UserDefaults.argumentDomain)
        arguments["AppleFontSmoothing"] = 0
        UserDefaults.standard.setVolatileDomain(arguments, forName: UserDefaults.argumentDomain)
    }
}
