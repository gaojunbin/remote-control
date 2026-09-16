import Foundation
#if os(iOS)
import AVFoundation
import UIKit
#endif

/// Whether the app may use the camera.
///
/// `docs/DESIGN.md` § "The three screens" → **A camera the app may not use says
/// so** puts denied and restricted on the same line, so there are two answers
/// here and not four.
public enum CameraAccess: Sendable, Equatable {
    case allowed, denied
}

/// The camera the app asks for, and whether there is one at all.
@MainActor
public enum Camera {
    /// Whether this machine has a camera to offer. A simulator has none, and
    /// presenting `UIImagePickerController` with `.camera` there raises rather
    /// than refusing, so nothing offers the item before asking.
    public static var exists: Bool {
        #if os(iOS)
        UIImagePickerController.isSourceTypeAvailable(.camera)
        #else
        false
        #endif
    }

    /// The authorization, asked of the person the first time. Everything that
    /// is not an explicit yes is a refusal: restricted by policy and denied by
    /// hand read the same to the user, and get the same line.
    public static func requestAccess() async -> CameraAccess {
        #if os(iOS)
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            return .allowed
        case .notDetermined:
            return await AVCaptureDevice.requestAccess(for: .video) ? .allowed : .denied
        default:
            return .denied
        }
        #else
        return .denied
        #endif
    }

    /// The app's own page in iOS Settings, where a refused camera is the only
    /// thing left to do about it.
    public static func openSystemSettings() {
        #if os(iOS)
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
        #endif
    }
}
