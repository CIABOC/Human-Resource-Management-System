package lk.gov.ciaboc.hrms.reports;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import lk.gov.ciaboc.hrms.masterdata.SettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/** Renders report results to CSV, Excel and PDF (FR-RPT-002). */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportExporter {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Colombo"));
    private static final Color NAVY = new Color(0x0B, 0x1F, 0x4B);
    private static final Color GOLD = new Color(0xC9, 0xA2, 0x27);

    private final SettingsService settings;
    private volatile BaseFont unicodeFont;
    private volatile boolean fontLoaded;

    public byte[] csv(ReportService.ReportResult r) {
        StringBuilder sb = new StringBuilder("﻿"); // BOM so Excel opens Sinhala/Tamil correctly
        List<ReportDefinition.Column> cols = r.columns();
        for (int i = 0; i < cols.size(); i++) sb.append(i > 0 ? "," : "").append(esc(cols.get(i).label()));
        sb.append("\r\n");
        for (Map<String, Object> row : r.rows()) {
            for (int i = 0; i < cols.size(); i++) sb.append(i > 0 ? "," : "").append(esc(str(row.get(cols.get(i).key()))));
            sb.append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public byte[] xlsx(ReportService.ReportResult r) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet(safeSheet(r.title()));
            CellStyle head = wb.createCellStyle();
            Font hf = wb.createFont();
            hf.setBold(true);
            hf.setColor(IndexedColors.WHITE.getIndex());
            head.setFont(hf);
            head.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle money = wb.createCellStyle();
            money.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));

            int rowIdx = 0;
            Row t = sh.createRow(rowIdx++);
            t.createCell(0).setCellValue(settings.get("org.name", "CIABOC") + " — " + r.title());
            Row g = sh.createRow(rowIdx++);
            g.createCell(0).setCellValue("Generated " + STAMP.format(r.generatedAt()) + " by " + r.generatedBy());
            rowIdx++;
            Row h = sh.createRow(rowIdx++);
            for (int i = 0; i < r.columns().size(); i++) {
                Cell c = h.createCell(i);
                c.setCellValue(r.columns().get(i).label());
                c.setCellStyle(head);
            }
            for (Map<String, Object> row : r.rows()) {
                Row x = sh.createRow(rowIdx++);
                for (int i = 0; i < r.columns().size(); i++) {
                    ReportDefinition.Column col = r.columns().get(i);
                    Object v = row.get(col.key());
                    Cell c = x.createCell(i);
                    if (v instanceof Number n && ("number".equals(col.type()) || "money".equals(col.type()))) {
                        c.setCellValue(n.doubleValue());
                        if ("money".equals(col.type())) c.setCellStyle(money);
                    } else c.setCellValue(str(v));
                }
            }
            for (int i = 0; i < r.columns().size() && i < 30; i++) sh.autoSizeColumn(i);
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Excel export failed", e);
        }
    }

    public byte[] pdf(ReportService.ReportResult r) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = new Document(r.columns().size() > 6 ? PageSize.A4.rotate() : PageSize.A4, 28, 28, 36, 36);
            PdfWriter.getInstance(doc, out);
            doc.open();
            BaseFont bf = font();
            com.lowagie.text.Font titleF = bf != null ? new com.lowagie.text.Font(bf, 14, com.lowagie.text.Font.BOLD, NAVY)
                    : FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, NAVY);
            com.lowagie.text.Font small = bf != null ? new com.lowagie.text.Font(bf, 8) : FontFactory.getFont(FontFactory.HELVETICA, 8);
            com.lowagie.text.Font headF = bf != null ? new com.lowagie.text.Font(bf, 8, com.lowagie.text.Font.BOLD, Color.WHITE)
                    : FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Color.WHITE);

            doc.add(new Paragraph(settings.get("org.name", "CIABOC"), small));
            doc.add(new Paragraph(r.title(), titleF));
            Paragraph meta = new Paragraph("Generated " + STAMP.format(r.generatedAt()) + " by " + r.generatedBy()
                    + (r.truncated() ? "  (truncated to maximum rows)" : ""), small);
            meta.setSpacingAfter(8);
            doc.add(meta);

            PdfPTable table = new PdfPTable(Math.max(1, r.columns().size()));
            table.setWidthPercentage(100);
            table.setHeaderRows(1);
            for (ReportDefinition.Column c : r.columns()) {
                PdfPCell cell = new PdfPCell(new Phrase(c.label(), headF));
                cell.setBackgroundColor(NAVY);
                cell.setBorderColor(GOLD);
                cell.setPadding(4);
                table.addCell(cell);
            }
            boolean alt = false;
            for (Map<String, Object> row : r.rows()) {
                for (ReportDefinition.Column c : r.columns()) {
                    PdfPCell cell = new PdfPCell(new Phrase(str(row.get(c.key())), small));
                    cell.setPadding(3);
                    if (alt) cell.setBackgroundColor(new Color(0xF4, 0xF6, 0xFB));
                    if ("number".equals(c.type()) || "money".equals(c.type())) cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                    table.addCell(cell);
                }
                alt = !alt;
            }
            if (r.rows().isEmpty()) {
                PdfPCell cell = new PdfPCell(new Phrase("No records", small));
                cell.setColspan(Math.max(1, r.columns().size()));
                table.addCell(cell);
            }
            doc.add(table);
            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("PDF export failed", e);
        }
    }

    /**
     * Optional Unicode font for Sinhala/Tamil text (NFR-LOC-01). Place a TTF at classpath:fonts/report-font.ttf
     * (e.g. Noto Sans Sinhala). Complex-script shaping in PDFs must be verified during UAT.
     */
    private BaseFont font() {
        if (!fontLoaded) {
            synchronized (this) {
                if (!fontLoaded) {
                    try {
                        ClassPathResource res = new ClassPathResource("fonts/report-font.ttf");
                        if (res.exists()) {
                            try (InputStream in = res.getInputStream()) {
                                unicodeFont = BaseFont.createFont("report-font.ttf", BaseFont.IDENTITY_H, BaseFont.EMBEDDED,
                                        true, in.readAllBytes(), null);
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Unicode report font could not be loaded: {}", e.getMessage());
                    }
                    fontLoaded = true;
                }
            }
        }
        return unicodeFont;
    }

    private static String str(Object v) {
        if (v == null) return "";
        if (v instanceof BigDecimal b) return b.toPlainString();
        if (v instanceof java.sql.Timestamp ts) return STAMP.format(ts.toInstant());
        if (v instanceof java.time.OffsetDateTime odt) return STAMP.format(odt.toInstant());
        if (v instanceof Boolean b) return b ? "Yes" : "No";
        return String.valueOf(v);
    }

    private static String esc(String s) {
        boolean q = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        // Neutralize spreadsheet formula injection.
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0 && !s.matches("^-?\\d+(\\.\\d+)?$")) s = "'" + s;
        s = s.replace("\"", "\"\"");
        return q ? "\"" + s + "\"" : s;
    }

    private static String safeSheet(String t) {
        String s = t.replaceAll("[\\\\/?*\\[\\]:]", " ");
        return s.length() > 31 ? s.substring(0, 31) : s;
    }
}
