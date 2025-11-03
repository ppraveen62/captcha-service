package org.app.captcha.controller;

import org.app.captcha.model.CaptchaChallenge;
import org.app.captcha.service.CaptchaService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Endpoints:
 * GET  /captcha/new?type={type}
 * POST /captcha/verify  (body: { "captchaId": "...", "answer": "..." })
 */
@RestController
@RequestMapping("/captcha")
public class CaptchaController {

    private final CaptchaService svc;

    public CaptchaController(CaptchaService svc) {
        this.svc = svc;
    }

    @GetMapping("/new")
    public ResponseEntity<?> newCaptcha(@RequestParam(name = "type", required = false) String type) {
        try {
            CaptchaChallenge ch = svc.create(type);
            // return id, type, payload (safe to expose)
            return ResponseEntity.ok(Map.of(
                    "captchaId", ch.getId(),
                    "type", ch.getType(),
                    "payload", ch.getPayload(),
                    "expiresAt", ch.getExpiresAt()
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "failed_to_create", "message", e.getMessage()));
        }
    }

    @PostMapping("/verify")
    public ResponseEntity<?> verify(@RequestBody Map<String, String> body) {
        String id = body.get("captchaId");
        String answer = body.get("answer");
        if (id == null || answer == null) return ResponseEntity.badRequest().body(Map.of("error", "missing_parameters"));
        boolean ok = svc.validate(id, answer);
        return ResponseEntity.ok(Map.of("success", ok));
    }
}
