package eu.sovereigntylens.adapter.web;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import eu.sovereigntylens.config.AppProperties;
import eu.sovereigntylens.contract.ApiErrorResponse;
import eu.sovereigntylens.domain.DomainException;
import eu.sovereigntylens.domain.model.DomainErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Renders the QR codes projected on stage.
 *
 * <p>This endpoint is what lets phones join the demo, and it can only work if
 * {@code APP_PUBLIC_BASE_URL} is an address the audience's phones can actually reach: a LAN address
 * of the presenting machine, or a public URL. A code encoding {@code localhost} scans perfectly and
 * then opens nothing, because {@code localhost} on a phone is the phone. There is no way for the
 * server to detect that mistake - the value looks valid - so it has to be got right at deployment.
 *
 * <p>The image is generated per request rather than cached: the base URL is deployment-specific and
 * a code left over from a previous venue would point the room at an unreachable host.
 */
@RestController
@Tag(name = "QR", description = "Scannable codes for the audience form and the presentation view")
public class QrController {

  /** Below this a projected code is unreadable from the back of a room. */
  private static final int MIN_SIZE = 128;

  /** Above this the response is large for no gain: a QR code is a grid, not a photograph. */
  private static final int MAX_SIZE = 2048;

  private static final int DEFAULT_SIZE = 512;

  /**
   * Level M tolerates roughly 15% damage. Enough for a projector's glare and a phone held at an
   * angle, without inflating the module count the way H would.
   */
  private static final ErrorCorrectionLevel ERROR_CORRECTION = ErrorCorrectionLevel.M;

  /**
   * Quiet zone in modules. Scanners need clear space around the finder patterns; two modules is the
   * practical minimum against a busy slide background.
   */
  private static final int QUIET_ZONE_MODULES = 2;

  private static final MediaType SVG = new MediaType("image", "svg+xml", StandardCharsets.UTF_8);

  private final AppProperties properties;

  public QrController(AppProperties properties) {
    this.properties = properties;
  }

  @Operation(
      summary = "QR code image",
      description =
          "Encodes the audience form URL or the presentation URL derived from APP_PUBLIC_BASE_URL."
              + " That base URL must be reachable from the audience's phones - a LAN or public"
              + " address, never localhost.")
  @ApiResponse(responseCode = "200", description = "The image, as PNG or SVG")
  @ApiResponse(
      responseCode = "400",
      description = "VALIDATION_ERROR: unknown target or format",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  @GetMapping("/api/qr")
  public ResponseEntity<byte[]> qr(
      @Parameter(description = "Which page the code opens", schema = @Schema(allowableValues = {"contribute", "present"}))
          @RequestParam(defaultValue = "contribute")
          String target,
      @Parameter(description = "Image format", schema = @Schema(allowableValues = {"png", "svg"}))
          @RequestParam(defaultValue = "png")
          String format,
      @Parameter(description = "Edge length in pixels, clamped to 128-2048")
          @RequestParam(defaultValue = "" + DEFAULT_SIZE)
          int size) {

    Target resolvedTarget = Target.fromParameter(target);
    Format resolvedFormat = Format.fromParameter(format);
    // Clamped rather than rejected: a presenter tweaking the size in a URL during a talk should get
    // a usable code, not an error page.
    int pixels = Math.clamp(size, MIN_SIZE, MAX_SIZE);
    String content = resolvedTarget.url(properties);

    byte[] body =
        resolvedFormat == Format.PNG ? png(content, pixels) : svg(content, pixels).getBytes(StandardCharsets.UTF_8);

    return ResponseEntity.ok()
        .contentType(resolvedFormat == Format.PNG ? MediaType.IMAGE_PNG : SVG)
        .cacheControl(CacheControl.noStore())
        .body(body);
  }

  private byte[] png(String content, int pixels) {
    BitMatrix matrix = encode(content, pixels);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      MatrixToImageWriter.writeToStream(matrix, "PNG", out);
    } catch (IOException e) {
      throw new DomainException(
          DomainErrorCode.INTERNAL_ERROR, "The QR code could not be rendered.", null, e);
    }
    return out.toByteArray();
  }

  /**
   * Draws the module grid as a single path. One rect per dark module would produce a few thousand
   * elements; a path keeps the file small enough to inline in a slide, and the SVG scales to any
   * projector without the resampling blur a PNG would show.
   */
  private String svg(String content, int pixels) {
    // Requesting a 1x1 image makes ZXing fall back to one pixel per module, which is exactly the
    // module grid the path needs, quiet zone included.
    BitMatrix matrix = encode(content, 1);
    int width = matrix.getWidth();
    int height = matrix.getHeight();

    StringBuilder path = new StringBuilder();
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        if (matrix.get(x, y)) {
          path.append('M').append(x).append(' ').append(y).append("h1v1h-1z");
        }
      }
    }

    return """
        <svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d" \
        shape-rendering="crispEdges" role="img" aria-label="QR code for %s">\
        <rect width="%d" height="%d" fill="#ffffff"/>\
        <path fill="#000000" d="%s"/></svg>
        """
        .formatted(pixels, pixels, width, height, escape(content), width, height, path);
  }

  private BitMatrix encode(String content, int pixels) {
    Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
    hints.put(EncodeHintType.ERROR_CORRECTION, ERROR_CORRECTION);
    hints.put(EncodeHintType.MARGIN, QUIET_ZONE_MODULES);
    hints.put(EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name());
    try {
      return new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, pixels, pixels, hints);
    } catch (WriterException e) {
      throw new DomainException(
          DomainErrorCode.INTERNAL_ERROR, "The QR code could not be encoded.", null, e);
    }
  }

  /** The URL reaches an XML attribute, and a configured base URL may legitimately contain "&". */
  private static String escape(String value) {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }

  /** Page a scanned code opens. */
  private enum Target {
    CONTRIBUTE("contribute"),
    PRESENT("present");

    private final String parameter;

    Target(String parameter) {
      this.parameter = parameter;
    }

    String url(AppProperties properties) {
      return this == CONTRIBUTE ? properties.contributeUrl() : properties.presentUrl();
    }

    static Target fromParameter(String value) {
      for (Target candidate : values()) {
        if (candidate.parameter.equals(value)) {
          return candidate;
        }
      }
      throw DomainException.validation(
          "Unknown QR target. Expected \"contribute\" or \"present\".", "target");
    }
  }

  /** Image encoding of the returned code. */
  private enum Format {
    PNG("png"),
    SVG("svg");

    private final String parameter;

    Format(String parameter) {
      this.parameter = parameter;
    }

    static Format fromParameter(String value) {
      for (Format candidate : values()) {
        if (candidate.parameter.equals(value)) {
          return candidate;
        }
      }
      throw DomainException.validation(
          "Unknown QR format. Expected \"png\" or \"svg\".", "format");
    }
  }
}
