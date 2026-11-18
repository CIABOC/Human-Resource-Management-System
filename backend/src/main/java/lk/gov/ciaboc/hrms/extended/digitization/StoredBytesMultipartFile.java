package lk.gov.ciaboc.hrms.extended.digitization;

import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

/**
 * Presents an already stored, QA-verified scan as a {@link MultipartFile} so it can be filed through the employee
 * module's own upload path ({@code EmployeeService.upload}) with all of its validation, classification checks and
 * audit, instead of writing employee_document rows directly.
 */
final class StoredBytesMultipartFile implements MultipartFile {
    private final String originalName;
    private final String contentType;
    private final byte[] bytes;

    StoredBytesMultipartFile(String originalName, String contentType, byte[] bytes) {
        this.originalName = originalName;
        this.contentType = contentType;
        this.bytes = bytes;
    }

    @Override
    public String getName() {
        return "file";
    }

    @Override
    public String getOriginalFilename() {
        return originalName;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public boolean isEmpty() {
        return bytes.length == 0;
    }

    @Override
    public long getSize() {
        return bytes.length;
    }

    @Override
    public byte[] getBytes() {
        return bytes.clone();
    }

    @Override
    public InputStream getInputStream() {
        return new ByteArrayInputStream(bytes);
    }

    @Override
    public void transferTo(File dest) throws IOException {
        Files.write(dest.toPath(), bytes);
    }
}
