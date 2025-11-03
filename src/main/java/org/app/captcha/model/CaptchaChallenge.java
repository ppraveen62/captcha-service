package org.app.captcha.model;

import java.time.Instant;
import java.util.Map;

/**
 * CaptchaChallenge container:
 * - id: challenge id
 * - type: "text-image" | "math" | "image-grid" | "slider" | "audio"
 * - payload: metadata returned to client (e.g. base64 image, instructions, tiles)
 * - solution: canonical solution string (how validate expects answer)
 * - expiresAt: expiry instant
 */
public class CaptchaChallenge {
    private final String id;
    private final String type;
    private final Map<String, Object> payload;
    private final String solution;
    private final Instant expiresAt;

    public CaptchaChallenge(String id, String type, Map<String, Object> payload, String solution, Instant expiresAt) {
        this.id = id;
        this.type = type;
        this.payload = payload;
        this.solution = solution;
        this.expiresAt = expiresAt;
    }

    public String getId() { return id; }
    public String getType() { return type; }
    public Map<String, Object> getPayload() { return payload; }
    public String getSolution() { return solution; }
    public Instant getExpiresAt() { return expiresAt; }
}
