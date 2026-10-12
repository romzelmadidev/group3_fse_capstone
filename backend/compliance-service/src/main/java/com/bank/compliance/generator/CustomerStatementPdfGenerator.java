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
import java.util.List;
import java.util.Map;

@Component
public class CustomerStatementPdfGenerator {

    public byte[] generateStatement(String accountId, String accountNumber, BigDecimal currentBalance,
                                    LocalDate statementPeriodStart, LocalDate statementPeriodEnd,
                                    List<Map<String, Object>> transactions) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 36, 36, 36, 36);
            PdfWriter.getInstance(document, baos);
            document.open();

            // Header
            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, Color.DARK_GRAY);
            Paragraph title = new Paragraph("UNIVERSAL RETAIL BANK - ACCOUNT STATEMENT", titleFont);
            title.setAlignment(Element.ALIGN_CENTER);
            title.setSpacingAfter(15);
            document.add(title);

            // Account Metadata Table
            PdfPTable metaTable = new PdfPTable(2);
            metaTable.setWidthPercentage(100);
            metaTable.setSpacingAfter(15);

            metaTable.addCell(createCell("Account Number: " + accountNumber, false));
            metaTable.addCell(createCell("Statement Date: " + statementPeriodEnd, false));
            metaTable.addCell(createCell("Account ID: " + accountId, false));
            metaTable.addCell(createCell("Period: " + statementPeriodStart + " to " + statementPeriodEnd, false));
            metaTable.addCell(createCell("Currency: PHP", false));
            metaTable.addCell(createCell("Closing Balance: PHP " + (currentBalance != null ? currentBalance.toPlainString() : "0.00"), true));
            document.add(metaTable);

            // Transactions Header
            Font sectionFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, Color.BLACK);
            Paragraph txHeader = new Paragraph("Transaction Activity", sectionFont);
            txHeader.setSpacingAfter(8);
            document.add(txHeader);

            // Transactions Table
            PdfPTable txTable = new PdfPTable(4);
            txTable.setWidthPercentage(100);
            txTable.setWidths(new float[]{25, 35, 20, 20});

            txTable.addCell(createHeaderCell("Date / Time"));
            txTable.addCell(createHeaderCell("Description"));
            txTable.addCell(createHeaderCell("Mutation Type"));
            txTable.addCell(createHeaderCell("Amount (PHP)"));

            if (transactions != null && !transactions.isEmpty()) {
                for (Map<String, Object> tx : transactions) {
                    txTable.addCell(createCell(String.valueOf(tx.getOrDefault("date", statementPeriodEnd)), false));
                    txTable.addCell(createCell(String.valueOf(tx.getOrDefault("description", "Transfer")), false));
                    txTable.addCell(createCell(String.valueOf(tx.getOrDefault("type", "DEBIT")), false));
                    txTable.addCell(createCell(String.valueOf(tx.getOrDefault("amount", "0.00")), false));
                }
            } else {
                PdfPCell emptyCell = new PdfPCell(new Phrase("No transactions recorded during this statement cycle."));
                emptyCell.setColspan(4);
                emptyCell.setHorizontalAlignment(Element.ALIGN_CENTER);
                emptyCell.setPadding(8);
                txTable.addCell(emptyCell);
            }

            document.add(txTable);

            // Footer Notice
            Paragraph notice = new Paragraph("\nThis is a system-generated official bank e-statement certified by the Immutable PostgreSQL Audit Vault and archived in Microsoft Azurite Object Storage.",
                    FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 8, Color.GRAY));
            notice.setAlignment(Element.ALIGN_CENTER);
            document.add(notice);

            document.close();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate customer statement PDF", e);
        }
    }

    private PdfPCell createHeaderCell(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.WHITE)));
        cell.setBackgroundColor(new Color(41, 128, 185));
        cell.setPadding(5);
        return cell;
    }

    private PdfPCell createCell(String text, boolean bold) {
        Font font = bold ? FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9) : FontFactory.getFont(FontFactory.HELVETICA, 9);
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setPadding(4);
        cell.setBorder(Rectangle.NO_BORDER);
        return cell;
    }
}
