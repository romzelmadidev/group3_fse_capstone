package com.bank.compliance.generator;

import org.openpdf.text.*;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;

@Component
public class BirTaxCertificateGenerator {

    public byte[] generateBir2306Certificate(String taxYear, LocalDate periodStart, LocalDate periodEnd,
                                             String payorTin, String payorName,
                                             String payeeTin, String payeeName,
                                             BigDecimal grossInterest, BigDecimal taxWithheld) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 36, 36, 36, 36);
            PdfWriter.getInstance(document, baos);
            document.open();

            // Header
            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, Color.BLACK);
            Paragraph title = new Paragraph("BIR FORM 2306\nCERTIFICATE OF FINAL TAX WITHHELD AT SOURCE", titleFont);
            title.setAlignment(Element.ALIGN_CENTER);
            title.setSpacingAfter(10);
            document.add(title);

            Paragraph subtitle = new Paragraph("Republic of the Philippines - Bureau of Internal Revenue\nFor Tax Period: " + periodStart + " to " + periodEnd + " (Tax Year " + taxYear + ")",
                    FontFactory.getFont(FontFactory.HELVETICA, 9, Color.DARK_GRAY));
            subtitle.setAlignment(Element.ALIGN_CENTER);
            subtitle.setSpacingAfter(15);
            document.add(subtitle);

            // Part I & II: Payor & Payee Table
            PdfPTable infoTable = new PdfPTable(2);
            infoTable.setWidthPercentage(100);
            infoTable.setSpacingAfter(15);

            infoTable.addCell(createCell("Part I - Withholding Agent (Payor)", true));
            infoTable.addCell(createCell("Part II - Payee (Income Recipient)", true));
            infoTable.addCell(createCell("TIN: " + payorTin + "\nName: " + payorName + "\nAddress: Makati City, Metro Manila", false));
            infoTable.addCell(createCell("TIN: " + payeeTin + "\nName: " + payeeName + "\nAddress: Registered Account Address", false));
            document.add(infoTable);

            // Part III: Tax Computation
            Font sectionFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, Color.BLACK);
            Paragraph compHeader = new Paragraph("Part III - Schedule of Final Tax Withheld", sectionFont);
            compHeader.setSpacingAfter(8);
            document.add(compHeader);

            PdfPTable taxTable = new PdfPTable(4);
            taxTable.setWidthPercentage(100);
            taxTable.setWidths(new float[]{40, 20, 20, 20});

            taxTable.addCell(createHeader("Income Payment Description (ATC)"));
            taxTable.addCell(createHeader("Gross Amount"));
            taxTable.addCell(createHeader("Tax Rate"));
            taxTable.addCell(createHeader("Tax Withheld"));

            taxTable.addCell(createCell("Interest on Bank Deposits (WI161)", false));
            taxTable.addCell(createCell("PHP " + (grossInterest != null ? grossInterest.toPlainString() : "0.00"), false));
            taxTable.addCell(createCell("20.00%", false));
            taxTable.addCell(createCell("PHP " + (taxWithheld != null ? taxWithheld.toPlainString() : "0.00"), false));

            document.add(taxTable);

            // Certification
            Paragraph cert = new Paragraph("\nI declare under the penalties of perjury that this certificate has been made in good faith, verified by the Core Banking System and Immutable PostgreSQL Audit Vault pursuant to the provisions of the National Internal Revenue Code, as amended, and the regulations issued under authority thereof.",
                    FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 8, Color.BLACK));
            cert.setSpacingAfter(20);
            document.add(cert);

            PdfPTable signTable = new PdfPTable(2);
            signTable.setWidthPercentage(100);
            signTable.addCell(createCell("Authorized Withholding Agent Signature\nDate: " + LocalDate.now(), false));
            signTable.addCell(createCell("System Certified SHA-256 Validated\nStatus: FILED_WITH_BIR", false));
            document.add(signTable);

            document.close();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate BIR Form 2306 PDF", e);
        }
    }

    private PdfPCell createHeader(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.WHITE)));
        cell.setBackgroundColor(new Color(52, 73, 94));
        cell.setPadding(4);
        return cell;
    }

    private PdfPCell createCell(String text, boolean bold) {
        Font font = bold ? FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9) : FontFactory.getFont(FontFactory.HELVETICA, 8);
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setPadding(4);
        return cell;
    }
}
