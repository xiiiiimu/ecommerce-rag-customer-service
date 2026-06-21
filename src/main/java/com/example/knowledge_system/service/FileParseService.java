package com.example.knowledge_system.service;

import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.Loader;
import java.io.File;

@Service
public class FileParseService {

    private final Tika tika = new Tika();

    public String parse(String filePath, String fileName) {
        try {
            File file = new File(filePath);

            if (fileName.toLowerCase().endsWith(".pdf")) {
                return parsePdf(file);
            }

            return tika.parseToString(file);

        } catch (Exception e) {
            throw new RuntimeException("文件解析失败: " + fileName, e);
        }
    }
    private String parsePdf(File file) throws Exception {
        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);

            if (text == null || text.isBlank()) {
                throw new RuntimeException("PDF解析为空，可能是扫描件");
            }

            return text;
        }
    }
}