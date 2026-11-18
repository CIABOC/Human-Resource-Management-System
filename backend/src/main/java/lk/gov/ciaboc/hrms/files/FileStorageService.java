package lk.gov.ciaboc.hrms.files;

import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.config.AppProperties;
import lk.gov.ciaboc.hrms.security.CurrentUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Stores uploads outside the web root after checking extension, declared/actual type and (optionally) scanning with
 * ClamAV (SYS-14). Encryption at rest is provided by the encrypted storage volume (NFR-SEC-03).
 */
@Slf4j
@Service
public class FileStorageService {
    private final Path root;
    private final Set<String> allowed;
    private final AppProperties.Clamav clamav;
    private final StoredFileRepository repo;

    public FileStorageService(AppProperties props, StoredFileRepository repo) throws IOException {
        this.root = Path.of(props.files().storagePath()).toAbsolutePath().normalize();
        this.allowed = props.files().allowedExtensions().stream().map(s -> s.trim().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        this.clamav = props.files().clamav();
        this.repo = repo;
        Files.createDirectories(root);
    }

    @Transactional
    public StoredFile store(MultipartFile file, String module) {
        if (file == null || file.isEmpty()) throw ApiException.badRequest("No file was uploaded");
        String name = sanitize(file.getOriginalFilename());
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        if (!allowed.contains(ext)) throw ApiException.rule("FILE_TYPE", "File type ." + ext + " is not allowed");
        try {
            byte[] head = new byte[8];
            int n;
            try (InputStream in = file.getInputStream()) {
                n = in.readNBytes(head, 0, head.length);
            }
            if (!magicMatches(ext, head, n)) throw ApiException.rule("FILE_CONTENT", "File content does not match its extension");
            String scan = scan(file);
            String key = LocalDate.now().getYear() + "/" + String.format("%02d", LocalDate.now().getMonthValue()) + "/" + UUID.randomUUID();
            Path target = root.resolve(key).normalize();
            if (!target.startsWith(root)) throw ApiException.badRequest("Invalid storage path");
            Files.createDirectories(target.getParent());
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new java.security.DigestInputStream(file.getInputStream(), md)) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            StoredFile f = new StoredFile();
            f.setOriginalName(name);
            f.setContentType(file.getContentType());
            f.setSizeBytes(file.getSize());
            f.setStorageKey(key);
            f.setSha256(HexFormat.of().formatHex(md.digest()));
            f.setModule(module);
            f.setScanStatus(scan);
            f.setUploadedBy(CurrentUser.username());
            f.setUploadedAt(Instant.now());
            return repo.save(f);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("File storage failed", e);
            throw ApiException.badRequest("The file could not be stored");
        }
    }

    public StoredFile meta(Long id) {
        return repo.findById(id).orElseThrow(() -> ApiException.notFound("File", id));
    }

    /** Builds a download response. Callers must have already authorized access. */
    public ResponseEntity<Resource> download(Long id) {
        StoredFile f = meta(id);
        Path p = root.resolve(f.getStorageKey()).normalize();
        if (!p.startsWith(root) || !Files.exists(p)) throw ApiException.notFound("File content", id);
        MediaType type;
        try {
            type = f.getContentType() == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(f.getContentType());
        } catch (Exception e) {
            type = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(f.getOriginalName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(type)
                .contentLength(f.getSizeBytes())
                .body(new FileSystemResource(p));
    }

    private static String sanitize(String original) {
        String n = original == null ? "file" : Path.of(original.replace('\\', '/')).getFileName().toString();
        n = n.replaceAll("[^\\p{L}\\p{N}._ -]", "_");
        return n.length() > 200 ? n.substring(n.length() - 200) : n;
    }

    private static boolean magicMatches(String ext, byte[] h, int n) {
        return switch (ext) {
            case "pdf" -> n >= 4 && h[0] == '%' && h[1] == 'P' && h[2] == 'D' && h[3] == 'F';
            case "png" -> n >= 4 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G';
            case "jpg", "jpeg" -> n >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF;
            case "docx", "xlsx" -> n >= 2 && h[0] == 'P' && h[1] == 'K';
            case "doc", "xls" -> n >= 4 && (h[0] & 0xFF) == 0xD0 && (h[1] & 0xFF) == 0xCF && (h[2] & 0xFF) == 0x11 && (h[3] & 0xFF) == 0xE0;
            default -> true; // text formats
        };
    }

    /** ClamAV INSTREAM scan. Returns CLEAN / NOT_SCANNED; throws if infected or the scanner is enabled but unreachable. */
    private String scan(MultipartFile file) throws IOException {
        if (clamav == null || !clamav.enabled()) return "NOT_SCANNED";
        try (Socket s = new Socket(clamav.host(), clamav.port());
             OutputStream out = new BufferedOutputStream(s.getOutputStream());
             InputStream in = file.getInputStream();
             BufferedReader reply = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII))) {
            s.setSoTimeout(30_000);
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(new byte[]{(byte) (r >>> 24), (byte) (r >>> 16), (byte) (r >>> 8), (byte) r});
                out.write(buf, 0, r);
            }
            out.write(new byte[]{0, 0, 0, 0});
            out.flush();
            String result = reply.readLine();
            if (result != null && result.contains("FOUND")) {
                log.warn("Infected upload rejected: {}", result);
                throw ApiException.rule("FILE_INFECTED", "The file was rejected by the virus scanner");
            }
            if (result == null || !result.contains("OK")) throw ApiException.rule("SCAN_FAILED", "The virus scanner could not verify the file");
            return "CLEAN";
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            log.error("ClamAV unavailable", e);
            throw ApiException.rule("SCAN_UNAVAILABLE", "The virus scanner is unavailable; please try again later");
        }
    }
}
