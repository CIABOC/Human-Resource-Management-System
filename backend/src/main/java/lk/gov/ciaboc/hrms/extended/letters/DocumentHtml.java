package lk.gov.ciaboc.hrms.extended.letters;

import lk.gov.ciaboc.hrms.extended.letters.LetterEntities.IssuedDocument;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Pattern;

import static lk.gov.ciaboc.hrms.extended.ExtSupport.html;

/** Printable HTML for issued documents and the public verification page. Every dynamic value is escaped. */
final class DocumentHtml {
    static final ZoneId ZONE = ZoneId.of("Asia/Colombo");
    /** Content-Security-Policy for generated pages: no scripts, no external resources. */
    static final String CSP = "default-src 'none'; style-src 'unsafe-inline'; img-src data:; base-uri 'none'; form-action 'none'; frame-ancestors 'self'";

    private static final Pattern UNSAFE = Pattern.compile(
            "(?is)<\\s*(script|iframe|object|embed|link|meta|base|form|input|button|textarea|select|svg|math|frame|frameset|applet)\\b"
                    + "|\\bon[a-z]+\\s*=|javascript\\s*:|vbscript\\s*:|data\\s*:\\s*text/html|srcdoc\\s*=|expression\\s*\\(|@import");

    private DocumentHtml() {
    }

    /** Template bodies are authored by HR administrators; they may use formatting markup but nothing active. */
    static boolean unsafe(String templateHtml) {
        return templateHtml != null && UNSAFE.matcher(templateHtml).find();
    }

    static String formatDate(LocalDate d, String language) {
        if (d == null) return "";
        return "en".equals(language) ? d.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH))
                : d.format(DateTimeFormatter.ofPattern("yyyy.MM.dd"));
    }

    private static final String STYLE = """
            <style>
              @page { size: A4; margin: 18mm 18mm 20mm 18mm; }
              body { font-family: 'Noto Serif', 'Iskoola Pota', 'Latha', Georgia, serif; color: #0b1f3a; margin: 0; background: #fff; }
              .sheet { max-width: 720px; margin: 24px auto; padding: 32px 40px; position: relative; }
              .hdr { text-align: center; border-bottom: 3px solid #0b1f3a; padding-bottom: 10px; }
              .hdr .crest { width: 54px; height: 54px; border-radius: 50%; border: 3px solid #c9a227; margin: 0 auto 6px; display: flex;
                            align-items: center; justify-content: center; font-weight: 700; color: #0b1f3a; font-size: 13px; }
              .hdr .org { font-size: 17px; font-weight: 700; letter-spacing: .3px; }
              .hdr .sub { font-size: 12px; color: #4a5b74; margin-top: 2px; }
              .gold { height: 3px; background: #c9a227; margin-top: 3px; }
              .meta { display: flex; justify-content: space-between; font-size: 12.5px; margin: 16px 0 6px; }
              h1 { text-align: center; font-size: 18px; text-decoration: underline; margin: 22px 0 16px; }
              .body { font-size: 14px; line-height: 1.7; text-align: justify; }
              .sign { margin-top: 56px; font-size: 13.5px; }
              .sign .line { width: 220px; border-top: 1px dotted #0b1f3a; margin-bottom: 4px; }
              .foot { margin-top: 40px; border-top: 1px solid #c9a227; padding-top: 8px; font-size: 11px; color: #4a5b74; }
              .code { font-family: 'Consolas', monospace; font-weight: 700; color: #0b1f3a; letter-spacing: 1px; }
              .void { position: absolute; top: 40%; left: 0; right: 0; text-align: center; font-size: 72px; color: rgba(180,30,30,.25);
                      transform: rotate(-24deg); font-weight: 800; pointer-events: none; }
              @media print { .sheet { margin: 0; padding: 0; } }
            </style>""";

    static String printable(IssuedDocument d, String orgName, String signatory, String verifyUrl) {
        String date = formatDate(d.getIssuedAt().atZone(ZONE).toLocalDate(), d.getLanguage());
        return "<!doctype html><html lang=\"" + html(d.getLanguage()) + "\"><head><meta charset=\"utf-8\"><title>" + html(d.getDocumentNo())
                + "</title>" + STYLE + "</head><body><div class=\"sheet\">"
                + (d.getStatus() == IssuedDocument.Status.REVOKED ? "<div class=\"void\">REVOKED</div>" : "")
                + "<div class=\"hdr\"><div class=\"crest\">CIABOC</div><div class=\"org\">" + html(orgName) + "</div>"
                + "<div class=\"sub\">Human Resources &middot; මානව සම්පත් &middot; மனித வளம்</div></div><div class=\"gold\"></div>"
                + "<div class=\"meta\"><span>Ref: " + html(d.getDocumentNo()) + "</span><span>" + html(date) + "</span></div>"
                + "<h1>" + html(d.getTitle()) + "</h1>"
                + "<div class=\"body\">" + d.getBodyHtml() + "</div>"
                + "<div class=\"sign\"><div class=\"line\"></div>" + html(signatory) + "<br>" + html(orgName) + "</div>"
                + "<div class=\"foot\">Verification code: <span class=\"code\">" + html(d.getVerificationCode()) + "</span><br>"
                + "Verify the authenticity of this document at " + html(verifyUrl) + "</div>"
                + "</div></body></html>";
    }

    static String verificationPage(String orgName, LetterService.Verification v) {
        String status = v == null ? "No document matches this verification code."
                : v.valid() ? "This document is VALID." : "This document has been REVOKED and is no longer valid.";
        StringBuilder sb = new StringBuilder("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><meta name=\"robots\" content=\"noindex\">"
                + "<title>Document verification</title>" + STYLE + "</head><body><div class=\"sheet\">"
                + "<div class=\"hdr\"><div class=\"crest\">CIABOC</div><div class=\"org\">" + html(orgName) + "</div>"
                + "<div class=\"sub\">Document verification</div></div><div class=\"gold\"></div>"
                + "<h1>" + html(status) + "</h1>");
        if (v != null) {
            sb.append("<div class=\"body\"><table style=\"margin:0 auto;font-size:14px\">")
                    .append("<tr><td style=\"padding:4px 16px\">Document</td><td><strong>").append(html(v.documentType())).append("</strong></td></tr>")
                    .append("<tr><td style=\"padding:4px 16px\">Issued on</td><td><strong>").append(html(v.issueDate())).append("</strong></td></tr>")
                    .append("<tr><td style=\"padding:4px 16px\">Issued to</td><td><strong>").append(html(v.holderName())).append("</strong></td></tr>")
                    .append("</table></div>");
        }
        sb.append("<div class=\"foot\">Only minimal details are shown. Contact the Human Resources Division of the Commission for further confirmation.</div>");
        return sb.append("</div></body></html>").toString();
    }
}
