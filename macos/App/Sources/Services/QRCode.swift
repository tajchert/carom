import AppKit
import CoreImage
import CoreImage.CIFilterBuiltins
import Vision

/// QR codes: CoreImage to draw them, Vision to read them from images.
enum QRCode {
    /// A crisp QR image for `text` (never log the text: pair URIs are secrets).
    static func image(for text: String, scale: CGFloat = 10) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: scale, y: scale)) else {
            return nil
        }
        let context = CIContext(options: [.useSoftwareRenderer: false])
        guard let cgImage = context.createCGImage(output, from: output.extent) else { return nil }
        return NSImage(cgImage: cgImage, size: NSSize(width: output.extent.width / 2, height: output.extent.height / 2))
    }

    /// Every QR payload found in the image, in Vision's order.
    static func decode(_ image: NSImage) -> [String] {
        var rect = CGRect(origin: .zero, size: image.size)
        guard let cgImage = image.cgImage(forProposedRect: &rect, context: nil, hints: nil) else { return [] }
        return decode(cgImage)
    }

    static func decode(_ cgImage: CGImage) -> [String] {
        let request = VNDetectBarcodesRequest()
        request.symbologies = [.qr]
        let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
        do { try handler.perform([request]) } catch { return [] }
        return (request.results ?? []).compactMap(\.payloadStringValue)
    }

    /// The first `carom://` URI in the image, if any.
    static func caromURI(in image: NSImage) -> String? {
        decode(image).first { $0.lowercased().hasPrefix("carom://") }
    }
}
