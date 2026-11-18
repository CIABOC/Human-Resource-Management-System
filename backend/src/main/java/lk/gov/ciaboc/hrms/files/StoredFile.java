package lk.gov.ciaboc.hrms.files;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Metadata for an uploaded file. Access is always decided by the owning module; there is deliberately no generic
 * download endpoint.
 */
@Getter
@Setter
@Entity
@Table(name = "stored_file")
public class StoredFile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    @Column(name = "content_type", length = 120)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "storage_key", nullable = false, unique = true, length = 300)
    private String storageKey;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(nullable = false, length = 40)
    private String module;

    @Column(name = "scan_status", nullable = false, length = 20)
    private String scanStatus;

    @Column(name = "uploaded_by", nullable = false, length = 100)
    private String uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;
}
