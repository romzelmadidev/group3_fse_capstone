package com.bank.compliance.generator;

import org.openpdf.text.*;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Component
public class GlTrialBalanceGenerator {

    public record GlEntry(String glCode, String glName, String category, BigDecimal debit, BigDecimal credit) {}

    public byte[] generateExcel(LocalDate businessDate, List<GlEntry> entries) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("GL Trial Balance");

            // Header style
            org.apache.poi.ss.usermodel.Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());

            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);

            // Row 0: Title
            org.apache.poi.ss.usermodel.Row titleRow = sheet.createRow(0);
            org.apache.poi.ss.usermodel.Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue("GENERAL LEDGER TRIAL BALANCE - " + businessDate);

            // Row 2: Headers
            org.apache.poi.ss.usermodel.Row headRow = sheet.createRow(2);
            String[] columns = {"GL Code", "GL Account Name", "Category", "Debit (PHP)", "Credit (PHP)"};
            for (int i = 0; i < columns.length; i++) {
                org.apache.poi.ss.usermodel.Cell cell = headRow.createCell(i);
                cell.setCellValue(columns[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowIdx = 3;
            BigDecimal totalDebit = BigDecimal.ZERO;
            BigDecimal totalCredit = BigDecimal.ZERO;

            if (entries != null) {
                for (GlEntry e : entries) {
                    org.apache.poi.ss.usermodel.Row row = sheet.createRow(rowIdx++);
                    row.createCell(0).setCellValue(e.glCode());
                    row.createCell(1).setCellValue(e.glName());
                    row.createCell(2).setCellValue(e.category());
                    row.createCell(3).setCellValue(e.debit().doubleValue());
                    row.createCell(4).setCellValue(e.credit().doubleValue());
                    totalDebit = totalDebit.add(e.debit());
                    totalCredit = totalCredit.add(e.credit());
                }
            }

            // Total row
            org.apache.poi.ss.usermodel.Row totalRow = sheet.createRow(rowIdx);
            org.apache.poi.ss.usermodel.Cell totalLabel = totalRow.createCell(1);
            totalLabel.setCellValue("TOTAL (LEVEL 1 RECONCILIATION)");
            totalRow.createCell(3).setCellValue(totalDebit.doubleValue());
            totalRow.createCell(4).setCellValue(totalCredit.doubleValue());

            for (int i = 0; i < columns.length; i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate GL Trial Balance Excel", e);
        }
    }

    public byte[] generatePdf(LocalDate businessDate, List<GlEntry> entries) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 36, 36, 36, 36);
            PdfWriter.getInstance(document, baos);
            document.open();

            Paragraph title = new Paragraph("UNIVERSAL RETAIL BANK - GL TRIAL BALANCE",
                    FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16, Color.DARK_GRAY));
            title.setAlignment(Element.ALIGN_CENTER);
            title.setSpacingAfter(10);
            document.add(title);

            Paragraph subtitle = new Paragraph("Business Date: " + businessDate + " | Level 1 Balancing Assertion: DR == CR",
                    FontFactory.getFont(FontFactory.HELVETICA, 10, Color.GRAY));
            subtitle.setAlignment(Element.ALIGN_CENTER);
            subtitle.setSpacingAfter(15);
            document.add(subtitle);

            PdfPTable table = new PdfPTable(5);
            table.setWidthPercentage(100);
            table.setWidths(new float[]{15, 35, 15, 17, 18});

            table.addCell(createHeader("GL Code"));
            table.addCell(createHeader("Account Name"));
            table.addCell(createHeader("Category"));
            table.addCell(createHeader("Debit (PHP)"));
            table.addCell(createHeader("Credit (PHP)"));

            BigDecimal totalDebit = BigDecimal.ZERO;
            BigDecimal totalCredit = BigDecimal.ZERO;

            if (entries != null) {
                for (GlEntry e : entries) {
                    table.addCell(createCell(e.glCode()));
                    table.addCell(createCell(e.glName()));
                    table.addCell(createCell(e.category()));
                    table.addCell(createCell(e.debit().toPlainString()));
                    table.addCell(createCell(e.credit().toPlainString()));
                    totalDebit = totalDebit.add(e.debit());
                    totalCredit = totalCredit.add(e.credit());
                }
            }

            PdfPCell totalLbl = new PdfPCell(new Phrase("TOTAL BALANCE", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9)));
            totalLbl.setColspan(3);
            table.addCell(totalLbl);
            table.addCell(createCell(totalDebit.toPlainString()));
            table.addCell(createCell(totalCredit.toPlainString()));

            document.add(table);
            document.close();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate GL Trial Balance PDF", e);
        }
    }

    private PdfPCell createHeader(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.WHITE)));
        cell.setBackgroundColor(new Color(44, 62, 80));
        cell.setPadding(4);
        return cell;
    }

    private PdfPCell createCell(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, FontFactory.getFont(FontFactory.HELVETICA, 8)));
        cell.setPadding(4);
        return cell;
    }
}
