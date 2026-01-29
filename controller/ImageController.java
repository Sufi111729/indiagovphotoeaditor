package com.sufi.indgoveservices.controller;

import com.sufi.indgoveservices.services.ImageService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Base64;

@Controller
public class ImageController {

    private final ImageService imageService;

    public ImageController(ImageService imageService) {
        this.imageService = imageService;
    }

    @GetMapping("/")
    public String index(Model model) {
        // model can carry server-side messages if needed in future
        return "index";
    }

    /**
     * Handles direct file upload and immediate processing (legacy/simple path).
     * Accepts optional 'authority' to determine output filename (nsdl|uti). Validates that the uploaded file is a JPEG.
     */
    @PostMapping(path = "/upload")
    public ResponseEntity<byte[]> upload(@RequestParam("file") MultipartFile file,
                                         @RequestParam("type") String type,
                                         @RequestParam(value = "authority", required = false) String authority) {
        try {
            if (file == null || file.isEmpty()) {
                return ResponseEntity.badRequest()
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("No file uploaded".getBytes());
            }

            String originalFilename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
            String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase();

            // Server-side: only accept JPEG/JPG
            boolean isJpegByName = originalFilename.endsWith(".jpg") || originalFilename.endsWith(".jpeg");
            boolean isJpegByType = contentType.equals("image/jpeg") || contentType.equals("image/jpg");
            if (!(isJpegByName || isJpegByType)) {
                return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("Only JPG/JPEG images are accepted. Please convert your image to JPG and try again.".getBytes());
            }

            int width;
            int height;
            float maxKB;
            String outFilename;

            boolean isPhoto = "photo".equalsIgnoreCase(type);
            boolean isSignature = "signature".equalsIgnoreCase(type) || "sign".equalsIgnoreCase(type);

            if (isPhoto) {
                // PAN Photograph rules: pixel dimensions (width, height) — width FIRST then height.
                width = 197; // pixels (width)
                height = 276; // pixels (height)
                maxKB = 50f; // NSDL max 50 KB for photo
            } else if (isSignature) {
                // Signature rules: pixel dimensions (width, height) — width FIRST then height.
                width = 354; // pixels (width)
                height = 157; // pixels (height)
                maxKB = 20f;
            } else {
                return ResponseEntity.badRequest()
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("Invalid type. Use 'photo' or 'signature'.".getBytes());
            }

            // Normalize authority into 'nsdl' or 'uti'
            String auth = (authority == null || authority.isBlank()) ? "nsdl" : authority.toLowerCase();
            if (!auth.equals("nsdl") && !auth.equals("uti") && !auth.equals("uts")) {
                auth = "nsdl";
            }
            String authorityKey = (auth.equals("uti") || auth.equals("uts")) ? "uti" : "nsdl";
            String typeKey = isPhoto ? "photo" : "signature";
            outFilename = String.format("%s_%s.jpg", authorityKey, typeKey);

            try (InputStream is = file.getInputStream()) {
                byte[] output = imageService.processImage(is, width, height, maxKB);

                double finalSizeKB = output.length / 1024.0;

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.IMAGE_JPEG);
                headers.setContentDispositionFormData("attachment", outFilename);
                headers.add("X-Final-Size-KB", String.format("%.2f", finalSizeKB));

                return new ResponseEntity<>(output, headers, HttpStatus.OK);
            }

        } catch (Exception ex) {
            String msg = "Failed to process image: " + ex.getMessage();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(msg.getBytes());
        }
    }

    /**
     * Endpoint for the canvas editor: accepts a JSON body containing a data URL (base64 JPEG)
     * and processing parameters (authority, type, cropMode). Returns processed JPEG as download.
     */
    @PostMapping(path = "/process", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> processFromCanvas(@RequestBody ProcessRequest req) {
        try {
            if (req == null || req.dataUrl == null || req.dataUrl.isBlank()) {
                return ResponseEntity.badRequest()
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("No image data provided".getBytes());
            }

            String dataUrl = req.dataUrl;
            // Expected format: data:image/jpeg;base64,/9j/...
            int comma = dataUrl.indexOf(',');
            if (comma < 0) {
                return ResponseEntity.badRequest()
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("Invalid data URL".getBytes());
            }
            String meta = dataUrl.substring(0, comma);
            String b64 = dataUrl.substring(comma + 1);
            if (!meta.toLowerCase().contains("image/")) {
                return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("Only image data URLs are accepted".getBytes());
            }

            byte[] decoded = Base64.getDecoder().decode(b64);
            try (InputStream is = new ByteArrayInputStream(decoded)) {

                int width;
                int height;
                float maxKB;
                String outFilename;

                boolean isPhoto = "photo".equalsIgnoreCase(req.type);
                boolean isSignature = "signature".equalsIgnoreCase(req.type) || "sign".equalsIgnoreCase(req.type);
                boolean centerCrop = "center".equalsIgnoreCase(req.cropMode);

                if (isPhoto) {
                    // width FIRST, height SECOND: 197 x 276
                    width = 197;
                    height = 276;
                    maxKB = 50f;
                } else if (isSignature) {
                    // width FIRST, height SECOND: 354 x 157
                    width = 354;
                    height = 157;
                    maxKB = 20f; // aim for 20KB; processor enforces smaller when possible
                } else {
                    return ResponseEntity.badRequest()
                            .contentType(MediaType.TEXT_PLAIN)
                            .body("Invalid type parameter".getBytes());
                }

                // Determine filename from authority + type
                String auth = (req.authority == null) ? "nsdl" : req.authority.toLowerCase();
                if (!auth.equals("nsdl") && !auth.equals("uti") && !auth.equals("uts")) {
                    auth = "nsdl"; // default
                }
                String authorityKey = auth.equals("uti") || auth.equals("uts") ? "uti" : "nsdl";
                String typeKey = isPhoto ? "photo" : "signature";
                outFilename = String.format("%s_%s.jpg", authorityKey, typeKey);

                byte[] output = imageService.processImage(is, width, height, maxKB, centerCrop);
                double finalSizeKB = output.length / 1024.0;

                // Enforce hard maximum of 50 KB for all outputs
                if (finalSizeKB > 50.0) {
                    String msg = String.format("Unable to compress image below 50 KB (final: %.2f KB). Try using a smaller input or enable more aggressive cropping.", finalSizeKB);
                    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                            .contentType(MediaType.TEXT_PLAIN)
                            .body(msg.getBytes());
                }

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.IMAGE_JPEG);
                headers.setContentDispositionFormData("attachment", outFilename);
                headers.add("X-Final-Size-KB", String.format("%.2f", finalSizeKB));

                return new ResponseEntity<>(output, headers, HttpStatus.OK);
            }

        } catch (IllegalArgumentException iae) {
            // Base64 decode errors
            return ResponseEntity.badRequest()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(("Invalid base64 image data: " + iae.getMessage()).getBytes());
        } catch (Exception ex) {
            String msg = "Failed to process image: " + ex.getMessage();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(msg.getBytes());
        }
    }

    // Simple DTO for JSON request body from editor
    public static class ProcessRequest {
        public String dataUrl;
        public String authority; // nsdl | uti
        public String type; // photo | signature
        public String cropMode; // center | full
    }
}
