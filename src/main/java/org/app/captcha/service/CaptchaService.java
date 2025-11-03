package org.app.captcha.service;

import jakarta.annotation.PostConstruct;
import org.app.captcha.model.CaptchaChallenge;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.ResourceLoader;

import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.CubicCurve2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URL;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Production-ready CaptchaService:
 * - discovers images from classpath:/static/captcha-grid/* and classpath:/static/captcha-bg/*
 * - preloads images into memory (resized) to avoid runtime I/O
 * - closes all resource streams
 */
@Service
public class CaptchaService {

    private final Map<String, CaptchaChallenge> store = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor();

    @Value("${captcha.expiry-seconds:120}")
    private long expirySeconds;

    @Value("${captcha.cleanup-interval-seconds:60}")
    private long cleanupInterval;

    private final Random rnd = new Random();

    // caches
    private final Map<String, List<ImageEntry>> gridCache = new ConcurrentHashMap<>(); // category -> tiles
    private final List<BufferedImage> sliderBackgrounds = new CopyOnWriteArrayList<>(); // preloaded slider bg images

    // tile size for grid and slider preload
    private final int tileSize = 200;
    private final int sliderWidth = 300, sliderHeight = 150;

    // Resource resolver (works in jar)
    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    public CaptchaService(@Value("${captcha.cleanup-interval-seconds:60}") long cleanupIntervalSeconds) {
        cleaner.scheduleAtFixedRate(this::cleanup, cleanupIntervalSeconds, cleanupIntervalSeconds, TimeUnit.SECONDS);
    }

    private void cleanup() {
        Instant now = Instant.now();
        for (Map.Entry<String, CaptchaChallenge> e : new ArrayList<>(store.entrySet())) {
            if (e.getValue().getExpiresAt().isBefore(now)) store.remove(e.getKey());
        }
    }

    // ----------------- Preload at startup -----------------

    private static record ImageEntry(String base64, String filename, String category) {}

    @PostConstruct
    private void preloadAll() {
        try {
            preloadGridImages();
            preloadSliderBackgrounds();
            preloadAudioList(); // ensure audio resources present (no heavy caching for audio)
            System.out.println("CaptchaService: Preload complete. categories=" + gridCache.keySet());
        } catch (Exception e) {
            System.err.println("CaptchaService: Preload failed: " + e.getMessage());
        }
    }

    private void preloadGridImages() throws IOException {
        // gather jpg, jpeg, png under static/captcha-grid/*/*
        List<Resource> resources = new ArrayList<>();
        resources.addAll(List.of(resolver.getResources("classpath:/static/captcha-grid/*/*.jpg")));
        resources.addAll(List.of(resolver.getResources("classpath:/static/captcha-grid/*/*.jpeg")));
        resources.addAll(List.of(resolver.getResources("classpath:/static/captcha-grid/*/*.png")));

        // group by category (path contains /static/captcha-grid/{category}/filename)
        Map<String, List<Resource>> grouped = new HashMap<>();
        for (Resource r : resources) {
            String cat = resourceCategory(r, "/static/captcha-grid/");
            if (cat == null) continue;
            grouped.computeIfAbsent(cat, k -> new ArrayList<>()).add(r);
        }

        for (Map.Entry<String, List<Resource>> e : grouped.entrySet()) {
            String cat = e.getKey();
            List<ImageEntry> entries = new ArrayList<>();
            for (Resource res : e.getValue()) {
                try (InputStream in = res.getInputStream()) {
                    BufferedImage orig = ImageIO.read(in);
                    if (orig == null) continue;
                    BufferedImage resized = resizeCover(orig, tileSize, tileSize);
                    String fmt = guessFormat(res.getFilename());
                    byte[] bytes = bufferedImageToBytes(resized, fmt);
                    String mime = fmt.equals("png") ? "image/png" : "image/jpeg";
                    String b64 = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
                    entries.add(new ImageEntry(b64, res.getFilename(), cat));
                } catch (Exception ex) {
                    System.err.println("CaptchaService: preloadGridImages failed for " + res + " -> " + ex.getMessage());
                }
            }
            if (!entries.isEmpty()) {
                gridCache.put(cat, Collections.unmodifiableList(entries));
            }
        }
    }

    private void preloadSliderBackgrounds() throws IOException {
        List<Resource> resources = new ArrayList<>();
        resources.addAll(List.of(resolver.getResources("classpath:/static/captcha-bg/*.jpg")));
        resources.addAll(List.of(resolver.getResources("classpath:/static/captcha-bg/*.jpeg")));
        resources.addAll(List.of(resolver.getResources("classpath:/static/captcha-bg/*.png")));

        for (Resource r : resources) {
            try (InputStream in = r.getInputStream()) {
                BufferedImage orig = ImageIO.read(in);
                if (orig == null) continue;
                BufferedImage resized = resizeCover(orig, sliderWidth, sliderHeight);
                sliderBackgrounds.add(resized);
            } catch (Exception ex) {
                System.err.println("CaptchaService: preloadSliderBackgrounds failed for " + r + " -> " + ex.getMessage());
            }
        }
    }

    private void preloadAudioList() {
        // check presence of wav files but don't fully load them to memory
        try {
            Resource[] wavs = resolver.getResources("classpath:/static/audio/*.wav");
            if (wavs == null || wavs.length == 0) {
                System.out.println("CaptchaService: No audio digits found under /static/audio/");
            } else {
                System.out.println("CaptchaService: found " + wavs.length + " audio digit files");
            }
        } catch (IOException e) {
            System.err.println("CaptchaService: preloadAudioList error: " + e.getMessage());
        }
    }

    // ----------------- public API -----------------

    /**
     * Generate a captcha by type.
     * Supported types: text-image, math, image-grid, slider, static/audio
     */
    public CaptchaChallenge create(String type) throws Exception {
        return switch (Optional.ofNullable(type).orElse("text-image")) {
            case "math" -> createMath();
            case "image-grid" -> createImageGrid();
            case "slider" -> createSlider();
            case "audio" -> createAudio();
            default -> createTextImage();
        };
    }

    // 1) text-image
    private CaptchaChallenge createTextImage() throws Exception {
        String code = randomAlphaNumeric(5);
        String id = id();
        String b64 = renderTextImageBase64(code);
        Map<String, Object> payload = Map.of("imageBase64", b64, "instructions", "Enter the characters you see");
        Instant exp = Instant.now().plusSeconds(expirySeconds);
        CaptchaChallenge ch = new CaptchaChallenge(id, "text-image", payload, code, exp);
        store.put(id, ch);
        return ch;
    }

    // 2) math
    private CaptchaChallenge createMath() {
        int a = rnd.nextInt(9) + 1;
        int b = rnd.nextInt(9) + 1;
        int t = rnd.nextInt(3);
        char op; int res;
        if (t == 0) { op = '+'; res = a + b; }
        else if (t == 1) { op = '-'; res = a - b; }
        else { op = '*'; res = a * b; }
        String question = a + " " + op + " " + b + " = ?";
        String solution = String.valueOf(res);
        String id = id();
        Map<String, Object> payload = Map.of("question", question);
        Instant exp = Instant.now().plusSeconds(expirySeconds);
        CaptchaChallenge ch = new CaptchaChallenge(id, "math", payload, solution, exp);
        store.put(id, ch);
        return ch;
    }

    // 3) image-grid using preloaded gridCache
    private CaptchaChallenge createImageGrid() {
        String id = id();

        if (gridCache.isEmpty()) {
            throw new IllegalStateException("No preloaded captcha-grid images available under /static/captcha-grid/");
        }

        // pick a category
        List<String> categories = new ArrayList<>(gridCache.keySet());
        String category = categories.get(rnd.nextInt(categories.size()));
        List<ImageEntry> pool = gridCache.get(category);
        if (pool == null || pool.isEmpty()) throw new IllegalStateException("No images in category " + category);

        // pick up to 3 correct
        List<ImageEntry> correct = new ArrayList<>(pool);
        Collections.shuffle(correct, rnd);
        correct = correct.subList(0, Math.min(3, correct.size()));

        // collect distractors from other categories
        List<ImageEntry> distractorsPool = new ArrayList<>();
        for (String other : gridCache.keySet()) {
            if (!other.equals(category)) distractorsPool.addAll(gridCache.get(other));
        }
        Collections.shuffle(distractorsPool, rnd);
        List<ImageEntry> distractors = distractorsPool.stream().limit(Math.min(3, distractorsPool.size())).collect(Collectors.toList());

        // if not enough distractors, fill from same pool (skip already picked)
        if (distractors.size() < 3) {
            List<ImageEntry> remaining = new ArrayList<>(pool);
            remaining.removeAll(correct);
            Collections.shuffle(remaining, rnd);
            for (ImageEntry e : remaining) {
                if (distractors.size() >= 3) break;
                distractors.add(e);
            }
        }

        List<ImageEntry> finalGrid = new ArrayList<>();
        finalGrid.addAll(correct);
        finalGrid.addAll(distractors);
        Collections.shuffle(finalGrid, rnd);

        Map<String, String> tiles = new LinkedHashMap<>();
        List<String> correctIds = new ArrayList<>();
        for (int i = 0; i < finalGrid.size(); i++) {
            ImageEntry e = finalGrid.get(i);
            tiles.put("t" + i, e.base64());
            if (e.category().equals(category)) correctIds.add("t" + i);
        }

        String solution = String.join(",", correctIds);
        Map<String, Object> payload = Map.of("tiles", tiles, "instructions", "Select all images containing " + category.replace("_", " "));
        Instant exp = Instant.now().plusSeconds(expirySeconds);
        CaptchaChallenge ch = new CaptchaChallenge(id, "image-grid", payload, solution, exp);
        store.put(id, ch);
        return ch;
    }

    // 4) slider using preloaded backgrounds
    private CaptchaChallenge createSlider() throws Exception {
        if (sliderBackgrounds.isEmpty()) {
            throw new IllegalStateException("No slider backgrounds found under /static/captcha-bg/");
        }

        int pieceW = 50, pieceH = 50;
        // pick background
        BufferedImage bgOrig = sliderBackgrounds.get(rnd.nextInt(sliderBackgrounds.size()));
        // clone so we can draw hole overlay
        BufferedImage bg = new BufferedImage(bgOrig.getWidth(), bgOrig.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = bg.createGraphics();
        g.drawImage(bgOrig, 0, 0, null);
        g.dispose();

        int width = bg.getWidth(), height = bg.getHeight();

        int pieceY = 30 + rnd.nextInt(Math.max(1, height - pieceH - 30));
        int offsetX = 50 + rnd.nextInt(Math.max(1, width - pieceW - 100));

        Shape puzzleShape = createPuzzleShape(pieceW, pieceH);

        // piece image
        BufferedImage piece = new BufferedImage(pieceW, pieceH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = piece.createGraphics();
            pg.setClip(puzzleShape);
            pg.drawImage(bg, -offsetX, -pieceY, null);
            pg.setClip(null);
            pg.setColor(new Color(0, 0, 0, 70));
            pg.setStroke(new BasicStroke(2f));
            pg.draw(puzzleShape);


        // hole overlay on bg
        Graphics2D gHole = bg.createGraphics();
            gHole.setColor(new Color(255, 255, 255, 199));
            gHole.fill(puzzleShapeAt(offsetX, pieceY, pieceW, pieceH));


        String bgB64 = imageToBase64(bg, "png");
        String pieceB64 = imageToBase64(piece, "png");

        String id = id();
        Map<String, Object> payload = Map.of(
                "bgImageBase64", bgB64,
                "pieceImageBase64", pieceB64,
                "pieceY", pieceY,
                "pieceWidth", pieceW,
                "instructions", "Slide the puzzle piece to complete the image"
        );

        String solution = String.valueOf(offsetX);
        Instant exp = Instant.now().plusSeconds(expirySeconds);
        CaptchaChallenge ch = new CaptchaChallenge(id, "slider", payload, solution, exp);
        store.put(id, ch);
        return ch;
    }

    // 5) audio captcha using WAV files from classpath:/static/audio/
    private CaptchaChallenge createAudio() throws Exception {
        String id = id();
        String code = String.format("%04d", rnd.nextInt(10000));
        byte[] audioBytes = synthesizeAudio(code);
        String base64Audio = Base64.getEncoder().encodeToString(audioBytes);

        Map<String, Object> payload = Map.of(
                "audioBase64", base64Audio,
                "mimeType", "audio/wav",
                "instructions", "Listen to the audio and enter the spoken digits"
        );

        Instant exp = Instant.now().plusSeconds(expirySeconds);
        CaptchaChallenge ch = new CaptchaChallenge(id, "audio", payload, code, exp);
        store.put(id, ch);
        return ch;
    }

    // ------------- validation --------------

    public boolean validate(String id, String userAnswer) {
        CaptchaChallenge ch = store.get(id);
        if (ch == null) return false;
        if (ch.getExpiresAt().isBefore(Instant.now())) {
            store.remove(id);
            return false;
        }
        String type = ch.getType();
        String sol = ch.getSolution();
        boolean ok = false;
        if ("image-grid".equals(type)) {
            Set<String> expected = new HashSet<>(List.of(sol.split(",")));
            Set<String> got = new HashSet<>();
            for (String s : Optional.ofNullable(userAnswer).orElse("").split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) got.add(t);
            }
            ok = expected.equals(got);
        } else if ("slider".equals(type)) {
            try {
                int expected = Integer.parseInt(sol);
                int provided = Integer.parseInt(userAnswer.trim());
                ok = Math.abs(expected - provided) <= 6;
            } catch (Exception e) { ok = false; }
        } else {
            if (sol == null) ok = false;
            else ok = sol.equalsIgnoreCase(Optional.ofNullable(userAnswer).orElse("").trim());
        }

        if (ok) store.remove(id);
        return ok;
    }

    // -------------- helpers -----------------

    private String id() { return UUID.randomUUID().toString(); }

    private String randomAlphaNumeric(int len) {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(chars.charAt(rnd.nextInt(chars.length())));
        return sb.toString();
    }

    private byte[] bufferedImageToBytes(BufferedImage img, String fmt) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, fmt, baos);
        return baos.toByteArray();
    }

    private String guessFormat(String filename) {
        if (filename == null) return "jpg";
        String n = filename.toLowerCase();
        if (n.endsWith(".png")) return "png";
        return "jpg";
    }

    private BufferedImage resizeCover(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        double sx = (double) w / src.getWidth();
        double sy = (double) h / src.getHeight();
        double s = Math.max(sx, sy);
        int nw = (int) Math.round(src.getWidth() * s);
        int nh = (int) Math.round(src.getHeight() * s);
        int x = (w - nw) / 2;
        int y = (h - nh) / 2;
        g.drawImage(src, x, y, nw, nh, null);
        g.dispose();
        return out;
    }

    private String imageToBase64(BufferedImage img, String fmt) throws Exception {
        byte[] bytes = bufferedImageToBytes(img, fmt);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private String resourceCategory(Resource r, String marker) {
        try {
            URL url = r.getURL();
            String s = url.toString();
            int idx = s.indexOf(marker);
            if (idx == -1) return null;
            int start = idx + marker.length();
            int slash = s.indexOf('/', start);
            if (slash == -1) return null;
            return s.substring(start, slash);
        } catch (IOException e) {
            return null;
        }
    }

    private byte[] synthesizeAudio(String text) throws Exception {
        List<AudioInputStream> streams = new ArrayList<>();
        // open AudioInputStream for each digit using classpath resource and keep closed properly
        for (char c : text.toCharArray()) {
            Resource r = resolver.getResource("classpath:/static/audio/" + c + ".wav");
            if (r.exists()) {
                try (InputStream in = r.getInputStream()) {
                    AudioInputStream ais = AudioSystem.getAudioInputStream(in);
                    // must copy ais content because closing 'in' will close stream; so read bytes and create new stream
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    AudioSystem.write(ais, AudioFileFormat.Type.WAVE, baos);
                    byte[] data = baos.toByteArray();
                    // create AIS again from bytes
                    try (InputStream bais = new java.io.ByteArrayInputStream(data)) {
                        AudioInputStream ais2 = AudioSystem.getAudioInputStream(bais);
                        streams.add(ais2);
                    }
                    ais.close();
                } catch (Exception ex) {
                    System.err.println("CaptchaService: synthesizeAudio failed for digit " + c + " -> " + ex.getMessage());
                }
            } else {
                System.err.println("CaptchaService: audio digit not found: " + c);
            }
        }

        if (streams.isEmpty()) throw new IllegalStateException("No audio digit files found");

        // combine streams (we already loaded them as AudioInputStream instances)
        AudioInputStream combined = streams.get(0);
        for (int i = 1; i < streams.size(); i++) {
            AudioInputStream next = streams.get(i);
            combined = new AudioInputStream(
                    new SequenceInputStream(combined, next),
                    combined.getFormat(),
                    combined.getFrameLength() + next.getFrameLength()
            );
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        AudioSystem.write(combined, AudioFileFormat.Type.WAVE, baos);

        // close component streams
        for (AudioInputStream ais : streams) {
            try { ais.close(); } catch (IOException ignore) {}
        }
        try { combined.close(); } catch (IOException ignore) {}

        return baos.toByteArray();
    }

    private Shape createPuzzleShape(int w, int h) {
        int notchRadius = 8 + rnd.nextInt(5);
        int notchX = rnd.nextInt(Math.max(1, w / 2)) + w / 4;
        int notchY = h / 2;

        Path2D path = new Path2D.Float();
        path.moveTo(0, 0);
        path.lineTo(w, 0);
        path.lineTo(w, h);
        path.lineTo(0, h);
        path.closePath();
        path.append(new Ellipse2D.Float(notchX - notchRadius, notchY - notchRadius, notchRadius * 2, notchRadius * 2), false);
        return path;
    }

    private Shape puzzleShapeAt(int x, int y, int w, int h) {
        AffineTransform at = AffineTransform.getTranslateInstance(x, y);
        return at.createTransformedShape(createPuzzleShape(w, h));
    }

    // -------------- image text generator (unchanged) -----------------

    private byte[] generateCaptchaImage(String captchaText) throws IOException {
        int width = 200;
        int height = 50;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();

        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // subtle gradient background
        GradientPaint gp = new GradientPaint(
                0, 0, Color.WHITE,
                width, height, getRandomLightColor()
        );
        g2d.setPaint(gp);
        g2d.fillRect(0, 0, width, height);

        Font bigFont = new Font("Dialog", Font.BOLD, 36);
        int charCount = captchaText.length();
        int padding = 10;
        int availableWidth = width - 2 * padding;
        int charSpacing = availableWidth / charCount;
        Random random = new Random();

        for (int i = 0; i < charCount; i++) {
            g2d.setFont(bigFont);
            g2d.setColor(randomColor(random, 0, 100));

            double rotation = (random.nextInt(11) - 5) * Math.PI / 180;
            int x = padding + i * charSpacing + charSpacing / 4;
            int y = 40;

            g2d.translate(x, y);
            g2d.rotate(rotation);
            g2d.drawString(String.valueOf(captchaText.charAt(i)), 0, 0);
            g2d.rotate(-rotation);
            g2d.translate(-x, -y);
        }

        // fine noise lines
        for (int i = 0; i < 4; i++) {
            g2d.setColor(randomColor(random, 180, 220));
            int x1 = random.nextInt(width);
            int y1 = random.nextInt(height);
            int x2 = random.nextInt(width);
            int y2 = random.nextInt(height);
            g2d.drawLine(x1, y1, x2, y2);
        }

        // soft cubic noise
        for (int i = 0; i < 1; i++) {
            g2d.setColor(randomColor(random, 150, 200));
            int y1 = random.nextInt(height);
            int y2 = random.nextInt(height);
            CubicCurve2D c = new CubicCurve2D.Float(
                    0, y1,
                    width / 3f, random.nextInt(height),
                    2 * width / 3f, random.nextInt(height),
                    width, y2
            );
            g2d.draw(c);
        }

        // random noise dots
        int dots = (int) (width * height * 0.015);
        for (int i = 0; i < dots; i++) {
            g2d.setColor(randomColor(random, 0, 255));
            g2d.fillRect(random.nextInt(width), random.nextInt(height), 1, 1);
        }

        g2d.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        return baos.toByteArray();
    }

    private String renderTextImageBase64(String text) throws IOException {
        byte[] imageBytes = generateCaptchaImage(text);
        return Base64.getEncoder().encodeToString(imageBytes);
    }

    private Color randomColor(Random random, int min, int max) {
        if (min > 255) min = 255;
        if (max > 255) max = 255;
        int r = min + random.nextInt(Math.max(1, max - min));
        int g = min + random.nextInt(Math.max(1, max - min));
        int b = min + random.nextInt(Math.max(1, max - min));
        return new Color(r, g, b);
    }


    private Color getRandomLightColor() {
        ThreadLocalRandom rand = ThreadLocalRandom.current();
        int r = 180 + rand.nextInt(76); // 180–255
        int g = 180 + rand.nextInt(76);
        int b = 180 + rand.nextInt(76);
        return new Color(r, g, b);
    }

}
