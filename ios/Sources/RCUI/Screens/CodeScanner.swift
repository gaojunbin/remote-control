import SwiftUI
import RCCore
#if os(iOS)
import AVFoundation
import VisionKit
#endif

/// What the pairing screen needs from a camera (amendment A23): a view that
/// reports every payload it reads and keeps reading afterwards, because a code
/// for another gateway is answered in the strip above it rather than by closing
/// the camera.
///
/// The protocol is what lets the demo and the UI test run the whole flow on a
/// simulator, which has no camera at all: they supply a stand-in that hands
/// over a printed payload on a tap, and everything above this line — the
/// overlay, the claim, the errors — is the same either way.
@MainActor
public protocol CodeScanning {
    func makeView(onCode: @escaping (String) -> Void) -> AnyView
}

/// The stand-in: one button, one payload, no camera. It is what the demo and
/// the UI test scan with.
public struct StaticCodeScanner: CodeScanning {
    let payload: String

    public init(payload: String) { self.payload = payload }

    public func makeView(onCode: @escaping (String) -> Void) -> AnyView {
        AnyView(
            Button("Simulate a scan") { onCode(payload) }
                .buttonStyle(.borderedProminent)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .accessibilityIdentifier("scan.simulate")
        )
    }
}

/// The scanner this build runs on a real machine.
@MainActor
public enum SystemCodeScanner {
    public static func make() -> any CodeScanning {
        #if os(iOS)
        CameraCodeScanner()
        #else
        StaticCodeScanner(payload: "")
        #endif
    }
}

#if os(iOS)
/// The device camera. VisionKit's data scanner is the one the system tunes for
/// this, and an `AVCaptureMetadataOutput` session stands in on hardware where
/// it is unavailable.
public struct CameraCodeScanner: CodeScanning {
    public init() {}

    public func makeView(onCode: @escaping (String) -> Void) -> AnyView {
        if DataScannerViewController.isSupported, DataScannerViewController.isAvailable {
            return AnyView(DataScannerView(onCode: onCode))
        }
        return AnyView(MetadataScannerView(onCode: onCode))
    }
}

/// `VisionKit`'s scanner, restricted to QR payloads.
private struct DataScannerView: UIViewControllerRepresentable {
    let onCode: (String) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onCode: onCode) }

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let controller = DataScannerViewController(
            recognizedDataTypes: [.barcode(symbologies: [.qr])],
            qualityLevel: .balanced, recognizesMultipleItems: false,
            isHighFrameRateTrackingEnabled: false, isPinchToZoomEnabled: true,
            isGuidanceEnabled: false, isHighlightingEnabled: true)
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ controller: DataScannerViewController, context: Context) {
        guard !controller.isScanning else { return }
        try? controller.startScanning()
    }

    static func dismantleUIViewController(_ controller: DataScannerViewController, coordinator: Coordinator) {
        controller.stopScanning()
    }

    @MainActor
    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        private let onCode: (String) -> Void
        /// The scanner reports the same code on every frame it stays in view.
        private var last: String?

        init(onCode: @escaping (String) -> Void) { self.onCode = onCode }

        func dataScanner(_ scanner: DataScannerViewController, didAdd items: [RecognizedItem],
                         allItems: [RecognizedItem]) {
            for item in items { take(item) }
        }

        func dataScanner(_ scanner: DataScannerViewController, didTapOn item: RecognizedItem) {
            take(item)
        }

        private func take(_ item: RecognizedItem) {
            guard case .barcode(let barcode) = item, let payload = barcode.payloadStringValue,
                  payload != last else { return }
            last = payload
            onCode(payload)
        }
    }
}

/// The fallback: a capture session with a metadata output, which is what every
/// iPhone can do whether or not the data scanner is available.
private struct MetadataScannerView: UIViewControllerRepresentable {
    let onCode: (String) -> Void

    func makeUIViewController(context: Context) -> MetadataScannerController {
        MetadataScannerController(onCode: onCode)
    }

    func updateUIViewController(_ controller: MetadataScannerController, context: Context) {}
}

/// Owns the capture session so it is started and stopped with the view rather
/// than left running behind a dismissed screen.
final class MetadataScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    private let onCode: (String) -> Void
    private let session = AVCaptureSession()
    /// Configuring and starting a session blocks; doing it on the main thread
    /// stalls the presentation animation the camera appears behind.
    private let queue = DispatchQueue(label: "rc.scanner")
    private var preview: AVCaptureVideoPreviewLayer?
    private var last: String?

    init(onCode: @escaping (String) -> Void) {
        self.onCode = onCode
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("not from a nib") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        view.layer.addSublayer(layer)
        preview = layer
        queue.async { [weak self] in self?.configure() }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview?.frame = view.bounds
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        queue.async { [session] in session.stopRunning() }
    }

    private func configure() {
        session.beginConfiguration()
        if let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
           let input = try? AVCaptureDeviceInput(device: camera), session.canAddInput(input) {
            session.addInput(input)
        }
        let output = AVCaptureMetadataOutput()
        if session.canAddOutput(output) {
            session.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: DispatchQueue.main)
            output.metadataObjectTypes = output.availableMetadataObjectTypes.filter { $0 == .qr }
        }
        session.commitConfiguration()
        session.startRunning()
    }

    /// The delegate queue is the main one, so the payloads are read here and
    /// handed on as plain strings rather than crossing with the objects.
    nonisolated func metadataOutput(_ output: AVCaptureMetadataOutput,
                                    didOutput objects: [AVMetadataObject],
                                    from connection: AVCaptureConnection) {
        let payloads = objects.compactMap { ($0 as? AVMetadataMachineReadableCodeObject)?.stringValue }
        MainActor.assumeIsolated { take(payloads) }
    }

    private func take(_ payloads: [String]) {
        for payload in payloads where payload != last {
            last = payload
            onCode(payload)
        }
    }
}
#endif
