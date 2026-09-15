import Foundation

/// Amendment A33: the one line under an agent that says how it is signed in.
///
/// `docs/DESIGN.md` § "A device has a page": the vendor's name, then the plan,
/// the tier and the email, each only when the device reported it; *Anthropic
/// API key*, or *Anthropic API key · host* when the key goes somewhere that is
/// not the vendor; *Not signed in* when the device found neither.
///
/// Everything but the vendor's name is the device's own word: a plan, a tier,
/// an email and a host are data, never translated and never mapped through a
/// table. The plan alone is raised at its first letter, because the vendor
/// records it lowercase and the device passes it on as it found it; the tier
/// arrives in words the device already vouches for (A33).
public enum AccountLine {
    public static let separator = " · "

    /// The vendors this build has a name for. An id it does not know is printed
    /// as itself, so a device that grows a fifth vendor needs no new app.
    private static let vendors = [
        "anthropic": "Anthropic",
        "openai": "OpenAI",
        "xai": "xAI"
    ]

    public static func vendorName(_ provider: String) -> String {
        vendors[provider] ?? provider
    }

    /// The line itself, for one credential.
    public static func text(for account: AgentAccount) -> String {
        var parts: [String] = []
        let vendor = vendorName(account.provider)
        switch account.method {
        case .apiKey:
            parts.append(vendor.isEmpty ? L10n.string("API key") : L10n.string("%@ API key", vendor))
            if let endpoint = account.endpoint, !endpoint.isEmpty { parts.append(endpoint) }
        default:
            parts.append(vendor.isEmpty ? L10n.string("Account") : L10n.string("%@ account", vendor))
            if let plan = account.plan, !plan.isEmpty { parts.append(raised(plan)) }
            if let tier = account.tier, !tier.isEmpty { parts.append(tier) }
            if let email = account.email, !email.isEmpty { parts.append(email) }
        }
        return parts.joined(separator: separator)
    }

    /// The vendor's own word with its first letter raised: `max` reads as *Max*
    /// beside a name and a tier that are already written for a reader.
    static func raised(_ word: String) -> String {
        guard let first = word.first else { return word }
        return String(first).uppercased() + word.dropFirst()
    }

    /// What a device says when it looked and found nothing signed in.
    public static var notSignedIn: String { L10n.string("Not signed in") }
}
