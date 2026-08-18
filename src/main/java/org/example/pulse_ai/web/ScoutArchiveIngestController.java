package org.example.pulse_ai.web;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.config.PulseAdminProperties;
import org.example.pulse_ai.domain.scout.ScoutMessageArchiveService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

/**
 * Ingest от scout-sidecar: копия ЛС (текст/edit/delete/медиа). Не админский SPA.
 */
@RestController
@RequestMapping("/internal/scout-archive")
@RequiredArgsConstructor
public class ScoutArchiveIngestController {

    private final PulseAdminProperties adminProperties;
    private final ScoutMessageArchiveService archiveService;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> ingest(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestBody Map<String, Object> body
    ) {
        requireToken(token);
        archiveService.ingest(body);
        return Map.of("ok", true);
    }

    @PostMapping(value = "/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> ingestMedia(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestPart("meta") String metaJson,
            @RequestPart(value = "file", required = false) MultipartFile file
    ) throws Exception {
        requireToken(token);
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(metaJson, HashMap.class);
        byte[] bytes = file != null && !file.isEmpty() ? file.getBytes() : null;
        String filename = file != null ? file.getOriginalFilename() : null;
        String contentType = file != null ? file.getContentType() : null;
        archiveService.ingestWithMedia(meta, bytes, filename, contentType);
        return Map.of("ok", true);
    }

    private void requireToken(String token) {
        String expected = adminProperties.getWebToken();
        if (expected == null || expected.isBlank() || !expected.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }
}
